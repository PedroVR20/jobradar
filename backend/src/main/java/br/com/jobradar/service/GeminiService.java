package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente pra API do Gemini (generateContent) — usado por CoverLetterService,
 * o classificador de senioridade/stack e a detecção de duplicatas. Sem
 * nenhuma key configurada, {@link #isEnabled()} volta false e {@link #generateText}
 * devolve null sem tentar chamar a API — os recursos de IA ficam desativados
 * de forma transparente, sem quebrar nada que já funcionava sem eles.
 *
 * <p><b>Pool de keys:</b> o free tier do Gemini tem cota por minuto E por dia,
 * e (até onde sabemos hoje) a cota é por projeto do Google Cloud, não por key
 * individual — múltiplas keys do MESMO projeto compartilham o mesmo balde.
 * Configurando {@code GEMINI_API_KEYS} (plural, separadas por vírgula) com
 * keys de projetos diferentes, cada uma tem sua própria cota, e esse serviço
 * faz o rodízio sozinho: quando uma key esbarra em rate limit, tenta a
 * próxima automaticamente, na mesma chamada — sem o usuário perceber, até
 * todas se esgotarem. {@code GEMINI_API_KEY} (singular) continua funcionando
 * como antes, como uma pool de uma key só.</p>
 */
@Service
@Slf4j
public class GeminiService {

    @Value("${gemini.api-key:}")
    private String legacyApiKey;

    @Value("${gemini.api-keys:}")
    private String apiKeysRaw;

    @Value("${gemini.model:gemini-flash-latest}")
    private String model;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private List<KeySlot> pool = List.of();
    private final AtomicInteger cursor = new AtomicInteger(0);

    // Estado por key: só guardamos os últimos 6 caracteres em memória, pra
    // identificar qual key é qual nos logs sem nunca logar/expor a key inteira.
    private static class KeySlot {
        final String key;
        final String tail;
        volatile LocalDate dailyExhaustedOn = null;
        final AtomicInteger requestsToday = new AtomicInteger(0);
        volatile LocalDate counterDate = LocalDate.now();

        KeySlot(String key) {
            this.key = key;
            this.tail = key.length() > 6 ? key.substring(key.length() - 6) : key;
        }

        void resetIfNewDay() {
            LocalDate today = LocalDate.now();
            if (!today.equals(counterDate)) {
                counterDate = today;
                requestsToday.set(0);
                dailyExhaustedOn = null;
            }
        }

        boolean isExhaustedToday() {
            resetIfNewDay();
            return dailyExhaustedOn != null && dailyExhaustedOn.equals(LocalDate.now());
        }
    }

    // Resultado interno de UMA tentativa contra UMA key — carrega a
    // classificação (diário vs por minuto) que o rodízio em generate() usa
    // pra decidir se marca a key como esgotada ou só pula pra próxima.
    // Nunca sai do GeminiService: generate() converte pro GeminiResult
    // público antes de devolver, sem essas marcações internas.
    private record Attempt(String text, String message, boolean dailyLimit, boolean minuteLimit) {
        boolean ok() { return text != null; }
        boolean isRateLimit() { return dailyLimit || minuteLimit; }
    }

    @PostConstruct
    void initPool() {
        List<String> keys = new ArrayList<>();
        if (apiKeysRaw != null && !apiKeysRaw.isBlank()) {
            keys.addAll(Arrays.stream(apiKeysRaw.split(","))
                    .map(String::trim)
                    .filter(k -> !k.isBlank())
                    .toList());
        } else if (legacyApiKey != null && !legacyApiKey.isBlank()) {
            keys.add(legacyApiKey.trim());
        }
        pool = keys.stream().map(KeySlot::new).toList();
        if (pool.size() > 1) {
            log.info("=== Gemini: pool com {} keys configuradas (rodízio automático em rate limit) ===", pool.size());
        } else if (pool.size() == 1) {
            log.info("=== Gemini: 1 key configurada ===");
        }
    }

    public boolean isEnabled() {
        return !pool.isEmpty();
    }

    // Exposto pro endpoint de status (GET /api/jobs/ai-status) — só o nome do
    // modelo, nunca a key.
    public String getModel() {
        return model;
    }

    public int getRequestsToday() {
        return pool.stream().mapToInt(k -> {
            k.resetIfNewDay();
            return k.requestsToday.get();
        }).sum();
    }

    public record KeyPoolStatus(int total, int availableToday, int exhaustedToday) {}

    public KeyPoolStatus getKeyPoolStatus() {
        int total = pool.size();
        long exhausted = pool.stream().filter(KeySlot::isExhaustedToday).count();
        return new KeyPoolStatus(total, total - (int) exhausted, (int) exhausted);
    }

    /**
     * Resultado de uma chamada ao Gemini: {@code text} vem preenchido quando
     * deu certo; caso contrário {@code errorMessage} traz uma explicação em
     * português já pronta pra mostrar ao usuário, e {@code rateLimited} diz
     * se a falha foi por limite (pra quem chama decidir o status HTTP).
     */
    public record GeminiResult(String text, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return text != null;
        }
    }

    /**
     * Manda um prompt de texto puro pro Gemini. Tenta as keys da pool em
     * rodízio (a partir do cursor atual, avançando a cada chamada pra
     * espalhar a carga) até uma responder com sucesso ou todas falharem por
     * rate limit nessa passada — nunca lança exceção, sempre devolve um
     * {@link GeminiResult}.
     */
    public GeminiResult generate(String prompt) {
        if (!isEnabled()) {
            return new GeminiResult(null, "Recurso de IA não configurado — defina GEMINI_API_KEY ou GEMINI_API_KEYS no .env", false);
        }

        int size = pool.size();
        Attempt last = null;
        int tentativas = 0;

        for (int i = 0; i < size; i++) {
            int idx = Math.floorMod(cursor.getAndIncrement(), size);
            KeySlot slot = pool.get(idx);
            if (slot.isExhaustedToday()) continue;

            tentativas++;
            slot.requestsToday.incrementAndGet();
            Attempt attempt = call(prompt, slot);
            if (attempt.ok()) {
                return new GeminiResult(attempt.text(), null, false);
            }
            last = attempt;
            if (attempt.dailyLimit()) {
                slot.dailyExhaustedOn = LocalDate.now();
                log.warn("Gemini: key ...{} esgotou a cota diária, tirando do rodízio de hoje", slot.tail);
                continue; // tenta a próxima key imediatamente
            }
            if (attempt.minuteLimit()) {
                continue; // rate limit por minuto — tenta a próxima key na mesma passada
            }
            // erro que não é de rate limit (rede, resposta inesperada) — não
            // adianta insistir com outra key, devolve na hora
            return new GeminiResult(null, attempt.message(), false);
        }

        if (tentativas == 0) {
            // todas as keys já estavam marcadas como esgotadas hoje antes de tentar
            return new GeminiResult(null, dailyLimitMessage(size), true);
        }
        if (last != null && last.dailyLimit() && size > 1) {
            return new GeminiResult(null, dailyLimitMessage(size), true);
        }
        return new GeminiResult(null,
                last != null ? last.message() : "Não foi possível falar com o Gemini agora. Tente de novo em instantes.",
                last != null && last.isRateLimit());
    }

    private String dailyLimitMessage(int poolSize) {
        return poolSize > 1
                ? String.format("Todas as %d keys do Gemini atingiram o limite diário do free tier hoje. Volta a funcionar amanhã.", poolSize)
                : "Limite diário gratuito do Gemini atingido (free tier permite poucas requisições por dia). "
                        + "Volta a funcionar automaticamente amanhã — ou gere uma chave nova em aistudio.google.com/apikey.";
    }

    private Attempt call(String prompt, KeySlot slot) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + slot.key;

            Map<String, Object> body = Map.of(
                    "contents", List.of(Map.of(
                            "parts", List.of(Map.of("text", prompt))
                    ))
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode textNode = root.at("/candidates/0/content/parts/0/text");
            if (textNode.isMissingNode()) {
                log.warn("Gemini respondeu sem texto no formato esperado");
                return new Attempt(null, "O Gemini devolveu uma resposta inesperada. Tente de novo.", false, false);
            }
            return new Attempt(textNode.asText().trim(), null, false, false);
        } catch (HttpClientErrorException e) {
            return describeHttpError(e, slot);
        } catch (RestClientException e) {
            log.warn("Gemini indisponível: {}", e.getMessage());
            return new Attempt(null, "Gemini indisponível no momento (rede/timeout). Tente de novo em instantes.", false, false);
        } catch (Exception e) {
            log.warn("Erro ao processar resposta do Gemini: {}", e.getMessage());
            return new Attempt(null, "Erro ao processar a resposta do Gemini. Tente de novo.", false, false);
        }
    }

    private Attempt describeHttpError(HttpClientErrorException e, KeySlot slot) {
        HttpStatusCode status = e.getStatusCode();
        String bodyText = e.getResponseBodyAsString();

        if (status.value() == 429) {
            boolean daily = bodyText != null && bodyText.contains("PerDay");
            if (daily) {
                log.warn("Gemini: key ...{} bateu no limite DIÁRIO: {}", slot.tail, bodyText);
                return new Attempt(null,
                        "Limite diário gratuito do Gemini atingido (free tier permite poucas requisições por dia). "
                                + "Volta a funcionar automaticamente amanhã — ou gere uma chave nova em aistudio.google.com/apikey.",
                        true, false);
            }
            log.warn("Gemini: key ...{} bateu no limite por minuto: {}", slot.tail, bodyText);
            return new Attempt(null,
                    "Limite de requisições por minuto do Gemini atingido. Espere cerca de 20-30 segundos e tente de novo.",
                    false, true);
        }

        log.warn("Gemini respondeu {} : {}", status, bodyText);
        return new Attempt(null, "O Gemini recusou a requisição (erro " + status.value() + "). Tente de novo em instantes.", false, false);
    }

    /**
     * Atalho pros chamadores que só querem o texto e tratam qualquer falha
     * como "IA indisponível agora" sem precisar do motivo (classificador de
     * senioridade, verificador de duplicatas) — mantém a assinatura simples
     * de antes.
     */
    public String generateText(String prompt) {
        return generate(prompt).text();
    }

    /**
     * Gemini às vezes envolve JSON pedido no prompt em cercas de código
     * markdown (```json ... ```) mesmo quando instruído a não fazer isso —
     * helper compartilhado por quem pede resposta em JSON (classificador,
     * verificador de duplicatas, match score, perguntas de entrevista).
     */
    public static String stripMarkdownFences(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }

    /**
     * Monta o bloco de "feedback de gerações anteriores" que CoverLetter/
     * MatchScore/InterviewQuestions inserem no prompt quando o usuário já
     * avaliou (👍/👎 + comentário) resultados passados desse mesmo recurso —
     * histórico mantido só no localStorage do frontend (useAiFeedback),
     * nunca persistido aqui. É a forma prática de "aprender" preferência do
     * usuário sem fine-tuning: o modelo lê os comentários como instrução de
     * estilo pra essa geração. Devolve string vazia (não null) quando não há
     * feedback, pra poder ser sempre interpolada no prompt sem checagem extra.
     */
    public static String feedbackSection(String feedbackContext) {
        if (feedbackContext == null || feedbackContext.isBlank()) return "";
        return """

                Feedback do usuário sobre gerações anteriores desse tipo — leve em conta
                pra ajustar tom/estilo/abordagem desta vez, sem forçar o que não se
                aplicar a esta vaga específica:
                %s
                """.formatted(feedbackContext);
    }
}

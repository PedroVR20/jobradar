package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente fino pra API do Gemini (generateContent) — usado por CoverLetterService,
 * o classificador de senioridade/stack e a detecção de duplicatas. Sem
 * {@code GEMINI_API_KEY} configurada, {@link #isEnabled()} volta false e
 * {@link #generateText} devolve null sem tentar chamar a API — os recursos de
 * IA ficam desativados de forma transparente, sem quebrar nada que já
 * funcionava sem eles.
 *
 * Free tier do Gemini tem limite de requisições por minuto **e** por dia —
 * por isso os chamadores devem ser econômicos (ex: só classificar por IA os
 * casos que o regex não resolveu, não toda vaga nova). Quando o limite é
 * atingido, {@link #generate} distingue os dois casos (a mensagem do Google
 * indica qual quota estourou) pra devolver uma mensagem que diga a real —
 * "espera 20s" e "só amanhã" pedem reações bem diferentes do usuário.
 */
@Service
@Slf4j
public class GeminiService {

    @Value("${gemini.api-key:}")
    private String apiKey;

    @Value("${gemini.model:gemini-2.5-flash}")
    private String model;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // Contador aproximado de chamadas feitas hoje — só pra dar um sinal ao
    // usuário no painel de Configurações antes que ele esbarre no limite.
    // Não é a contagem oficial do Google (essa só existe no dashboard deles em
    // ai.dev/rate-limit); reseta à meia-noite do servidor e também se o
    // backend reiniciar, então é aproximado, não uma fonte da verdade.
    private final AtomicInteger requestsToday = new AtomicInteger(0);
    private volatile LocalDate counterDate = LocalDate.now();

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    // Exposto pro endpoint de status (GET /api/jobs/ai-status) — só o nome do
    // modelo, nunca a key.
    public String getModel() {
        return model;
    }

    public int getRequestsToday() {
        resetCounterIfNewDay();
        return requestsToday.get();
    }

    private void resetCounterIfNewDay() {
        LocalDate today = LocalDate.now();
        if (!today.equals(counterDate)) {
            counterDate = today;
            requestsToday.set(0);
        }
    }

    /**
     * Resultado de uma chamada ao Gemini: {@code text} vem preenchido quando
     * deu certo; caso contrário {@code errorMessage} traz uma explicação em
     * português já pronta pra mostrar ao usuário (o motivo real: limite por
     * minuto, limite diário, ou falha genérica).
     */
    public record GeminiResult(String text, String errorMessage) {
        public boolean ok() {
            return text != null;
        }
    }

    /**
     * Manda um prompt de texto puro pro Gemini. Nunca lança exceção — sempre
     * devolve um {@link GeminiResult}, com {@code errorMessage} explicando o
     * motivo quando falha (usado por {@code CoverLetterService}, que precisa
     * mostrar isso ao usuário). Chamadores que só querem "deu certo ou não"
     * (classificador, verificador de duplicatas) podem usar
     * {@link #generateText} e ignorar o motivo.
     */
    public GeminiResult generate(String prompt) {
        if (!isEnabled()) {
            return new GeminiResult(null, "Recurso de IA não configurado — defina GEMINI_API_KEY no .env");
        }
        resetCounterIfNewDay();
        requestsToday.incrementAndGet();
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + apiKey;

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
                return new GeminiResult(null, "O Gemini devolveu uma resposta inesperada. Tente de novo.");
            }
            return new GeminiResult(textNode.asText().trim(), null);
        } catch (HttpClientErrorException e) {
            return new GeminiResult(null, describeHttpError(e));
        } catch (RestClientException e) {
            log.warn("Gemini indisponível: {}", e.getMessage());
            return new GeminiResult(null, "Gemini indisponível no momento (rede/timeout). Tente de novo em instantes.");
        } catch (Exception e) {
            log.warn("Erro ao processar resposta do Gemini: {}", e.getMessage());
            return new GeminiResult(null, "Erro ao processar a resposta do Gemini. Tente de novo.");
        }
    }

    private String describeHttpError(HttpClientErrorException e) {
        HttpStatusCode status = e.getStatusCode();
        String bodyText = e.getResponseBodyAsString();

        if (status.value() == 429) {
            // O corpo do erro do Google identifica qual quota estourou pelo
            // quotaId — "PerDay" vs "PerMinute" — e pelo campo "limit" do
            // texto da mensagem. Free tier do gemini-2.5-flash hoje é bem
            // apertado (poucas requisições por minuto e por dia), então vale
            // deixar claro qual dos dois é o caso, já que pedem esperas bem
            // diferentes.
            boolean daily = bodyText != null && bodyText.contains("PerDay");
            if (daily) {
                log.warn("Gemini: limite DIÁRIO do free tier atingido: {}", bodyText);
                return "Limite diário gratuito do Gemini atingido (free tier permite poucas requisições por dia). "
                        + "Volta a funcionar automaticamente amanhã — ou gere uma chave nova em aistudio.google.com/apikey.";
            }
            log.warn("Gemini: limite por minuto atingido: {}", bodyText);
            return "Limite de requisições por minuto do Gemini atingido. Espere cerca de 20-30 segundos e tente de novo.";
        }

        log.warn("Gemini respondeu {} : {}", status, bodyText);
        return "O Gemini recusou a requisição (erro " + status.value() + "). Tente de novo em instantes.";
    }

    /**
     * Atalho pros chamadores que só querem o texto e tratam qualquer falha
     * como "IA indisponível agora" sem precisar do motivo (classificador de
     * senioridade, verificador de duplicatas) — mantém a mesma assinatura
     * simples de antes.
     */
    public String generateText(String prompt) {
        return generate(prompt).text();
    }
}

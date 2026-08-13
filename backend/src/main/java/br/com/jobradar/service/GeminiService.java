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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente pra API do Gemini (generateContent) — usado por CoverLetterService,
 * o classificador de senioridade/stack, a detecção de duplicatas e o chat
 * livre do Jarvis (function-calling, ver {@link #chat}). Sem nenhuma key
 * configurada, {@link #isEnabled()} volta false e {@link #generateText}
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

    // Resultado interno de UMA tentativa contra UMA key — carrega o conteúdo
    // bruto (`candidates/0/content`) quando dá certo, e a classificação do
    // erro (diário vs por minuto) que o rodízio usa pra decidir se marca a
    // key como esgotada ou só pula pra próxima. Nunca sai do GeminiService.
    // keyRejected: 401/403 — a KEY em si foi recusada (inválida, revogada,
    // sem permissão), não é limite de cota. Antes isso abortava o rodízio
    // inteiro na primeira key ruim, mesmo com outras keys saudáveis no pool
    // logo depois — agora pula pra próxima key igual faz com rate limit,
    // bem mais persistente (uma key com problema não devia derrubar o chat
    // inteiro se há outras funcionando).
    private record Attempt(JsonNode content, String message, boolean dailyLimit, boolean minuteLimit, boolean keyRejected) {
        Attempt(JsonNode content, String message, boolean dailyLimit, boolean minuteLimit) {
            this(content, message, dailyLimit, minuteLimit, false);
        }
        boolean ok() { return content != null; }
        boolean isRateLimit() { return dailyLimit || minuteLimit; }
        boolean skipToNextKey() { return dailyLimit || minuteLimit || keyRejected; }
    }

    // Resultado do rodízio completo — sucesso com o content bruto, ou falha
    // com mensagem já pronta pra mostrar ao usuário. Base compartilhada por
    // generate() (texto simples) e chat() (function-calling).
    private record PoolResult(JsonNode content, String errorMessage, boolean rateLimited) {
        boolean ok() { return content != null; }
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
     * rodízio até uma responder com sucesso ou todas falharem por rate
     * limit nessa passada — nunca lança exceção, sempre devolve um
     * {@link GeminiResult}.
     */
    public GeminiResult generate(String prompt) {
        Map<String, Object> body = Map.of(
                "contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", prompt))))
        );
        PoolResult resultado = attemptWithPool(body);
        if (!resultado.ok()) {
            return new GeminiResult(null, resultado.errorMessage(), resultado.rateLimited());
        }
        JsonNode textNode = resultado.content().at("/parts/0/text");
        if (textNode.isMissingNode()) {
            return new GeminiResult(null, "O Gemini devolveu uma resposta inesperada. Tente de novo.", false);
        }
        return new GeminiResult(textNode.asText().trim(), null, false);
    }

    // ===================== Embeddings (busca semântica) =====================

    // Modelo dedicado de embedding — quota SEPARADA da de generateContent
    // (1500 req/min, sem limite diário documentado, bem mais folgada que os
    // 20/dia do gemini-flash-latest no free tier). Por isso NÃO reaproveita
    // KeySlot.dailyExhaustedOn: marcar uma key como "esgotada" aqui esgotaria
    // ela erradamente pro chat também, e vice-versa — são baldes diferentes,
    // mesmo sendo a mesma key/projeto.
    //
    // BUG REAL corrigido (Fase 1.1 — busca semântica achada morta: 0 de 5327
    // vagas com embedding salvo): "text-embedding-004" foi DESATIVADO pelo
    // Google em 14/jan/2026 — toda chamada dava 404 em TODAS as 29 keys (não
    // era problema de key nenhuma, era o modelo não existir mais), e como
    // embedESalvar()/buscar() falham silenciosamente de propósito (pra não
    // travar o fetch periódico), isso nunca apareceu como erro visível em
    // lugar nenhum — só via log, que ninguém tinha motivo de vasculhar.
    // Substituto oficial: "gemini-embedding-001", 3072 dimensões por padrão;
    // pedido em 768 via outputDimensionality (suporta truncar por Matryoshka
    // Representation Learning) pra manter o vetor pequeno — sem isso o
    // TEXT salvo por vaga quadruplicaria de tamanho à toa.
    private static final String EMBEDDING_MODEL = "gemini-embedding-001";
    private static final int EMBEDDING_DIMENSOES = 768;

    public record EmbedResult(float[] vector, String errorMessage) {
        public boolean ok() {
            return vector != null;
        }
    }

    // taskType diferencia o lado "documento sendo indexado" do lado "consulta
    // buscando" — embeddings assimétricos (RETRIEVAL_DOCUMENT vs
    // RETRIEVAL_QUERY) dão resultado melhor que embeddar os dois iguais,
    // porque o modelo otimiza a geometria do vetor sabendo qual papel cada
    // texto tem na busca.
    public static final String TASK_TYPE_DOCUMENTO = "RETRIEVAL_DOCUMENT";
    public static final String TASK_TYPE_CONSULTA = "RETRIEVAL_QUERY";

    /**
     * Embedda um texto livre (título+empresa+tags de uma vaga, ou a busca
     * digitada pelo usuário) num vetor de {@value #EMBEDDING_DIMENSOES}
     * dimensões — usado pra busca semântica (ver JobEmbeddingService), que
     * acha vagas por SIGNIFICADO em vez de substring exata ("vaga de infra"
     * achando "DevOps/SRE/Cloud"). Roda o mesmo rodízio de keys que
     * generate()/chat(), só que sem marcar nada como esgotado (ver comentário
     * acima) — cada key tenta uma vez, pula pra próxima em qualquer erro.
     *
     * @param taskType {@link #TASK_TYPE_DOCUMENTO} pra texto sendo indexado
     *                 (a vaga em si), {@link #TASK_TYPE_CONSULTA} pra busca
     *                 do usuário — ver comentário do campo acima.
     */
    public EmbedResult embedContent(String text, String taskType) {
        if (!isEnabled()) {
            return new EmbedResult(null, "Recurso de IA não configurado.");
        }
        if (text == null || text.isBlank()) {
            return new EmbedResult(null, "Texto vazio.");
        }

        Map<String, Object> body = Map.of(
                "content", Map.of("parts", List.of(Map.of("text", text))),
                "embedContentConfig", Map.of(
                        "outputDimensionality", EMBEDDING_DIMENSOES,
                        "taskType", taskType != null ? taskType : TASK_TYPE_DOCUMENTO)
        );

        int size = pool.size();
        String ultimoErro = "Não foi possível gerar o embedding agora.";
        for (int i = 0; i < size; i++) {
            KeySlot slot = pool.get(Math.floorMod(cursor.getAndIncrement(), size));
            try {
                String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                        + EMBEDDING_MODEL + ":embedContent?key=" + slot.key;
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

                String response = restTemplate.postForObject(url, request, String.class);
                JsonNode values = mapper.readTree(response).at("/embedding/values");
                if (!values.isArray() || values.isEmpty()) {
                    ultimoErro = "Gemini devolveu o embedding num formato inesperado.";
                    continue;
                }
                float[] vetor = new float[values.size()];
                for (int j = 0; j < vetor.length; j++) vetor[j] = (float) values.get(j).asDouble();
                return new EmbedResult(vetor, null);
            } catch (HttpClientErrorException e) {
                log.warn("Gemini embedContent: key ...{} falhou ({}): {}", slot.tail, e.getStatusCode(), e.getMessage());
                ultimoErro = "Gemini recusou a requisição de embedding (erro " + e.getStatusCode().value() + ").";
            } catch (RestClientException e) {
                ultimoErro = "Gemini indisponível no momento (rede/timeout).";
            } catch (Exception e) {
                log.warn("Erro ao processar embedding do Gemini: {}", e.getMessage());
                ultimoErro = "Erro ao processar o embedding devolvido pelo Gemini.";
            }
        }
        return new EmbedResult(null, ultimoErro);
    }

    // ===================== Chat livre com function-calling (Jarvis) =====================

    public record FunctionDeclaration(String name, String description, Map<String, Object> parametersSchema) {}

    // thoughtSignature: modelos com "thinking" (raciocínio interno) exigem
    // propagar esse token de volta junto da function call quando ela é
    // ecoada no histórico da conversa — sem isso a API recusa a próxima
    // chamada com 400 ("missing thought_signature"). Vem preenchido na
    // resposta do Gemini, só precisa ser repassado adiante sem entender o
    // conteúdo. Ver https://ai.google.dev/gemini-api/docs/thought-signatures.
    public record FunctionCallRequest(String name, Map<String, Object> args, String thoughtSignature) {}

    /**
     * Resultado de um passo de chat: OU {@code text} (resposta final em
     * linguagem natural) OU {@code functionCall} (o modelo decidiu que
     * precisa chamar uma ferramenta antes de responder — quem chama executa
     * a função de verdade e manda o resultado de volta via
     * {@link #buildFunctionResponsePart}). Nunca os dois ao mesmo tempo.
     */
    // thinking: raciocínio real do modelo antes de chegar na resposta final
    // (só preenchido quando o passo termina em texto, não em function call —
    // nas rodadas intermediárias de ferramenta ninguém vê a tela mesmo, não
    // vale a pena acumular). Vem do Gemini só porque pedimos explicitamente
    // (generationConfig.thinkingConfig.includeThoughts=true em chat()) —
    // sem isso a API nem manda esse texto de volta, mesmo sendo um modelo
    // "thinking" por baixo dos panos (que já gera o thoughtSignature de
    // qualquer forma, ver FunctionCallRequest).
    public record ChatResult(String text, String thinking, FunctionCallRequest functionCall, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return errorMessage == null;
        }

        public boolean isFunctionCall() {
            return functionCall != null;
        }
    }

    /**
     * Um passo de conversa com ferramentas disponíveis — {@code contents} é
     * o histórico completo (mensagens do usuário, respostas do modelo,
     * resultados de function calls anteriores), {@code tools} são as
     * ferramentas que o modelo pode decidir chamar, {@code systemInstruction}
     * define comportamento persistente (identidade, quando economizar
     * chamadas de IA) sem poluir o histórico visível da conversa. Devolve
     * texto final OU um pedido de function call; nunca lança exceção.
     */
    public ChatResult chat(String systemInstruction, List<Map<String, Object>> contents, List<FunctionDeclaration> tools) {
        return chat(systemInstruction, contents, tools, false);
    }

    // includeThoughts: pede o raciocínio real de volta (generationConfig.
    // thinkingConfig) — custa alguns tokens de saída a mais, então só o
    // Hunter (chat livre, onde faz sentido mostrar "🧠 Pensando...") pede
    // isso. As outras 4 features que reaproveitam chat() pra extrair JSON
    // (carta, perguntas de entrevista, plano de ação, match-score) usam o
    // overload de 3 args e continuam sem pedir — não têm onde mostrar
    // raciocínio e só pagaria tokens à toa numa cota já apertada.
    public ChatResult chat(String systemInstruction, List<Map<String, Object>> contents, List<FunctionDeclaration> tools, boolean includeThoughts) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        body.put("contents", contents);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> declarations = tools.stream()
                    .map(t -> {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("name", t.name());
                        d.put("description", t.description());
                        d.put("parameters", t.parametersSchema());
                        return (Map<String, Object>) d;
                    })
                    .toList();
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }
        if (includeThoughts) {
            body.put("generationConfig", Map.of("thinkingConfig", Map.of("includeThoughts", true)));
        }

        PoolResult resultado = attemptWithPool(body);
        if (!resultado.ok()) {
            return new ChatResult(null, null, null, resultado.errorMessage(), resultado.rateLimited());
        }

        JsonNode parts = resultado.content().path("parts");
        for (JsonNode part : parts) {
            if (part.has("functionCall")) {
                JsonNode fc = part.get("functionCall");
                String nome = fc.path("name").asText();
                Map<String, Object> args;
                try {
                    args = mapper.convertValue(fc.path("args"), Map.class);
                } catch (Exception e) {
                    args = Map.of();
                }
                JsonNode sigNode = part.get("thoughtSignature");
                String thoughtSignature = sigNode != null ? sigNode.asText() : null;
                return new ChatResult(null, null, new FunctionCallRequest(nome, args, thoughtSignature), null, false);
            }
        }

        // Parte com "thought": true é raciocínio, não a resposta em si — junta
        // os dois tipos separadamente (pode vir mais de um parágrafo de cada).
        StringBuilder textoFinal = new StringBuilder();
        StringBuilder pensamento = new StringBuilder();
        for (JsonNode part : parts) {
            JsonNode textNode = part.path("text");
            if (textNode.isMissingNode()) continue;
            boolean isThought = part.path("thought").asBoolean(false);
            StringBuilder alvo = isThought ? pensamento : textoFinal;
            if (alvo.length() > 0) alvo.append("\n\n");
            alvo.append(textNode.asText());
        }
        if (textoFinal.length() == 0) {
            return new ChatResult(null, null, null, "O Gemini devolveu uma resposta inesperada. Tente de novo.", false);
        }
        String thinking = pensamento.length() > 0 ? pensamento.toString().trim() : null;
        return new ChatResult(textoFinal.toString().trim(), thinking, null, null, false);
    }

    /**
     * Mesma coisa que {@link #chat}, só que quando a resposta é TEXTO (não
     * function call) os pedaços vão chegando incrementalmente via
     * {@code onTextChunk} conforme o Gemini gera — usado pelo endpoint SSE
     * do Hunter pra dar a sensação de "digitando" de verdade, em vez de
     * esperar a resposta inteira pra só então mostrar tudo de uma vez.
     *
     * <p>Só a PRIMEIRA key tentada usa o modo streaming — se der qualquer
     * problema (erro de rede, formato inesperado no meio do stream), cai de
     * volta pro {@link #chat} normal (uma chamada extra, mas garante que o
     * usuário sempre recebe uma resposta em vez de travar por causa de um
     * detalhe do protocolo SSE que não bateu com o esperado). Não tenta
     * rodízio de key dentro do próprio streaming — a complexidade de
     * recomeçar um stream no meio não compensa pra esse caso.</p>
     */
    public ChatResult chatStream(String systemInstruction, List<Map<String, Object>> contents,
                                  List<FunctionDeclaration> tools, boolean includeThoughts,
                                  java.util.function.Consumer<String> onTextChunk) {
        if (!isEnabled()) {
            return chat(systemInstruction, contents, tools, includeThoughts);
        }
        int idx = Math.floorMod(cursor.getAndIncrement(), pool.size());
        KeySlot slot = pool.get(idx);
        if (slot.isExhaustedToday()) {
            return chat(systemInstruction, contents, tools, includeThoughts);
        }

        Map<String, Object> body = new LinkedHashMap<>();
        if (systemInstruction != null && !systemInstruction.isBlank()) {
            body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", systemInstruction))));
        }
        body.put("contents", contents);
        if (tools != null && !tools.isEmpty()) {
            List<Map<String, Object>> declarations = tools.stream()
                    .map(t -> {
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("name", t.name());
                        d.put("description", t.description());
                        d.put("parameters", t.parametersSchema());
                        return (Map<String, Object>) d;
                    })
                    .toList();
            body.put("tools", List.of(Map.of("functionDeclarations", declarations)));
        }
        if (includeThoughts) {
            body.put("generationConfig", Map.of("thinkingConfig", Map.of("includeThoughts", true)));
        }

        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":streamGenerateContent?alt=sse&key=" + slot.key;
            slot.requestsToday.incrementAndGet();

            StringBuilder textoFinal = new StringBuilder();
            StringBuilder pensamento = new StringBuilder();
            FunctionCallRequest[] functionCallHolder = new FunctionCallRequest[1];

            restTemplate.execute(url, org.springframework.http.HttpMethod.POST,
                    request -> {
                        request.getHeaders().setContentType(MediaType.APPLICATION_JSON);
                        mapper.writeValue(request.getBody(), body);
                    },
                    response -> {
                        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                if (!line.startsWith("data:")) continue;
                                String json = line.substring(5).trim();
                                if (json.isEmpty()) continue;
                                JsonNode chunk = mapper.readTree(json);
                                JsonNode parts = chunk.at("/candidates/0/content/parts");
                                for (JsonNode part : parts) {
                                    if (part.has("functionCall")) {
                                        JsonNode fc = part.get("functionCall");
                                        Map<String, Object> args;
                                        try {
                                            args = mapper.convertValue(fc.path("args"), Map.class);
                                        } catch (Exception e) {
                                            args = Map.of();
                                        }
                                        JsonNode sigNode = part.get("thoughtSignature");
                                        functionCallHolder[0] = new FunctionCallRequest(
                                                fc.path("name").asText(), args, sigNode != null ? sigNode.asText() : null);
                                        continue;
                                    }
                                    JsonNode textNode = part.path("text");
                                    if (textNode.isMissingNode()) continue;
                                    boolean isThought = part.path("thought").asBoolean(false);
                                    if (isThought) {
                                        pensamento.append(textNode.asText());
                                    } else {
                                        String pedaco = textNode.asText();
                                        textoFinal.append(pedaco);
                                        onTextChunk.accept(pedaco);
                                    }
                                }
                            }
                        }
                        return null;
                    });

            if (functionCallHolder[0] != null) {
                return new ChatResult(null, null, functionCallHolder[0], null, false);
            }
            if (textoFinal.length() == 0) {
                return new ChatResult(null, null, null, "O Gemini devolveu uma resposta inesperada. Tente de novo.", false);
            }
            return new ChatResult(textoFinal.toString().trim(),
                    pensamento.length() > 0 ? pensamento.toString().trim() : null, null, null, false);
        } catch (Exception e) {
            log.warn("chatStream falhou (caindo pro modo não-streaming): {}", e.getMessage());
            return chat(systemInstruction, contents, tools, includeThoughts);
        }
    }

    /** Monta a parte "model" pra representar uma function call no histórico da conversa. */
    public Map<String, Object> buildFunctionCallPart(FunctionCallRequest call) {
        // Modelos "thinking" (ex: gemini-flash-latest hoje) exigem que o
        // thoughtSignature devolvido junto da functionCall original seja
        // ecoado de volta aqui, senão a API responde 400 "missing
        // thought_signature in functionCall parts" — ver GeminiService.chat().
        Map<String, Object> part = new LinkedHashMap<>();
        part.put("functionCall", Map.of("name", call.name(), "args", call.args()));
        if (call.thoughtSignature() != null && !call.thoughtSignature().isBlank()) {
            part.put("thoughtSignature", call.thoughtSignature());
        }
        return Map.of("role", "model", "parts", List.of(part));
    }

    /**
     * Monta a parte que devolve o RESULTADO de uma function call pro modelo
     * continuar. A API costumava aceitar role "function" pra isso — hoje
     * (ago/2026) ela recusa com 400 listando os roles válidos, e "function"
     * não está mais entre eles; "user" é quem carrega o resultado de volta
     * agora. Descoberto empiricamente lendo a mensagem de erro real da API,
     * não documentação (meu conhecimento sobre isso já estava desatualizado).
     */
    public Map<String, Object> buildFunctionResponsePart(String functionName, Map<String, Object> response) {
        return Map.of("role", "user", "parts", List.of(
                Map.of("functionResponse", Map.of("name", functionName, "response", response))
        ));
    }

    public Map<String, Object> userTurn(String text) {
        return Map.of("role", "user", "parts", List.of(Map.of("text", text)));
    }

    /**
     * Mesma coisa que {@link #userTurn}, mas com um print anexado — usado
     * quando o usuário cola/anexa uma imagem no chat do Hunter. O modelo
     * ("gemini-flash-latest") aceita imagem inline no mesmo endpoint de
     * texto/function-calling, sem chamada separada: basta um "part" a mais
     * com "inlineData" (mimeType + base64 puro, sem o prefixo "data:...").
     * Só a mensagem mais recente carrega imagem (ver JarvisChatService.
     * conversar()) — reenviar prints antigos a cada rodada custaria tokens
     * à toa numa conversa longa.
     */
    public Map<String, Object> userTurnWithImage(String text, String mimeType, String base64Data) {
        List<Map<String, Object>> parts = new ArrayList<>();
        if (text != null && !text.isBlank()) {
            parts.add(Map.of("text", text));
        }
        parts.add(Map.of("inlineData", Map.of("mimeType", mimeType, "data", base64Data)));
        return Map.of("role", "user", "parts", parts);
    }

    public Map<String, Object> modelTurn(String text) {
        return Map.of("role", "model", "parts", List.of(Map.of("text", text)));
    }

    // ===================== Núcleo compartilhado: rodízio de keys =====================

    private PoolResult attemptWithPool(Map<String, Object> body) {
        if (!isEnabled()) {
            return new PoolResult(null, "Recurso de IA não configurado — defina GEMINI_API_KEY ou GEMINI_API_KEYS no .env", false);
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
            Attempt attempt = call(body, slot);
            if (attempt.ok()) {
                return new PoolResult(attempt.content(), null, false);
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
            if (attempt.keyRejected()) {
                continue; // key específica recusada (401/403) — outras do pool podem estar ok
            }
            // erro que não é de rate limit nem de key específica (rede,
            // resposta inesperada) — esse tipo tende a repetir em qualquer
            // key, não adianta insistir, devolve na hora
            return new PoolResult(null, attempt.message(), false);
        }

        if (tentativas == 0) {
            // todas as keys já estavam marcadas como esgotadas hoje antes de tentar
            return new PoolResult(null, dailyLimitMessage(size), true);
        }
        if (last != null && last.dailyLimit() && size > 1) {
            return new PoolResult(null, dailyLimitMessage(size), true);
        }
        if (last != null && last.keyRejected() && size > 1) {
            return new PoolResult(null,
                    "Nenhuma das " + size + " keys do Gemini configuradas foi aceita (erro de autenticação/permissão). "
                            + "Confira se GEMINI_API_KEYS ainda tem chaves válidas.", false);
        }
        return new PoolResult(null,
                last != null ? last.message() : "Não foi possível falar com o Gemini agora. Tente de novo em instantes.",
                last != null && last.isRateLimit());
    }

    private String dailyLimitMessage(int poolSize) {
        return poolSize > 1
                ? String.format("Todas as %d keys do Gemini atingiram o limite diário do free tier hoje. Volta a funcionar amanhã.", poolSize)
                : "Limite diário gratuito do Gemini atingido (free tier permite poucas requisições por dia). "
                        + "Volta a funcionar automaticamente amanhã — ou gere uma chave nova em aistudio.google.com/apikey.";
    }

    private Attempt call(Map<String, Object> body, KeySlot slot) {
        try {
            String url = "https://generativelanguage.googleapis.com/v1beta/models/"
                    + model + ":generateContent?key=" + slot.key;

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

            String response = restTemplate.postForObject(url, request, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode content = root.at("/candidates/0/content");
            if (content.isMissingNode()) {
                log.warn("Gemini respondeu sem candidato no formato esperado");
                return new Attempt(null, "O Gemini devolveu uma resposta inesperada. Tente de novo.", false, false);
            }
            return new Attempt(content, null, false, false);
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

        if (status.value() == 401 || status.value() == 403) {
            log.warn("Gemini: key ...{} recusada (erro {}): {}", slot.tail, status.value(), bodyText);
            return new Attempt(null,
                    "Uma das keys do Gemini foi recusada (erro " + status.value() + " — inválida ou sem permissão).",
                    false, false, true);
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

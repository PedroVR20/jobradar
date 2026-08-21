package br.com.jobradar.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Fase 9 — cliente HTTP pro serviço Python do Hunter-Embed (encoder próprio,
 * treinado do zero por destilação a partir dos vetores do Gemini já salvos
 * no banco — ver <code>hunter-llm/embed_server.py</code> e
 * <code>hunter-llm/scripts/train_embed_v2.py</code>). Ativo por padrão
 * ({@code hunter.embedding.provider} vazio ou {@code local}).
 *
 * <p><b>Onde o serviço roda:</b> fora do Docker, direto no WSL2 (GPU sem
 * fricção com o Docker Desktop no Windows — ver Seção 20.3 da spec de
 * migração). O backend, rodando em container, alcança via
 * {@code host.docker.internal} — confirmado funcionando (Docker Desktop
 * resolve isso pro host Windows, que por sua vez tem o WSL2 fazendo
 * localhost-forwarding da porta do uvicorn). Se o serviço não estiver no
 * ar, {@link #embed} volta erro claro e {@link JobEmbeddingService} já
 * trata isso como falha silenciosa (só loga) — mesmo comportamento de
 * quando o Gemini está fora do ar.</p>
 *
 * <p><b>Honestidade sobre qualidade:</b> recall@10 medido contra o Gemini
 * em vagas de validação nunca vistas no treino: ~68% (ver
 * {@code checkpoints/hunter-embed-v3/best.pt}, campo recall_at_10). Abaixo
 * da meta original de 85% da spec — motivo documentado no commit desta
 * fase: encoder de ~12M parâmetros treinado do zero em só ~6.600 textos
 * curtos (título+empresa+tags) não tem a mesma riqueza semântica que um
 * modelo de embedding gigante pré-treinado. Ainda assim é uma melhora real
 * sobre o que existia antes (busca por SUBSTRING pura, que não acha
 * sinônimo nenhum).</p>
 */
@Service
@Slf4j
@ConditionalOnProperty(name = "hunter.embedding.provider", havingValue = "local", matchIfMissing = true)
public class HunterEmbeddingProvider implements EmbeddingProvider {

    @Value("${hunter.embedding.url:http://host.docker.internal:8091}")
    private String baseUrl;

    private static final int CONNECT_TIMEOUT_MS = 5_000;
    // Inferência local pode ser mais lenta que a API do Gemini numa chamada
    // fria (primeira depois de idle) — mais folgado que o 20s do
    // GeminiService (Fase 11.4) por segurança, ainda dentro do orçamento
    // de 25s por chamada síncrona que o resto do app já respeita.
    private static final int READ_TIMEOUT_MS = 22_000;

    private final RestTemplate restTemplate = buildRestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Override
    public boolean isEnabled() {
        try {
            restTemplate.getForObject(baseUrl + "/health", String.class);
            return true;
        } catch (RestClientException e) {
            return false;
        }
    }

    @Override
    public String getProviderName() {
        return "hunter-embed-local";
    }

    @Override
    public EmbedResult embed(String text, String taskType) {
        if (text == null || text.isBlank()) {
            return new EmbedResult(null, "Texto vazio.");
        }
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            // taskType do lado Java é "RETRIEVAL_DOCUMENT"/"RETRIEVAL_QUERY"
            // (convenção do Gemini) -- o servico Python so usa isso pra log,
            // aceita qualquer string, entao repassa direto sem traduzir.
            Map<String, Object> body = Map.of("text", text, "task", taskType == null ? "document" : taskType);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            JsonNode response = restTemplate.postForObject(baseUrl + "/embed", entity, JsonNode.class);
            if (response == null || response.path("vector").isMissingNode() || response.path("vector").isNull()) {
                String erro = response != null && response.has("error") ? response.get("error").asText() : "resposta vazia";
                return new EmbedResult(null, "Hunter-Embed: " + erro);
            }

            JsonNode vecNode = response.get("vector");
            float[] vector = new float[vecNode.size()];
            for (int i = 0; i < vector.length; i++) {
                vector[i] = (float) vecNode.get(i).asDouble();
            }
            return new EmbedResult(vector, null);
        } catch (RestClientException e) {
            log.warn("Hunter-Embed: falha ao chamar {}: {}", baseUrl, e.getMessage());
            return new EmbedResult(null, "Serviço do Hunter-Embed indisponível: " + e.getMessage());
        }
    }
}

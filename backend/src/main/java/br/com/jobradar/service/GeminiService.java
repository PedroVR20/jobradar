package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;

/**
 * Cliente fino pra API do Gemini (generateContent) — usado por CoverLetterService,
 * o classificador de senioridade/stack e a detecção de duplicatas. Sem
 * {@code GEMINI_API_KEY} configurada, {@link #isEnabled()} volta false e
 * {@link #generateText} devolve null sem tentar chamar a API — os recursos de
 * IA ficam desativados de forma transparente, sem quebrar nada que já
 * funcionava sem eles.
 *
 * Free tier do Gemini tem limite de requisições por minuto/dia — por isso os
 * chamadores devem ser econômicos (ex: só classificar por IA os casos que o
 * regex não resolveu, não toda vaga nova).
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

    public boolean isEnabled() {
        return apiKey != null && !apiKey.isBlank();
    }

    // Exposto pro endpoint de status (GET /api/jobs/ai-status) — só o nome do
    // modelo, nunca a key.
    public String getModel() {
        return model;
    }

    /**
     * Manda um prompt de texto puro pro Gemini e devolve a resposta como
     * texto. Retorna null se a IA estiver desativada (sem key) ou se a
     * chamada falhar por qualquer motivo (rate limit, timeout, resposta
     * inesperada) — nunca lança exceção pro chamador, pra não derrubar o
     * fetch/fluxo principal por causa de um recurso opcional.
     */
    public String generateText(String prompt) {
        if (!isEnabled()) return null;
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
            return textNode.isMissingNode() ? null : textNode.asText().trim();
        } catch (RestClientException e) {
            log.warn("Gemini indisponível ou limite atingido: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Erro ao processar resposta do Gemini: {}", e.getMessage());
            return null;
        }
    }
}

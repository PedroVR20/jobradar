package br.com.jobradar.llm;

import br.com.jobradar.service.GeminiService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Fase 8 — casca fina sobre o {@link GeminiService#embedContent} que já
 * existia, só reempacotada atrás de {@link EmbeddingProvider}. Zero mudança
 * de comportamento quando ativa. Fica desativada por padrão desde que o
 * Hunter-Embed (ver {@link HunterEmbeddingProvider}) passou a recall@10 de
 * ~68% contra o Gemini nos testes — reativa com
 * {@code hunter.embedding.provider=gemini} se algo no Hunter local quebrar.
 */
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "hunter.embedding.provider", havingValue = "gemini")
public class GeminiEmbeddingProvider implements EmbeddingProvider {

    private final GeminiService geminiService;

    @Value("${gemini.model}")
    private String model;

    @Override
    public boolean isEnabled() {
        return geminiService.isEnabled();
    }

    @Override
    public String getProviderName() {
        return "gemini:" + model;
    }

    @Override
    public EmbedResult embed(String text, String taskType) {
        GeminiService.EmbedResult r = geminiService.embedContent(text, taskType);
        return new EmbedResult(r.vector(), r.errorMessage());
    }
}

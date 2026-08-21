package br.com.jobradar.llm;

/**
 * Fase 8 — costura entre {@link br.com.jobradar.service.JobEmbeddingService}
 * e QUEM realmente calcula o vetor. Duas implementações hoje:
 * {@link GeminiEmbeddingProvider} (o {@code embedContent} do Gemini que já
 * existia) e {@link HunterEmbeddingProvider} (o Hunter-Embed local, treinado
 * por destilação a partir dos ~6.600 vetores do Gemini já salvos no banco —
 * ver <code>hunter-llm/scripts/train_embed_v2.py</code>). Qual delas fica
 * ativa é controlado por {@code hunter.embedding.provider} em
 * application.yml — trocar isso é a forma de reverter pro Gemini se o
 * Hunter local tiver algum problema, sem precisar mexer em código.
 *
 * <p>Vetor de tamanho variável de propósito: o Gemini devolve 3072
 * dimensões, o Hunter-Embed devolve 512 — {@link JobEmbeddingService} já
 * era agnóstico a isso antes desta fase (serializa/parseia por tamanho do
 * array, não por uma constante fixa) e {@code buscar()} já ignora par de
 * vetores com dimensão diferente (protege contra o meio-termo de uma troca
 * de provider antes do reindex completo).</p>
 */
public interface EmbeddingProvider {

    // Mesmos valores que GeminiService já usava (API do Gemini) — movidos
    // pra cá pra JobEmbeddingService não precisar mais importar GeminiService
    // só por causa dessas duas constantes (Fase 8: desacoplar de verdade).
    String TASK_TYPE_DOCUMENTO = "RETRIEVAL_DOCUMENT";
    String TASK_TYPE_CONSULTA = "RETRIEVAL_QUERY";

    boolean isEnabled();

    String getProviderName();

    record EmbedResult(float[] vector, String errorMessage) {
        public boolean ok() {
            return vector != null;
        }
    }

    // taskType segue a mesma convenção que GeminiService já define
    // (TASK_TYPE_DOCUMENTO / TASK_TYPE_CONSULTA) — o Hunter-Embed hoje é
    // simétrico (ignora a distinção, mesmo encoder pros dois casos), mas o
    // parâmetro fica pra não fechar a porta de um encoder assimétrico futuro.
    EmbedResult embed(String text, String taskType);
}

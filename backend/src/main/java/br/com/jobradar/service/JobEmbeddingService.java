package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.model.JobEmbedding;
import br.com.jobradar.repository.JobEmbeddingRepository;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Busca semântica sobre as vagas — resolve um problema real e verificado no
 * código: a busca de {@code listarVagas}/{@code contemBusca} é
 * {@code haystack.contains(termo)}, substring puro. "vaga de infra" nunca
 * acha "DevOps/SRE/Cloud", "front" nunca acha "React". Embeddings resolvem
 * isso comparando SIGNIFICADO, não caractere por caractere.
 *
 * <p>Usa o modelo {@code gemini-embedding-001} (ver {@link GeminiService#embedContent}),
 * que tem quota SEPARADA e bem mais folgada que o {@code generateContent}
 * usado pelo chat — por isso essa ferramenta não compete pela cota que já
 * vive esgotada no free tier.</p>
 *
 * <p>Sem extensão pgvector no Postgres: os vetores ficam serializados como
 * bytea (Fase 6.5 — floats empacotados em binário, ver
 * {@link #serializar}/{@link #parsear}) numa tabela própria ({@link JobEmbedding},
 * ver Fase 6.1 pra por que saiu de dentro de {@link Job}) e a similaridade
 * de cosseno é calculada em Java sobre a lista de vagas candidatas — ~5900
 * vagas × 3072 dimensões é um produto escalar trivial pra JVM, não precisa
 * de banco vetorial de verdade nessa escala.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobEmbeddingService {

    private final GeminiService geminiService;
    private final JobRepository jobRepository;
    private final JobEmbeddingRepository jobEmbeddingRepository;

    // Resultado de uma busca semântica: a vaga e o quão perto ela ficou da
    // consulta (0 a 1, 1 = idêntico em significado).
    public record Match(Job job, double similaridade) {}

    // Texto que representa a vaga pro embedding — título+empresa+tags é o
    // que já usamos em outras heurísticas (ver scoreHeuristico em
    // JarvisAssistantService), mantém consistência do que "significa" uma vaga.
    private String textoDaVaga(Job job) {
        String tags = job.getTags() == null ? "" : job.getTags().replace(",", " ");
        return String.join(" ", job.getTitle(), job.getCompany(), tags).trim();
    }

    /**
     * Embedda e salva o vetor de UMA vaga — chamado no fetch pra vaga nova
     * (ver JobAggregatorService) e pelo backfill (endpoint admin). Falha
     * silenciosa de propósito (só loga): não pode travar o fetch periódico
     * inteiro porque o Gemini está fora do ar ou sem key configurada.
     *
     * <p>Fase 6.1 — salva direto em {@code job_embeddings} (não mexe mais no
     * Job em si), então precisa que {@code job.getId()} já exista — a vaga
     * tem que ter sido persistida antes de chamar isso.</p>
     */
    public boolean embedESalvar(Job job) {
        GeminiService.EmbedResult resultado = geminiService.embedContent(
                textoDaVaga(job), GeminiService.TASK_TYPE_DOCUMENTO);
        if (!resultado.ok()) {
            log.warn("Não foi possível embeddar a vaga {} ('{}'): {}", job.getId(), job.getTitle(), resultado.errorMessage());
            return false;
        }
        jobEmbeddingRepository.save(JobEmbedding.builder()
                .id(job.getId())
                .vector(serializar(resultado.vector()))
                .build());
        return true;
    }

    /**
     * Busca semântica de verdade — embedda a consulta do usuário e ordena as
     * vagas candidatas (já filtradas por quem chamou, ex: só não recusadas)
     * pela similaridade de cosseno com o embedding de cada uma. Vaga sem
     * embedding ainda (não passou pelo fetch desde que essa feature existe,
     * ou o backfill não chegou nela) simplesmente não entra no resultado —
     * não trava a busca, só fica de fora até ser embeddada.
     *
     * <p>Fase 6.1 — busca os vetores em lote só das candidatas (não carrega
     * a tabela job_embeddings inteira), via {@code findAllById}.</p>
     */
    public List<Match> buscar(String consulta, List<Job> candidatas, int limite) {
        GeminiService.EmbedResult consultaEmbed = geminiService.embedContent(consulta, GeminiService.TASK_TYPE_CONSULTA);
        if (!consultaEmbed.ok()) {
            log.warn("Busca semântica: não consegui embeddar a consulta '{}': {}", consulta, consultaEmbed.errorMessage());
            return List.of();
        }
        float[] vetorConsulta = consultaEmbed.vector();

        List<Long> ids = candidatas.stream().map(Job::getId).toList();
        Map<Long, byte[]> vetoresPorJobId = jobEmbeddingRepository.findAllById(ids).stream()
                .collect(java.util.stream.Collectors.toMap(JobEmbedding::getId, JobEmbedding::getVector));

        List<Match> resultados = new ArrayList<>();
        for (Job job : candidatas) {
            byte[] vetorSerializado = vetoresPorJobId.get(job.getId());
            if (vetorSerializado == null || vetorSerializado.length == 0) continue;
            float[] vetorVaga = parsear(vetorSerializado);
            if (vetorVaga.length != vetorConsulta.length) continue; // modelo trocado no meio do caminho, ignora
            resultados.add(new Match(job, similaridadeCosseno(vetorConsulta, vetorVaga)));
        }
        resultados.sort(Comparator.comparingDouble(Match::similaridade).reversed());
        return resultados.stream().limit(Math.max(1, limite)).toList();
    }

    // Quantas vagas ainda não têm embedding — usado pra reportar progresso
    // no endpoint de backfill. Fase 6.1: query de verdade (NOT IN contra
    // job_embeddings), não mais filtro em memória sobre findAll().
    public long contarSemEmbedding() {
        return jobRepository.countNotEmbedded();
    }

    // Só as sem embedding ainda — usado pelo backfill, pra não reprocessar
    // (e regastar cota em) vaga que já foi embeddada antes.
    public List<Job> semEmbedding() {
        return jobRepository.findAllNotEmbedded();
    }

    // Fase 6.5 — 4 bytes por float (little-endian), em vez do texto decimal
    // de antes (~13 caracteres por float em média, incluindo notação
    // científica). ByteBuffer em vez de escrever byte a byte na mão: mais
    // curto e sem chance de errar a ordem dos bytes.
    static byte[] serializar(float[] vetor) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(vetor.length * Float.BYTES)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        for (float v : vetor) buffer.putFloat(v);
        return buffer.array();
    }

    static float[] parsear(byte[] serializado) {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(serializado)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        float[] vetor = new float[serializado.length / Float.BYTES];
        for (int i = 0; i < vetor.length; i++) vetor[i] = buffer.getFloat();
        return vetor;
    }

    // gemini-embedding-001 já devolve vetores normalizados (norma 1), mas
    // calcular completo em vez de assumir isso evita ficar refém de um
    // detalhe de implementação do modelo que pode mudar.
    static double similaridadeCosseno(float[] a, float[] b) {
        double dot = 0, normA = 0, normB = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            normA += a[i] * a[i];
            normB += b[i] * b[i];
        }
        if (normA == 0 || normB == 0) return 0;
        return dot / (Math.sqrt(normA) * Math.sqrt(normB));
    }
}

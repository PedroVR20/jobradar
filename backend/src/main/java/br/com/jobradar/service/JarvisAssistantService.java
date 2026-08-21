package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.repository.JobSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Motor por trás do painel "🤖 Jarvis" — assistente guiado (não é chat livre
 * com function-calling, o usuário escolheu ações pré-definidas em vez disso).
 * A ação principal, "compatibilidade com vagas recentes", precisa lidar com
 * o fato de que "vagas de hoje" pode ser dezenas — rodar o Gemini pra cada
 * uma estouraria a cota rapidinho, mesmo com o pool de keys. Por isso faz um
 * pré-filtro barato (sem IA, por sobreposição de tags + senioridade) pra
 * achar as mais promissoras, e só gasta chamada de IA de verdade
 * ({@link MatchScoreService}, já existente) nas top {@value #MAX_AI_CALLS}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JarvisAssistantService {

    private final JobRepository jobRepository;
    private final SalaryPredictionService salaryPredictionService;
    private final MatchScoreService matchScoreService;
    private final SeniorityClassifier seniorityClassifier;

    private static final int MAX_AI_CALLS = 5;

    public record CompatibilityHit(Job job, int score, List<String> pontosFortes, List<String> pontosFaltando, String resumo) {}

    public record CompatibilityResult(
            boolean available,
            int totalConsiderados,
            int totalAnalisadosPorIa,
            List<CompatibilityHit> hits,
            String errorMessage
    ) {}

    public CompatibilityResult scanCompatibilidade(String candidateProfile, int dias, String feedbackContext) {
        if (candidateProfile == null || candidateProfile.isBlank()) {
            return new CompatibilityResult(false, 0, 0, List.of(),
                    "Salve seu perfil/currículo em ⚙️ Configurações primeiro, ou cole ele aqui na conversa.");
        }

        Set<String> perfilTags = salaryPredictionService.extractTagsFromText(candidateProfile);
        String perfilSenioridade = seniorityClassifier.classify(candidateProfile, String.join(",", perfilTags));

        LocalDateTime cutoff = LocalDateTime.now().minusDays(Math.max(1, dias));
        // Fase 1.6 — os dois filtros (não recusada + postada depois do
        // corte) empurrados pro WHERE do SQL em vez de Java: pra "vagas de
        // hoje" isso corta a imensa maioria do catálogo ANTES de carregar
        // qualquer linha na memória, não só depois.
        List<Job> candidatos = jobRepository.findAll(
                JobSpecifications.combine(JobSpecifications.notRejected(), JobSpecifications.postedAfter(cutoff)));

        if (candidatos.isEmpty()) {
            return new CompatibilityResult(true, 0, 0, List.of(), null);
        }

        // Ranking barato (sem IA): sobreposição de tags técnicas + bônus se a
        // senioridade inferida do currículo bater com a da vaga. Só decide
        // QUAIS vagas merecem a chamada de IA de verdade, não é o veredito final.
        List<Job> ranqueados = candidatos.stream()
                .sorted(Comparator.comparingDouble((Job j) -> -scoreHeuristico(j, perfilTags, perfilSenioridade)))
                .toList();
        List<Job> topN = ranqueados.stream()
                .filter(j -> scoreHeuristico(j, perfilTags, perfilSenioridade) > 0)
                .limit(MAX_AI_CALLS)
                .toList();

        if (topN.isEmpty()) {
            // nenhuma vaga teve nem 1 tag em comum com o perfil — não vale gastar
            // chamada de IA em vagas que o filtro barato já indica que não batem
            return new CompatibilityResult(true, candidatos.size(), 0, List.of(), null);
        }

        List<CompatibilityHit> hits = new ArrayList<>();
        String ultimoErro = null;
        for (Job job : topN) {
            MatchScoreService.MatchOutcome resultado = matchScoreService.calcular(job, candidateProfile, feedbackContext);
            if (resultado.ok()) {
                MatchScoreService.MatchResult r = resultado.result();
                hits.add(new CompatibilityHit(job, r.score(), r.pontosFortes(), r.pontosFaltando(), r.resumo()));
            } else {
                ultimoErro = resultado.errorMessage();
                log.warn("Jarvis: falha ao calcular compatibilidade pra vaga {}: {}", job.getId(), ultimoErro);
            }
        }
        hits.sort((a, b) -> b.score() - a.score());

        return new CompatibilityResult(true, candidatos.size(), topN.size(), hits, hits.isEmpty() ? ultimoErro : null);
    }

    /**
     * Versão em % (0-100) do mesmo score heurístico usado no pré-filtro de
     * compatibilidade — pra modo triagem rápida e o badge "provável match"
     * nos cards, onde não faz sentido rodar IA (seria uma chamada por vaga
     * só pra ordenar/badge, não pra decisão final de verdade). 100% = todas
     * as tags técnicas do perfil aparecem na vaga; 0% = nenhuma em comum.
     * Sem perfil ou sem tags reconhecidas no perfil, devolve mapa vazio (não
     * dá pra pontuar contra nada).
     */
    public Map<Long, Integer> heuristicMatchPercents(List<Job> jobs, String candidateProfile) {
        if (candidateProfile == null || candidateProfile.isBlank()) return Map.of();
        Set<String> perfilTags = salaryPredictionService.extractTagsFromText(candidateProfile);
        if (perfilTags.isEmpty()) return Map.of();
        String perfilSenioridade = seniorityClassifier.classify(candidateProfile, String.join(",", perfilTags));

        Map<Long, Integer> out = new HashMap<>();
        for (Job job : jobs) {
            Set<String> jobTags = tagSet(job.getTags());
            long overlap = jobTags.stream().filter(perfilTags::contains).count();
            int percent = (int) Math.round(100.0 * overlap / perfilTags.size());
            if (perfilSenioridade != null && perfilSenioridade.equals(job.getSeniority())) {
                percent = Math.min(100, percent + 10);
            }
            out.put(job.getId(), percent);
        }
        return out;
    }

    public record MatchExplanation(int percent, List<String> tagsQueBateram, List<String> tagsQueFaltaram, boolean senioridadeBateu) {}

    // Fase 9.3 — "de onde veio a ordenação"/"por que essa vaga apareceu":
    // o badge 🎯 já existia (heuristicMatchPercents acima), mas só mostrava
    // o percentual — nunca QUAIS tags do perfil bateram ou faltaram. Mesmo
    // cálculo de heuristicMatchPercents, pra UMA vaga só, guardando o
    // detalhe em vez de só o número final.
    public Optional<MatchExplanation> explicarMatch(Job job, String candidateProfile) {
        if (candidateProfile == null || candidateProfile.isBlank()) return Optional.empty();
        Set<String> perfilTags = salaryPredictionService.extractTagsFromText(candidateProfile);
        if (perfilTags.isEmpty()) return Optional.empty();
        String perfilSenioridade = seniorityClassifier.classify(candidateProfile, String.join(",", perfilTags));

        Set<String> jobTags = tagSet(job.getTags());
        List<String> bateram = perfilTags.stream().filter(jobTags::contains).sorted().toList();
        List<String> faltaram = perfilTags.stream().filter(t -> !jobTags.contains(t)).sorted().toList();
        boolean senioridadeBateu = perfilSenioridade != null && perfilSenioridade.equals(job.getSeniority());

        int percent = perfilTags.isEmpty() ? 0 : (int) Math.round(100.0 * bateram.size() / perfilTags.size());
        if (senioridadeBateu) percent = Math.min(100, percent + 10);

        return Optional.of(new MatchExplanation(percent, bateram, faltaram, senioridadeBateu));
    }

    private double scoreHeuristico(Job job, Set<String> perfilTags, String perfilSenioridade) {
        Set<String> jobTags = tagSet(job.getTags());
        long overlap = jobTags.stream().filter(perfilTags::contains).count();
        double score = overlap;
        if (perfilSenioridade != null && perfilSenioridade.equals(job.getSeniority())) {
            score += 0.5;
        }
        return score;
    }

    private Set<String> tagSet(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : tags.toLowerCase().split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isBlank()) out.add(trimmed);
        }
        return out;
    }
}

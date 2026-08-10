package br.com.jobradar.controller;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.service.AiDuplicateVerifierService;
import br.com.jobradar.service.CoverLetterService;
import br.com.jobradar.service.GeminiService;
import br.com.jobradar.service.InterviewQuestionsService;
import br.com.jobradar.service.JarvisAssistantService;
import br.com.jobradar.service.JarvisChatService;
import br.com.jobradar.service.JobAggregatorService;
import br.com.jobradar.service.LearningPlanService;
import br.com.jobradar.service.MatchScoreService;
import br.com.jobradar.service.SalaryEstimateService;
import br.com.jobradar.service.SalaryModelTrainerService;
import br.com.jobradar.service.SalaryPredictionService;
import br.com.jobradar.service.SeniorityClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class JobController {

    private final JobRepository jobRepository;
    private final JobAggregatorService aggregatorService;
    private final SeniorityClassifier seniorityClassifier;
    private final AiDuplicateVerifierService aiDuplicateVerifierService;
    private final CoverLetterService coverLetterService;
    private final GeminiService geminiService;
    private final SalaryEstimateService salaryEstimateService;
    private final SalaryPredictionService salaryPredictionService;
    private final SalaryModelTrainerService salaryModelTrainerService;
    private final JarvisAssistantService jarvisAssistantService;
    private final JarvisChatService jarvisChatService;
    private final MatchScoreService matchScoreService;
    private final LearningPlanService learningPlanService;
    private final InterviewQuestionsService interviewQuestionsService;

    // Gate simples (não é segurança de verdade — app pessoal local) pra não
    // ter um botão de "retreinar" clicável sem querer. Vazio == recurso
    // desativado (retorna 503 em vez de aceitar qualquer código).
    @Value("${retrain.secret-code:}")
    private String retrainSecretCode;
    private final AtomicBoolean retreinoEmAndamento = new AtomicBoolean(false);

    /**
     * Lista todas as vagas com filtros opcionais
     * GET /api/jobs?source=REMOTIVE&search=java&seniority=JUNIOR&days=7&sort=posted_desc
     *
     * search    → multi-termo: "java senior" exige que TODOS os termos apareçam
     * seniority → ESTAGIO | JUNIOR | PLENO | SENIOR | NAO_INFORMADO
     * workplaceType → REMOTO | HIBRIDO | PRESENCIAL (só vagas brasileiras/Gupy informam)
     * state     → nome do estado por extenso, ignora acentos (ex: "sao paulo" acha "São Paulo")
     * days      → só vagas publicadas nos últimos N dias
     * sort      → posted_desc (padrão) | posted_asc | fetched_desc
     * onlyNew   → só não vistas
     * onlySeen  → só vistas, sem interesse marcado, e não aplicadas
     * onlyInteressado → só marcadas com interesse, e não aplicadas
     * onlyApplied → só aplicadas e fora de processo (não confundir com em andamento)
     * onlyInProgress → só aplicadas e em processo seletivo ativo
     * onlyRejected → só recusadas/congeladas (somem sozinhas depois de 7 dias)
     */
    @GetMapping
    public List<Map<String, Object>> getAll(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String seniority,
            @RequestParam(required = false) String workplaceType,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false, defaultValue = "posted_desc") String sort,
            @RequestParam(required = false, defaultValue = "false") boolean onlyNew,
            @RequestParam(required = false, defaultValue = "false") boolean onlySeen,
            @RequestParam(required = false, defaultValue = "false") boolean onlyInteressado,
            @RequestParam(required = false, defaultValue = "false") boolean onlyApplied,
            @RequestParam(required = false, defaultValue = "false") boolean onlyInProgress,
            @RequestParam(required = false, defaultValue = "false") boolean onlyRejected
    ) {
        List<Job> jobs = jobRepository.findAll();
        LocalDateTime postedAfter = days != null && days > 0
                ? LocalDateTime.now().minusDays(days)
                : null;

        // vagas pinadas sempre sobem ao topo, independente da aba ou filtro
        Comparator<Job> pinnedFirst = Comparator.comparing(
                (Job j) -> !Boolean.TRUE.equals(j.getFavorited()));

        return jobs.stream()
                .filter(j -> source == null || j.getSource().equalsIgnoreCase(source))
                .filter(j -> seniority == null || seniority.isBlank()
                        || Arrays.stream(seniority.split(","))
                            .anyMatch(s -> s.trim().equalsIgnoreCase(j.getSeniority())))
                .filter(j -> workplaceType == null || workplaceType.isBlank()
                        || workplaceType.equalsIgnoreCase(j.getWorkplaceType()))
                .filter(j -> state == null || state.isBlank()
                        || (j.getState() != null && normalize(state).equals(normalize(j.getState()))))
                .filter(j -> !onlyNew || (!j.isSeen() && !j.isRejected()))
                .filter(j -> !onlySeen || (j.isSeen() && !j.isInterested() && !j.isApplied() && !j.isRejected()))
                .filter(j -> !onlyInteressado || (j.isInterested() && !j.isApplied() && !j.isRejected()))
                .filter(j -> !onlyApplied || (j.isApplied() && !j.isInProgress() && !j.isRejected()))
                .filter(j -> !onlyInProgress || (j.isApplied() && j.isInProgress() && !j.isRejected()))
                .filter(j -> !onlyRejected || j.isRejected())
                .filter(j -> postedAfter == null || (j.getPostedAt() != null
                        && j.getPostedAt().isAfter(postedAfter)))
                .filter(j -> matchesSearch(j, search))
                .sorted(pinnedFirst.thenComparing(comparatorFor(sort)))
                .map(this::toDto)
                .toList();
    }

    /**
     * Lista os estados brasileiros presentes no banco (só vagas com state
     * preenchido, hoje só a fonte Gupy). Usado pra popular o filtro de estado.
     * GET /api/jobs/states
     */
    @GetMapping("/states")
    public List<String> getStates() {
        return jobRepository.findDistinctStates();
    }

    /**
     * Lista todas as fontes presentes no banco, incluindo fontes
     * personalizadas digitadas na adição manual de vaga (ex: "INFOJOBS").
     * Usado pra popular o filtro de fonte dinamicamente, sem precisar de
     * uma lista fixa no frontend.
     * GET /api/jobs/sources
     */
    @GetMapping("/sources")
    public List<String> getSources() {
        return jobRepository.findDistinctSources();
    }

    /**
     * Conta quantas vagas foram buscadas (fetchedAt) desde X minutos atrás.
     * Usado pelo frontend pra avisar "N vagas novas desde sua última visita"
     * ao abrir o app. Recebe minutos (não um timestamp absoluto do cliente)
     * de propósito — o cálculo de "agora - minutos" roda inteiramente no
     * servidor, então não tem risco de descompasso de fuso horário entre
     * o relógio do navegador e o do backend (já tivemos um bug assim com
     * a Eureca — ver EurecaService.parseDate).
     * GET /api/jobs/new-since?minutesAgo=180
     */
    @GetMapping("/new-since")
    public Map<String, Object> getNewSince(@RequestParam long minutesAgo) {
        long clamped = Math.max(0, Math.min(minutesAgo, 30 * 24 * 60)); // no máximo 30 dias
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(clamped);
        return Map.of("count", jobRepository.countByFetchedAtAfter(cutoff));
    }

    // Todos os termos da busca devem aparecer em título, empresa ou tags.
    // Ignora acentuação para achar "itau" em "Itaú", "sao paulo" em "São Paulo", etc.
    private boolean matchesSearch(Job j, String search) {
        if (search == null || search.isBlank()) return true;
        String haystack = normalize(j.getTitle() + " " + j.getCompany() + " "
                + (j.getTags() != null ? j.getTags() : ""));
        return Arrays.stream(normalize(search).trim().split("\\s+"))
                .allMatch(haystack::contains);
    }

    private String normalize(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    private Comparator<Job> comparatorFor(String sort) {
        return switch (sort == null ? "" : sort) {
            case "posted_asc" -> Comparator.comparing(Job::getPostedAt,
                    Comparator.nullsLast(Comparator.naturalOrder()));
            case "fetched_desc" -> Comparator.comparing(Job::getFetchedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
            default -> Comparator.comparing(Job::getPostedAt,
                    Comparator.nullsLast(Comparator.reverseOrder()));
        };
    }

    /**
     * Estatísticas para o painel superior do dashboard
     * GET /api/jobs/stats
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", jobRepository.count());
        stats.put("novas", jobRepository.countBySeenFalse());
        stats.put("interessadas", jobRepository.countByInterestedTrue());
        stats.put("aplicadas", jobRepository.countByAppliedTrue());
        stats.put("emAndamento", jobRepository.countByAppliedTrueAndInProgressTrue());
        stats.put("recusadas", jobRepository.countByRejectedTrue());
        // Diferente de "recusadas" acima (TODA vaga recusada, aplicada ou
        // não) — esse é só a recusa de quem realmente tinha sido aplicada.
        // "aplicadas" - "recusadas" (o campo de cima) dava número negativo
        // quando a maioria das recusas nunca foi aplicada de verdade — usa
        // este campo pra qualquer conta que precise só da recusa "dentro"
        // do funil de aplicadas (bug reportado: "-21 aplicadas" na tela).
        stats.put("recusadasDeAplicadas", jobRepository.countByAppliedTrueAndRejectedTrue());
        stats.put("hojeCount", jobRepository
                .findByFetchedAtAfter(LocalDateTime.now().minusHours(24)).size());
        stats.put("porFonte", Map.of(
                "REMOTIVE", jobRepository.countBySource("REMOTIVE"),
                "ARBEITNOW", jobRepository.countBySource("ARBEITNOW"),
                "WWR", jobRepository.countBySource("WWR"),
                "GUPY", jobRepository.countBySource("GUPY"),
                "EURECA", jobRepository.countBySource("EURECA"),
                "QUEROVAGASTECH", jobRepository.countBySource("QUEROVAGASTECH"),
                "NERDIN", jobRepository.countBySource("NERDIN")
        ));
        stats.put("porSenioridade", Map.of(
                SeniorityClassifier.ESTAGIO, jobRepository.countBySeniority(SeniorityClassifier.ESTAGIO),
                SeniorityClassifier.JUNIOR, jobRepository.countBySeniority(SeniorityClassifier.JUNIOR),
                SeniorityClassifier.PLENO, jobRepository.countBySeniority(SeniorityClassifier.PLENO),
                SeniorityClassifier.SENIOR, jobRepository.countBySeniority(SeniorityClassifier.SENIOR),
                SeniorityClassifier.NAO_INFORMADO, jobRepository.countBySeniority(SeniorityClassifier.NAO_INFORMADO)
        ));
        return stats;
    }

    /**
     * Métricas de funil de candidatura pro dashboard de métricas.
     * Só considera vagas que já foram aplicadas em algum momento
     * (aguardando/em andamento/recusadas), independente do estado atual.
     * GET /api/jobs/metrics
     */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        List<Job> aplicadas = jobRepository.findByAppliedTrue();

        long total = aplicadas.size();
        long emAndamento = aplicadas.stream().filter(Job::isInProgress).count();
        long recusadas = aplicadas.stream().filter(Job::isRejected).count();
        long aguardandoRetorno = Math.max(0, total - emAndamento - recusadas);
        Double taxaResposta = total == 0
                ? null
                : Math.round((emAndamento + recusadas) * 1000.0 / total) / 10.0;

        // aplicações por semana (últimas 8 semanas, segunda-feira como início de cada uma)
        LocalDate inicioSemanaAtual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> porSemana = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        for (int i = 7; i >= 0; i--) {
            LocalDate segunda = inicioSemanaAtual.minusWeeks(i);
            LocalDate proximaSegunda = segunda.plusDays(7);
            long count = aplicadas.stream()
                    .filter(j -> j.getAppliedAt() != null)
                    .map(j -> j.getAppliedAt().toLocalDate())
                    .filter(d -> !d.isBefore(segunda) && d.isBefore(proximaSegunda))
                    .count();
            porSemana.add(Map.of("semana", segunda.format(fmt), "count", count));
        }

        // tempo médio até avançar pra "em andamento" / até ser recusada — só
        // conta vagas com os dois timestamps disponíveis (appliedAt é campo novo,
        // então vagas antigas ficam de fora até serem reaplicadas)
        OptionalDouble avgAndamento = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getInProgressAt() != null)
                .mapToLong(j -> Duration.between(j.getAppliedAt(), j.getInProgressAt()).toDays())
                .average();
        OptionalDouble avgRecusa = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getRejectedAt() != null)
                .mapToLong(j -> Duration.between(j.getAppliedAt(), j.getRejectedAt()).toDays())
                .average();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalAplicadas", total);
        metrics.put("emAndamento", emAndamento);
        metrics.put("recusadas", recusadas);
        metrics.put("aguardandoRetorno", aguardandoRetorno);
        metrics.put("taxaResposta", taxaResposta);
        metrics.put("aplicacoesPorSemana", porSemana);
        metrics.put("tempoMedioAteAndamentoDias", avgAndamento.isPresent() ? Math.round(avgAndamento.getAsDouble() * 10) / 10.0 : null);
        metrics.put("tempoMedioAteRecusaDias", avgRecusa.isPresent() ? Math.round(avgRecusa.getAsDouble() * 10) / 10.0 : null);
        return metrics;
    }

    /**
     * Detecta possíveis vagas duplicadas publicadas em fontes diferentes —
     * mesma empresa (normalizada) + título com alta similaridade de palavras.
     * Só sinaliza pra revisão manual: não deleta nem mescla nada sozinho.
     * GET /api/jobs/duplicates
     */
    // Limite de grupos verificados por IA por chamada — o Jaccard já reduz
    // milhares de vagas a um punhado de grupos candidatos, mas ainda assim
    // pode passar disso, e cada verificação é uma chamada ao Gemini (free
    // tier tem limite de requisições por minuto).
    private static final int MAX_VERIFICACOES_IA = 20;

    @GetMapping("/duplicates")
    public List<Map<String, Object>> getDuplicates() {
        List<Job> ativos = jobRepository.findAll().stream()
                .filter(j -> !j.isRejected())
                .toList();

        Map<String, List<Job>> porEmpresa = new HashMap<>();
        for (Job j : ativos) {
            String key = normalizeCompany(j.getCompany());
            if (key.isBlank()) continue;
            porEmpresa.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }

        List<Map<String, Object>> grupos = new ArrayList<>();
        int verificacoesIa = 0;
        for (List<Job> candidatos : porEmpresa.values()) {
            if (candidatos.size() < 2) continue;

            // agrupa por similaridade de título via BFS (componentes conectados)
            boolean[] visitado = new boolean[candidatos.size()];
            for (int i = 0; i < candidatos.size(); i++) {
                if (visitado[i]) continue;
                List<Job> componente = new ArrayList<>();
                Deque<Integer> fila = new ArrayDeque<>();
                fila.add(i);
                visitado[i] = true;
                while (!fila.isEmpty()) {
                    int atual = fila.poll();
                    Job jobAtual = candidatos.get(atual);
                    componente.add(jobAtual);
                    Set<String> palavrasAtual = titleWords(jobAtual.getTitle());
                    for (int j = 0; j < candidatos.size(); j++) {
                        if (visitado[j] || j == atual) continue;
                        Job jobCandidato = candidatos.get(j);
                        // senioridade precisa bater — "Dev Pleno" e "Dev Sênior" da mesma empresa
                        // são vagas diferentes, não duplicata, mesmo com título quase idêntico
                        boolean mesmaSenioridade = java.util.Objects.equals(jobAtual.getSeniority(), jobCandidato.getSeniority());
                        if (mesmaSenioridade && jaccard(palavrasAtual, titleWords(jobCandidato.getTitle())) >= 0.6) {
                            visitado[j] = true;
                            fila.add(j);
                        }
                    }
                }
                if (componente.size() < 2) continue;

                // segunda opinião via IA — descarta grupos que o Jaccard achou parecidos
                // por palavra mas que são vagas de times/produtos genuinamente diferentes.
                // Sem IA disponível, ou depois do limite de verificações por chamada,
                // mantém o comportamento anterior (só o veredito do Jaccard).
                boolean aiVerificado = false;
                if (verificacoesIa < MAX_VERIFICACOES_IA) {
                    List<String> titulos = componente.stream().map(Job::getTitle).toList();
                    boolean confirmado = aiDuplicateVerifierService.confirmar(componente.get(0).getCompany(), titulos);
                    verificacoesIa++;
                    if (!confirmado) continue; // IA disse que são vagas diferentes
                    aiVerificado = true;
                }

                List<Map<String, Object>> vagas = componente.stream()
                        .map(j -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", j.getId());
                            m.put("title", j.getTitle());
                            m.put("source", j.getSource());
                            m.put("url", j.getUrl());
                            m.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
                            return m;
                        })
                        .toList();
                Map<String, Object> grupo = new HashMap<>();
                grupo.put("company", componente.get(0).getCompany());
                grupo.put("jobs", vagas);
                grupo.put("aiVerificado", aiVerificado);
                grupos.add(grupo);
            }
        }

        return grupos;
    }

    private static final Set<String> COMPANY_SUFFIXES = Set.of(
            "sa", "s a", "ltda", "me", "eireli", "inc", "llc", "corp", "corporation", "co"
    );

    private String normalizeCompany(String company) {
        if (company == null) return "";
        String norm = normalize(company).replaceAll("[^a-z0-9 ]", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : norm.split("\\s+")) {
            if (COMPANY_SUFFIXES.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString().trim();
    }

    private static final Set<String> TITLE_STOPWORDS = Set.of(
            "de", "da", "do", "das", "dos", "e", "para", "com", "em", "a", "o", "i", "ii", "iii"
    );

    private Set<String> titleWords(String title) {
        if (title == null) return Set.of();
        String norm = normalize(title).replaceAll("[^a-z0-9 ]", " ").trim();
        Set<String> words = new HashSet<>();
        for (String w : norm.split("\\s+")) {
            if (w.length() < 2 || TITLE_STOPWORDS.contains(w)) continue;
            words.add(w);
        }
        return words;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    /**
     * Marca uma vaga como vista
     * PATCH /api/jobs/{id}/seen
     */
    @PatchMapping("/{id}/seen")
    public ResponseEntity<Map<String, Object>> markSeen(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            job.setSeen(true);
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Marca uma vaga como aplicada (e como vista). Também tira do "em
     * andamento" — usado tanto pra marcar como aplicada pela primeira vez
     * quanto pra mover de volta de "Em Andamento" pra "Aplicadas".
     * PATCH /api/jobs/{id}/applied
     */
    @PatchMapping("/{id}/applied")
    public ResponseEntity<Map<String, Object>> markApplied(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            aplicarStatus(job, "APLICADA");
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Marca uma vaga como "em processo seletivo ativo" (entrevistas etc).
     * Implica aplicada+vista. Usado ao mover/arrastar de "Aplicadas" pra
     * "Em Andamento".
     * PATCH /api/jobs/{id}/in-progress
     */
    @PatchMapping("/{id}/in-progress")
    public ResponseEntity<Map<String, Object>> markInProgress(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            aplicarStatus(job, "ANDAMENTO");
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static final List<String> VALID_STATUSES =
            List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA");

    /**
     * Move a vaga diretamente pra um status específico — usado pelo menu "⋮"
     * do card, que permite pular pra qualquer aba independente da atual
     * (alternativa ao drag-and-drop, que nem sempre funciona ao iniciar o
     * arrasto a partir de um link dentro do card).
     * PATCH /api/jobs/{id}/status?value=NOVA|VISTA|APLICADA|ANDAMENTO|RECUSADA
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> setStatus(
            @PathVariable Long id, @RequestParam String value) {
        String status = value == null ? "" : value.toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().build();
        }

        return jobRepository.findById(id).map(job -> {
            aplicarStatus(job, status);
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // Ponto único que traduz um status "lógico" (NOVA/VISTA/APLICADA/ANDAMENTO/
    // RECUSADA) para os campos booleanos da entidade. RECUSADA marca rejectedAt
    // com o instante atual, usado depois pra excluir a vaga após alguns dias.
    //
    // "RECUSADA" NÃO força applied=true sozinho — antes forçava, assumindo que
    // toda recusa vem depois de uma candidatura de verdade, mas o usuário usa
    // "Recusada/congelada" também como "descartar/não tenho interesse" direto
    // de vagas nunca aplicadas (ex: limpar vagas antigas de anos atrás). Fica
    // com o applied que a vaga já tinha — true só se já era true antes.
    private void aplicarStatus(Job job, String status) {
        boolean applied = status.equals("APLICADA") || status.equals("ANDAMENTO")
                || (status.equals("RECUSADA") && job.isApplied());
        boolean inProgress = status.equals("ANDAMENTO");

        job.setSeen(!status.equals("NOVA"));
        job.setInterested(status.equals("INTERESSADO"));
        job.setApplied(applied);
        if (applied && job.getAppliedAt() == null) {
            job.setAppliedAt(LocalDateTime.now());
        }
        job.setInProgress(inProgress);
        if (inProgress && job.getInProgressAt() == null) {
            job.setInProgressAt(LocalDateTime.now());
        }
        job.setRejected(status.equals("RECUSADA"));
        job.setRejectedAt(status.equals("RECUSADA") ? LocalDateTime.now() : null);
    }

    /**
     * Dispara um fetch manual (útil para testar sem esperar o agendamento).
     * Como o fetch inicial agora roda em background ao subir o app (não
     * bloqueia mais o Tomcat, ver JobAggregatorService), dá pra essa chamada
     * chegar enquanto ele (ou o do cron de 2h) ainda está em andamento —
     * nesse caso devolve status "already-running" em vez de rodar tudo de
     * novo em paralelo à toa.
     * POST /api/jobs/fetch
     */
    @PostMapping("/fetch")
    public Map<String, Object> triggerFetch() {
        int novos = aggregatorService.fetchAllJobs();
        if (novos == JobAggregatorService.FETCH_JA_EM_ANDAMENTO) {
            return Map.of(
                    "status", "already-running",
                    "novasVagas", 0,
                    "timestamp", LocalDateTime.now().toString()
            );
        }
        return Map.of(
                "status", "ok",
                "novasVagas", novos,
                "timestamp", LocalDateTime.now().toString()
        );
    }

    /**
     * Adiciona manualmente uma vaga que não veio de nenhuma fonte automática
     * — ex: uma vaga achada no Glassdoor, LinkedIn ou indicação, que você
     * quer acompanhar no mesmo lugar que as outras.
     *
     * Se a URL já existir, atualiza a vaga existente em vez de duplicar.
     * POST /api/jobs/manual
     */
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> addManual(@RequestBody ManualJobRequest req) {
        if (req.title() == null || req.title().isBlank()
                || req.company() == null || req.company().isBlank()
                || req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String status = req.status() == null || req.status().isBlank()
                ? "APLICADA" : req.status().toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().build();
        }

        Job job = jobRepository.findByUrl(req.url()).orElseGet(Job::new);
        job.setTitle(req.title());
        job.setCompany(req.company());
        job.setUrl(req.url());
        job.setSource(req.source() == null || req.source().isBlank() ? "MANUAL" : req.source().toUpperCase());
        job.setSeniority(seniorityClassifier.classify(req.title(), req.tags()));
        job.setSalary(req.salary());
        job.setWorkplaceType(req.workplaceType());
        job.setState(req.state());
        job.setCity(req.city());
        job.setTags(req.tags());
        job.setExpiresAt(req.expiresAt());
        job.setPostedAt(req.postedAt() != null ? req.postedAt() : LocalDateTime.now());
        job.setFetchedAt(LocalDateTime.now());
        aplicarStatus(job, status);

        return ResponseEntity.ok(toDto(jobRepository.save(job)));
    }

    public record ManualJobRequest(
            String title,
            String company,
            String url,
            String source,
            String salary,
            String workplaceType,
            String state,
            String city,
            String tags,
            LocalDate expiresAt,
            LocalDateTime postedAt,
            String status
    ) {}

    // Converte entity -> DTO com tags como List<String>
    private Map<String, Object> toDto(Job job) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", job.getId());
        dto.put("title", job.getTitle());
        dto.put("company", job.getCompany());
        dto.put("url", job.getUrl());
        dto.put("source", job.getSource());
        dto.put("seniority", job.getSeniority() != null
                ? job.getSeniority()
                : SeniorityClassifier.NAO_INFORMADO);
        dto.put("salary", job.getSalary());
        dto.put("workplaceType", job.getWorkplaceType());
        dto.put("state", job.getState());
        dto.put("city", job.getCity());
        dto.put("postedAt", job.getPostedAt() != null ? job.getPostedAt().toString() : null);
        dto.put("expiresAt", job.getExpiresAt() != null ? job.getExpiresAt().toString() : null);
        dto.put("fetchedAt", job.getFetchedAt() != null ? job.getFetchedAt().toString() : null);
        dto.put("seen", job.isSeen());
        dto.put("interested", job.isInterested());
        dto.put("applied", job.isApplied());
        dto.put("appliedAt", job.getAppliedAt() != null ? job.getAppliedAt().toString() : null);
        dto.put("inProgress", job.isInProgress());
        dto.put("inProgressAt", job.getInProgressAt() != null ? job.getInProgressAt().toString() : null);
        dto.put("rejected", job.isRejected());
        dto.put("rejectedAt", job.getRejectedAt() != null ? job.getRejectedAt().toString() : null);
        dto.put("tags", job.getTags() != null
                ? Arrays.asList(job.getTags().split(","))
                : List.of());
        dto.put("companyLogoUrl", job.getCompanyLogoUrl());
        dto.put("pcd", job.getPcd() != null && job.getPcd());
        dto.put("pinned", job.getFavorited() != null && job.getFavorited());
        dto.put("notes", job.getNotes());
        dto.put("classifiedByAi", job.getClassifiedByAi() != null && job.getClassifiedByAi());
        return dto;
    }

    /**
     * Status da integração com IA (Gemini) — o frontend usa isso pra mostrar
     * se os recursos de IA (carta de apresentação, duplicatas, classificação)
     * estão ativos, sem nunca expor a key.
     * GET /api/jobs/ai-status
     */
    @GetMapping("/ai-status")
    public Map<String, Object> aiStatus() {
        Map<String, Object> status = new HashMap<>();
        boolean enabled = geminiService.isEnabled();
        status.put("enabled", enabled);
        status.put("model", enabled ? geminiService.getModel() : null);
        // Contagem aproximada (não é a oficial do Google) — só pra dar um
        // sinal antes do usuário esbarrar no limite do free tier.
        status.put("requestsToday", enabled ? geminiService.getRequestsToday() : null);
        if (enabled) {
            GeminiService.KeyPoolStatus pool = geminiService.getKeyPoolStatus();
            Map<String, Object> keyPool = new HashMap<>();
            keyPool.put("total", pool.total());
            keyPool.put("availableToday", pool.availableToday());
            keyPool.put("exhaustedToday", pool.exhaustedToday());
            status.put("keyPool", keyPool);
        } else {
            status.put("keyPool", null);
        }
        return status;
    }

    /**
     * Fixa ou desfixa uma vaga no topo da lista (toggle: fixada ↔ não fixada)
     * Vagas fixadas aparecem primeiro em qualquer aba/filtro.
     * PATCH /api/jobs/{id}/pin
     */
    @PatchMapping("/{id}/pin")
    public ResponseEntity<Map<String, Object>> togglePin(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            job.setFavorited(job.getFavorited() == null || !job.getFavorited());
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Salva anotação pessoal do usuário sobre a vaga
     * PATCH /api/jobs/{id}/notes  Body: { "notes": "..." }
     */
    @PatchMapping("/{id}/notes")
    public ResponseEntity<Map<String, Object>> updateNotes(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        return jobRepository.findById(id).map(job -> {
            String text = body.getOrDefault("notes", "");
            job.setNotes(text.isBlank() ? null : text.trim());
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // feedbackContext: histórico de avaliações (👍/👎 + comentário) que o
    // usuário deu em gerações anteriores desse mesmo recurso — mantido só no
    // localStorage do frontend (ver useAiFeedback), chega aqui formatado como
    // texto pronto e nunca é persistido no backend, igual ao perfil do candidato.
    public record CoverLetterRequest(String extraContext, String feedbackContext) {}

    /**
     * Gera uma carta de apresentação personalizada pra vaga via Gemini —
     * usa os campos que o Job Radar já tem (não a descrição completa, que
     * não é armazenada) mais qualquer contexto extra que o usuário quiser
     * colar no corpo da requisição, mais o histórico de feedback que o
     * usuário deu em cartas anteriores (se houver). 503 se a IA não estiver
     * configurada (sem GEMINI_API_KEY), 429 se o free tier estourou (por
     * minuto ou por dia — a mensagem diz qual), 502 pra qualquer outra falha
     * do Gemini.
     * POST /api/jobs/{id}/cover-letter  Body (opcional): { "extraContext": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/cover-letter")
    public ResponseEntity<Map<String, Object>> gerarCartaApresentacao(
            @PathVariable Long id, @RequestBody(required = false) CoverLetterRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String extraContext = req != null ? req.extraContext() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            GeminiService.GeminiResult resultado = coverLetterService.gerar(job, extraContext, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            return ResponseEntity.ok(Map.<String, Object>of("coverLetter", resultado.text()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Faixa salarial estimada — dado real do banco, nunca um chute de IA.
     * Combina duas fontes, nenhuma depende do Gemini estar configurado:
     * {@code predicted} vem de um modelo (regressão Ridge) treinado offline
     * sobre as vagas com salário do banco, sempre disponível quando o
     * modelo carregou (ver SalaryPredictionService — erro médio ~43%,
     * exposto em {@code modelInfo} pra não esconder a incerteza);
     * {@code similarJobs} é a mediana das vagas parecidas (mesma senioridade
     * + tag em comum), disponível só com amostra mínima (>=3).
     * GET /api/jobs/{id}/salary-estimate
     */
    @GetMapping("/{id}/salary-estimate")
    public ResponseEntity<Map<String, Object>> estimarSalario(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            Map<String, Object> body = new HashMap<>();
            boolean anyAvailable = false;

            Optional<Long> predicted = salaryPredictionService.predict(
                    job.getSeniority(), tagList(job.getTags()), job.getWorkplaceType(), job.getState());
            if (predicted.isPresent()) {
                body.put("predicted", predicted.get());
                SalaryPredictionService.ModelInfo info = salaryPredictionService.getModelInfo();
                body.put("modelInfo", Map.of(
                        "nSamples", info.nSamples(), "r2", info.r2(), "maePercent", info.maePercent()));
                anyAvailable = true;
            }

            Optional<SalaryEstimateService.SalaryEstimate> estimativa = salaryEstimateService.estimate(job);
            if (estimativa.isPresent()) {
                SalaryEstimateService.SalaryEstimate e = estimativa.get();
                Map<String, Object> similar = new HashMap<>();
                similar.put("sampleSize", e.sampleSize());
                similar.put("min", e.min());
                similar.put("max", e.max());
                similar.put("median", e.median());
                body.put("similarJobs", similar);
                anyAvailable = true;
            }

            body.put("available", anyAvailable);
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    private List<String> tagList(String tags) {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    /**
     * Estimativa personalizada: usa a senioridade/stack extraídas do
     * currículo/perfil salvo pelo candidato (nunca persistido — vem no
     * corpo do request) em vez dos dados da vaga, mantendo modalidade e
     * estado da vaga (isso não muda com quem se candidata). Sempre 200 —
     * available=false só se o modelo não tiver carregado ou o perfil vier
     * vazio, nunca erro.
     * POST /api/jobs/{id}/salary-estimate/personalized  Body: { "candidateProfile": "..." }
     */
    @PostMapping("/{id}/salary-estimate/personalized")
    public ResponseEntity<Map<String, Object>> estimarSalarioPersonalizado(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            Map<String, Object> body = new HashMap<>();
            if (perfil == null || perfil.isBlank() || !salaryPredictionService.isLoaded()) {
                body.put("available", false);
                return ResponseEntity.ok(body);
            }

            Set<String> stackDoCurriculo = salaryPredictionService.extractTagsFromText(perfil);
            String senioridadeInferida = seniorityClassifier.classify(perfil, String.join(",", stackDoCurriculo));

            Optional<Long> predicted = salaryPredictionService.predict(
                    senioridadeInferida, stackDoCurriculo, job.getWorkplaceType(), job.getState());
            if (predicted.isEmpty()) {
                body.put("available", false);
                return ResponseEntity.ok(body);
            }
            body.put("available", true);
            body.put("predicted", predicted.get());
            body.put("inferredSeniority", senioridadeInferida);
            body.put("inferredStack", stackDoCurriculo);
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Exporta as vagas com salário parseável e limpo (mesma lógica do
     * salary-estimate) pra treinar um modelo real fora do backend — uso
     * interno/manutenção, não é chamado pelo frontend. Ver
     * scripts/train_salary_model.py e SalaryPredictionService.
     * GET /api/jobs/admin/salary-training-data
     */
    @GetMapping("/admin/salary-training-data")
    public List<SalaryEstimateService.TrainingRow> exportSalaryTrainingData() {
        return salaryEstimateService.exportTrainingData();
    }

    public record RetrainCodeRequest(String code, Boolean force) {}

    private boolean codigoRetreinoBate(String code) {
        return retrainSecretCode != null && !retrainSecretCode.isBlank()
                && code != null && code.equals(retrainSecretCode);
    }

    /**
     * Passo 1 do fluxo de retreino do frontend: só confirma se o código
     * digitado bate, sem disparar o treino de verdade — o botão "Retreinar"
     * só aparece na tela depois de um {@code valid:true} aqui.
     * POST /api/jobs/admin/verify-retrain-code  Body: { "code": "..." }
     */
    @PostMapping("/admin/verify-retrain-code")
    public ResponseEntity<Map<String, Object>> verificarCodigoRetreino(@RequestBody(required = false) RetrainCodeRequest req) {
        return ResponseEntity.ok(Map.of("valid", codigoRetreinoBate(req != null ? req.code() : null)));
    }

    /**
     * Retreina o modelo de salário na hora (Ridge regression em Java puro,
     * ver SalaryModelTrainerService) e já troca o modelo em uso — sem
     * precisar rodar o script Python nem reconstruir o container. O código
     * é validado de novo aqui (não confia só na checagem do passo 1).
     * POST /api/jobs/admin/retrain-salary-model  Body: { "code": "..." }
     */
    @PostMapping("/admin/retrain-salary-model")
    public ResponseEntity<Map<String, Object>> retreinarModeloSalario(@RequestBody(required = false) RetrainCodeRequest req) {
        if (retrainSecretCode == null || retrainSecretCode.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error", "RETRAIN_SECRET_CODE não configurado no .env — recurso desativado."));
        }
        if (!codigoRetreinoBate(req != null ? req.code() : null)) {
            return ResponseEntity.status(403).body(Map.of("error", "Código incorreto."));
        }
        if (!retreinoEmAndamento.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("error", "Já tem um retreino em andamento — espera terminar."));
        }
        try {
            SalaryPredictionService.ModelInfo anterior = salaryPredictionService.getModelInfo();
            SalaryModelTrainerService.TrainResult resultado = salaryModelTrainerService.treinar();

            // Erro % (maePercent) é o número que a UI destaca em todo lugar
            // como "margem de erro típica" — é o que o usuário realmente
            // olha pra saber se piorou ou melhorou. Um retreino que piora
            // esse número não substitui o modelo em produção sozinho: o
            // treino em si sempre roda (não tem como saber se piorou sem
            // treinar), mas só troca o modelo em uso se não piorou, ou se o
            // usuário mandou forçar mesmo assim depois de ver a comparação.
            boolean force = req != null && Boolean.TRUE.equals(req.force());
            boolean piorou = anterior != null && resultado.maePercent() > anterior.maePercent();
            boolean aplicado = force || !piorou;
            if (aplicado) {
                salaryPredictionService.reload(resultado.modelJson());
            }

            Map<String, Object> body = new HashMap<>();
            body.put("previous", anterior == null ? null : Map.of(
                    "nSamples", anterior.nSamples(), "r2", anterior.r2(),
                    "maeBrl", anterior.maeBrl(), "maePercent", anterior.maePercent()));
            body.put("updated", Map.of(
                    "nSamples", resultado.nSamples(), "r2", resultado.r2(),
                    "maeBrl", resultado.maeBrl(), "maePercent", resultado.maePercent()));
            body.put("applied", aplicado);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Falha ao retreinar modelo de salário", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erro interno ao retreinar: " + e.getMessage()));
        } finally {
            retreinoEmAndamento.set(false);
        }
    }

    public record CandidateProfileRequest(String candidateProfile, String feedbackContext) {}

    /**
     * Compatibilidade entre o perfil/currículo do candidato (enviado pelo
     * frontend — nunca persistido no backend) e a vaga, considerando também
     * o histórico de feedback do usuário sobre análises anteriores, se
     * houver. 503 sem IA configurada, 429 em rate limit, 502 pra outras falhas.
     * POST /api/jobs/{id}/match-score  Body: { "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/match-score")
    public ResponseEntity<Map<String, Object>> calcularCompatibilidade(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            MatchScoreService.MatchOutcome resultado = matchScoreService.calcular(job, perfil, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            MatchScoreService.MatchResult r = resultado.result();
            Map<String, Object> body = new HashMap<>();
            body.put("score", r.score());
            body.put("pontosFortes", r.pontosFortes());
            body.put("pontosFaltando", r.pontosFaltando());
            body.put("resumo", r.resumo());
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    public record LearningPlanRequest(String gap, String candidateProfile, String feedbackContext) {}

    /**
     * "Contramedida" pra um ponto a desenvolver específico apontado pela
     * análise de compatibilidade (match-score) — plano de ação prático pra
     * fechar aquela lacuna, considerando o perfil atual do candidato como
     * ponto de partida. Mesma regra de privacidade das outras rotas de IA:
     * perfil nunca é persistido no backend, só chega junto do request.
     * POST /api/jobs/{id}/learning-plan  Body: { "gap": "...", "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/learning-plan")
    public ResponseEntity<Map<String, Object>> gerarPlanoDeAprendizado(
            @PathVariable Long id, @RequestBody(required = false) LearningPlanRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        if (req == null || req.gap() == null || req.gap().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe qual ponto a desenvolver quer um plano."));
        }
        return jobRepository.findById(id).map(job -> {
            LearningPlanService.PlanOutcome resultado = learningPlanService.gerar(job, req.gap(), req.candidateProfile(), req.feedbackContext());
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            LearningPlanService.LearningPlan p = resultado.plan();
            Map<String, Object> body = new HashMap<>();
            body.put("resumo", p.resumo());
            body.put("tempoEstimado", p.tempoEstimado());
            body.put("passos", p.passos());
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Perguntas prováveis de entrevista pra vaga, opcionalmente ajustadas ao
     * perfil do candidato (mesma regra de privacidade do match-score — só
     * chega no backend se o frontend mandar nesse request específico).
     * POST /api/jobs/{id}/interview-questions  Body opcional: { "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/interview-questions")
    public ResponseEntity<Map<String, Object>> gerarPerguntasEntrevista(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            InterviewQuestionsService.QuestionsOutcome resultado = interviewQuestionsService.gerar(job, perfil, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            return ResponseEntity.ok(Map.<String, Object>of("questions", resultado.questions()));
        }).orElse(ResponseEntity.notFound().build());
    }

    public record CompatibilityScanRequest(String candidateProfile, Integer days, String feedbackContext) {}

    /**
     * Ação "🤖 Jarvis": compatibilidade do perfil salvo com vagas recentes.
     * Pré-filtra por sobreposição de tags/senioridade (sem IA, sempre roda)
     * e só chama o Gemini de verdade nas top vagas do pré-filtro — ver
     * JarvisAssistantService pro porquê. 200 sempre, com available=false
     * quando não há perfil, e errorMessage preenchido se a IA falhar em
     * todas as tentativas (não depende do Gemini estar habilitado pra
     * responder "sem dados" — só falha graciosamente se estiver desligado).
     * POST /api/jobs/assistant/compatibility-scan
     * Body: { "candidateProfile": "...", "days": 1, "feedbackContext": "..." }
     */
    @PostMapping("/assistant/compatibility-scan")
    public Map<String, Object> assistantCompatibilityScan(@RequestBody(required = false) CompatibilityScanRequest req) {
        String perfil = req != null ? req.candidateProfile() : null;
        int dias = req != null && req.days() != null ? req.days() : 1;
        String feedback = req != null ? req.feedbackContext() : null;

        JarvisAssistantService.CompatibilityResult resultado = jarvisAssistantService.scanCompatibilidade(perfil, dias, feedback);

        Map<String, Object> body = new HashMap<>();
        body.put("available", resultado.available());
        body.put("totalConsiderados", resultado.totalConsiderados());
        body.put("totalAnalisadosPorIa", resultado.totalAnalisadosPorIa());
        body.put("errorMessage", resultado.errorMessage());
        body.put("hits", resultado.hits().stream().map(h -> {
            Map<String, Object> hit = new HashMap<>();
            Map<String, Object> jobDto = new HashMap<>();
            jobDto.put("id", h.job().getId());
            jobDto.put("title", h.job().getTitle());
            jobDto.put("company", h.job().getCompany());
            jobDto.put("url", h.job().getUrl());
            jobDto.put("source", h.job().getSource());
            hit.put("job", jobDto);
            hit.put("score", h.score());
            hit.put("pontosFortes", h.pontosFortes());
            hit.put("pontosFaltando", h.pontosFaltando());
            hit.put("resumo", h.resumo());
            return hit;
        }).toList());
        return body;
    }

    // imageMimeType/imageBase64: só preenchido na mensagem mais recente,
    // quando o usuário anexa um print no chat (ver JarvisChatService.
    // ChatMessage e GeminiService.userTurnWithImage). base64Data vem sem o
    // prefixo "data:image/png;base64," — o frontend já manda só o miolo.
    public record ChatMessageDto(String role, String text, String imageMimeType, String imageBase64) {}

    // feedbackContext: 👍/👎 salvos pelo usuário nos cards de compatibilidade/
    // plano de ação (useAiFeedback no frontend) — mesmo texto que já
    // alimenta match-score e learning-plan, agora também chega no chat.
    public record ChatRequest(List<ChatMessageDto> history, String candidateProfile, String feedbackContext) {}

    /**
     * Chat livre do Jarvis — diferente do compatibility-scan (ação fixa),
     * aqui o Gemini decide sozinho quais ferramentas chamar (listar vagas,
     * resumo do funil, compatibilidade) a partir da mensagem em linguagem
     * natural, via function-calling de verdade (ver JarvisChatService).
     * 503 sem IA configurada, 429 em rate limit, 502 pra outras falhas.
     * POST /api/jobs/assistant/chat
     * Body: { "history": [{"role":"user","text":"...","imageMimeType":null,"imageBase64":null}, ...], "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/assistant/chat")
    public ResponseEntity<Map<String, Object>> assistantChat(@RequestBody(required = false) ChatRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        List<JarvisChatService.ChatMessage> historico = req != null && req.history() != null
                ? req.history().stream()
                        .map(m -> new JarvisChatService.ChatMessage(m.role(), m.text(), m.imageMimeType(), m.imageBase64()))
                        .toList()
                : List.of();
        String perfil = req != null ? req.candidateProfile() : null;
        String feedbackContext = req != null ? req.feedbackContext() : null;

        JarvisChatService.ChatOutcome resultado = jarvisChatService.conversar(historico, perfil, feedbackContext);
        if (!resultado.ok()) {
            return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                    .body(Map.<String, Object>of("error", resultado.errorMessage()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("reply", resultado.reply());
        body.put("toolResults", resultado.toolResults());
        return ResponseEntity.ok(body);
    }
}

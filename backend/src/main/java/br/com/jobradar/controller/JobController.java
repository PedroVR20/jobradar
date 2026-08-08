package br.com.jobradar.controller;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.service.AiDuplicateVerifierService;
import br.com.jobradar.service.CoverLetterService;
import br.com.jobradar.service.GeminiService;
import br.com.jobradar.service.JobAggregatorService;
import br.com.jobradar.service.SeniorityClassifier;
import lombok.RequiredArgsConstructor;
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
import java.util.OptionalDouble;
import java.util.Set;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JobController {

    private final JobRepository jobRepository;
    private final JobAggregatorService aggregatorService;
    private final SeniorityClassifier seniorityClassifier;
    private final AiDuplicateVerifierService aiDuplicateVerifierService;
    private final CoverLetterService coverLetterService;
    private final GeminiService geminiService;

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
    private void aplicarStatus(Job job, String status) {
        boolean applied = status.equals("APLICADA") || status.equals("ANDAMENTO") || status.equals("RECUSADA");
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
     * Dispara um fetch manual (útil para testar sem esperar o agendamento)
     * POST /api/jobs/fetch
     */
    @PostMapping("/fetch")
    public Map<String, Object> triggerFetch() {
        int novos = aggregatorService.fetchAllJobs();
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
        return dto;
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

    public record CoverLetterRequest(String extraContext) {}

    /**
     * Gera uma carta de apresentação personalizada pra vaga via Gemini —
     * usa os campos que o Job Radar já tem (não a descrição completa, que
     * não é armazenada) mais qualquer contexto extra que o usuário quiser
     * colar no corpo da requisição. 503 se a IA não estiver configurada
     * (sem GEMINI_API_KEY), 502 se a chamada ao Gemini falhar.
     * POST /api/jobs/{id}/cover-letter  Body (opcional): { "extraContext": "..." }
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
            String carta = coverLetterService.gerar(job, extraContext);
            if (carta == null) {
                return ResponseEntity.status(502)
                        .body(Map.<String, Object>of("error", "Não foi possível gerar a carta agora. Tente de novo em instantes."));
            }
            return ResponseEntity.ok(Map.<String, Object>of("coverLetter", carta));
        }).orElse(ResponseEntity.notFound().build());
    }
}

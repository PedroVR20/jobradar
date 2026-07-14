package br.com.jobradar.controller;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.service.JobAggregatorService;
import br.com.jobradar.service.SeniorityClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JobController {

    private final JobRepository jobRepository;
    private final JobAggregatorService aggregatorService;
    private final SeniorityClassifier seniorityClassifier;

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
     * onlySeen  → só vistas e não aplicadas
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
            @RequestParam(required = false, defaultValue = "false") boolean onlyApplied,
            @RequestParam(required = false, defaultValue = "false") boolean onlyInProgress,
            @RequestParam(required = false, defaultValue = "false") boolean onlyRejected
    ) {
        List<Job> jobs = jobRepository.findAll();
        LocalDateTime postedAfter = days != null && days > 0
                ? LocalDateTime.now().minusDays(days)
                : null;

        return jobs.stream()
                .filter(j -> source == null || j.getSource().equalsIgnoreCase(source))
                .filter(j -> seniority == null || seniority.isBlank()
                        || seniority.equalsIgnoreCase(j.getSeniority()))
                .filter(j -> workplaceType == null || workplaceType.isBlank()
                        || workplaceType.equalsIgnoreCase(j.getWorkplaceType()))
                .filter(j -> state == null || state.isBlank()
                        || (j.getState() != null && normalize(state).equals(normalize(j.getState()))))
                .filter(j -> !onlyNew || (!j.isSeen() && !j.isRejected()))
                .filter(j -> !onlySeen || (j.isSeen() && !j.isApplied() && !j.isRejected()))
                .filter(j -> !onlyApplied || (j.isApplied() && !j.isInProgress() && !j.isRejected()))
                .filter(j -> !onlyInProgress || (j.isApplied() && j.isInProgress() && !j.isRejected()))
                .filter(j -> !onlyRejected || j.isRejected())
                .filter(j -> postedAfter == null || (j.getPostedAt() != null
                        && j.getPostedAt().isAfter(postedAfter)))
                .filter(j -> matchesSearch(j, search))
                .sorted(comparatorFor(sort))
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
                "EURECA", jobRepository.countBySource("EURECA")
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
            List.of("NOVA", "VISTA", "APLICADA", "ANDAMENTO", "RECUSADA");

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
        job.setSeen(!status.equals("NOVA"));
        job.setApplied(status.equals("APLICADA") || status.equals("ANDAMENTO") || status.equals("RECUSADA"));
        job.setInProgress(status.equals("ANDAMENTO"));
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
        dto.put("applied", job.isApplied());
        dto.put("inProgress", job.isInProgress());
        dto.put("rejected", job.isRejected());
        dto.put("rejectedAt", job.getRejectedAt() != null ? job.getRejectedAt().toString() : null);
        dto.put("tags", job.getTags() != null
                ? Arrays.asList(job.getTags().split(","))
                : List.of());
        return dto;
    }
}

package br.com.jobradar.service;

import br.com.jobradar.dto.JobListItemDto;
import br.com.jobradar.dto.JobPageResult;
import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.repository.JobSpecifications;
import lombok.RequiredArgsConstructor;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Fase 5.5 + 6.3 — extraído de {@code JobController.getAll()}, que fazia
 * filtro SQL + busca textual + ordenação + (agora) paginação tudo inline no
 * controller. Centraliza a lógica de listagem num lugar só, testável sem
 * precisar montar um {@code MockMvc}.
 *
 * <p>A paginação acontece DEPOIS do filtro/ordenação em memória (não é um
 * {@code LIMIT}/{@code OFFSET} de SQL) — de propósito: busca textual
 * multi-termo e o ranking pessoal (Fase 3.1) são calculados em Java, não em
 * SQL. Paginar ANTES desses filtros devolveria página incompleta/errada. O
 * ganho real da Fase 6.3 é outro: não é o backend que economiza trabalho, é
 * a REDE — antes o catálogo inteiro filtrado (4,37 MB medidos sem filtro
 * nenhum) viajava pra tela mostrar 30 itens; agora só a página pedida vai
 * pela rede.</p>
 *
 * <p>Fase 12.1 — o filtro de ESTADO saiu daqui e virou {@code WHERE} de SQL
 * (ver {@link JobSpecifications#byState}): tinha um índice desde a Fase 6.2
 * que nunca era alcançado justamente porque o filtro rodava em Java — agora
 * o índice funcional criado na Fase 12 bate com a query de verdade.</p>
 */
@Service
@RequiredArgsConstructor
public class JobQueryService {

    private final JobRepository jobRepository;
    private final PersonalRankingService personalRankingService;

    public record Filtro(
            String source,
            String search,
            String seniority,
            String workplaceType,
            String state,
            Integer days,
            String sort,
            boolean onlyNew,
            boolean onlySeen,
            boolean onlyInteressado,
            boolean onlyApplied,
            boolean onlyInProgress,
            boolean onlyRejected,
            List<String> techStack,
            int page,
            int size
    ) {}

    private static final int TAMANHO_PAGINA_PADRAO = 30;
    private static final int TAMANHO_PAGINA_MAXIMO = 100;

    public JobPageResult listar(Filtro f) {
        LocalDateTime postedAfter = f.days() != null && f.days() > 0
                ? LocalDateTime.now().minusDays(f.days())
                : null;

        // Filtros de comparação direta (fonte, senioridade, modalidade, data,
        // status do funil) viram WHERE de SQL — cortam a imensa maioria das
        // ~5900 vagas ANTES de qualquer coisa rodar em memória Java.
        Specification<Job> spec = JobSpecifications.combine(
                JobSpecifications.bySource(f.source()),
                JobSpecifications.bySeniorityIn(f.seniority()),
                JobSpecifications.byWorkplaceType(f.workplaceType()),
                JobSpecifications.byState(f.state()), // Fase 12.1 — antes rodava em Java, ver Javadoc da classe
                JobSpecifications.postedAfter(postedAfter),
                f.onlyNew() ? JobSpecifications.onlyNew() : null,
                f.onlySeen() ? JobSpecifications.onlySeen() : null,
                f.onlyInteressado() ? JobSpecifications.onlyInteressado() : null,
                f.onlyApplied() ? JobSpecifications.onlyApplied() : null,
                f.onlyInProgress() ? JobSpecifications.onlyInProgress() : null,
                f.onlyRejected() ? JobSpecifications.onlyRejected() : null
        );
        List<Job> jobs = jobRepository.findAll(spec);

        // vagas pinadas sempre sobem ao topo, independente da aba ou filtro
        Comparator<Job> pinnedFirst = Comparator.comparing(
                (Job j) -> !Boolean.TRUE.equals(j.getFavorited()));
        Comparator<Job> comparadorDeConteudo = "personal".equals(f.sort()) ? comparatorPersonal() : comparatorFor(f.sort());

        List<Job> filtradosEOrdenados = jobs.stream()
                .filter(j -> matchesSearch(j, f.search()))
                .filter(j -> matchesAnyTechStack(j, f.techStack()))
                .sorted(pinnedFirst.thenComparing(comparadorDeConteudo))
                .toList();

        int tamanho = f.size() > 0 ? Math.min(f.size(), TAMANHO_PAGINA_MAXIMO) : TAMANHO_PAGINA_PADRAO;
        int pagina = Math.max(0, f.page());
        int total = filtradosEOrdenados.size();
        int totalPaginas = tamanho == 0 ? 0 : (int) Math.ceil(total / (double) tamanho);
        int inicio = Math.min(pagina * tamanho, total);
        int fim = Math.min(inicio + tamanho, total);

        List<JobListItemDto> pagina_ = filtradosEOrdenados.subList(inicio, fim).stream()
                .map(JobListItemDto::de)
                .toList();

        return new JobPageResult(pagina_, total, pagina, tamanho, totalPaginas);
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

    // Fase 6.3 — antes os pills de tech stack só filtravam no CLIENTE (o
    // frontend recebia o catálogo inteiro filtrado e cortava mais em cima).
    // Com paginação de verdade isso teria que ver a lista inteira pra
    // decidir quais 30 mostrar — então virou filtro de servidor também,
    // mesma lógica de normalização do search, mas com OR entre os termos
    // (aparece se bater com QUALQUER pill selecionado) em vez de AND.
    private boolean matchesAnyTechStack(Job j, List<String> techStack) {
        if (techStack == null || techStack.isEmpty()) return true;
        String haystack = normalize(j.getTitle() + " " + j.getCompany() + " "
                + (j.getTags() != null ? j.getTags() : ""));
        return techStack.stream().anyMatch(p -> haystack.contains(normalize(p)));
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
     * Fase 3.1 — ranking pessoal aprendido (sem IA, ver
     * PersonalRankingService). Treina UMA vez por request (não dentro do
     * comparator — key extractor pode ser chamado várias vezes por
     * elemento durante o sort, treinar ali reprocessaria o catálogo
     * inteiro repetidas vezes).
     */
    private Comparator<Job> comparatorPersonal() {
        PersonalRankingService.Modelo modelo = personalRankingService.treinar();
        return Comparator
                .comparing((Job j) -> personalRankingService.pontuar(j, modelo))
                .reversed()
                .thenComparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }
}

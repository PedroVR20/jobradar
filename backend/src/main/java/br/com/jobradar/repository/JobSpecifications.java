package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.springframework.data.jpa.domain.Specification;

import java.text.Normalizer;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Filtros de {@link Job} traduzidos pra WHERE de SQL (via Specification),
 * em vez de {@code findAll()} + {@code stream().filter(...)} em Java.
 *
 * <p>Migração PARCIAL de propósito: só os filtros de comparação direta
 * (fonte, senioridade, modalidade, data, estado, e os status booleanos do
 * funil) vieram pra cá — são baratos e inequívocos em SQL. A busca textual
 * multi-termo insensível a acento ({@code matchesSearch} no controller)
 * continua em Java: "java senior" exigindo TODOS os termos em título+
 * empresa+tags é mais natural como filtro pós-carga do que um WHERE com N
 * termos dinâmicos. O filtro de estado (abaixo) MIGROU pra SQL na Fase
 * 12.1 — motivo: tinha um índice (Fase 6.2, {@code idx_jobs_state}) que a
 * aplicação nunca alcançava porque o filtro real rodava em Java, então o
 * índice existia só de enfeite (confirmado: zero leituras no
 * pg_stat_user_indexes). Agora usa a extensão {@code unaccent} do Postgres
 * (instalada via V2__indices_fase_12.sql) pra continuar ignorando acento
 * ("sao paulo" acha "São Paulo") sem regredir esse comportamento.</p>
 */
public final class JobSpecifications {

    private JobSpecifications() {
    }

    // Mesma técnica usada em vários outros pontos do projeto (JobController,
    // JobQueryService) — Normalizer decompõe e descarta os diacríticos.
    // Duplicado aqui de propósito, mesmo precedente já registrado no
    // projeto: é um helper pequeno o bastante que uma dependência entre
    // classes só pra reusá-lo custaria mais do que vale.
    private static String normalizeIgnorandoAcento(String texto) {
        String decomposto = Normalizer.normalize(texto.toLowerCase(), Normalizer.Form.NFD);
        return decomposto.replaceAll("\\p{M}", "");
    }

    // O alvo é normalizado em JAVA (mesma função usada em todo o projeto,
    // determinística) — só a COLUNA precisa de unaccent() em SQL, porque é
    // ela que tem acento gravado no banco. Chama immutable_unaccent (não
    // unaccent puro) — mesmo wrapper criado em V2__indices_fase_12.sql, que
    // existe porque o unaccent() de fábrica é STABLE, não IMMUTABLE, e por
    // isso não pode ser usado num índice de expressão. Índice funcional
    // criado nessa migração é sobre exatamente esta expressão
    // (lower(immutable_unaccent(state))) — precisa bater com o SQL gerado
    // aqui pra o planner conseguir usar o índice.
    public static Specification<Job> byState(String state) {
        if (state == null || state.isBlank()) return null;
        String alvo = normalizeIgnorandoAcento(state);
        return (root, query, cb) -> cb.equal(
                cb.lower(cb.function("immutable_unaccent", String.class, root.get("state"))), alvo);
    }

    public static Specification<Job> bySource(String source) {
        if (source == null || source.isBlank()) return null;
        String alvo = source.toLowerCase();
        return (root, query, cb) -> cb.equal(cb.lower(root.get("source")), alvo);
    }

    // Aceita lista separada por vírgula (ex: "ESTAGIO,JUNIOR", usado pelo
    // Modo Iniciante do frontend) — vira um IN, não uma cadeia de OR manual.
    public static Specification<Job> bySeniorityIn(String seniorityCsv) {
        if (seniorityCsv == null || seniorityCsv.isBlank()) return null;
        List<String> valores = Arrays.stream(seniorityCsv.split(","))
                .map(s -> s.trim().toUpperCase())
                .filter(s -> !s.isBlank())
                .toList();
        if (valores.isEmpty()) return null;
        return (root, query, cb) -> root.get("seniority").in(valores);
    }

    public static Specification<Job> byWorkplaceType(String workplaceType) {
        if (workplaceType == null || workplaceType.isBlank()) return null;
        String alvo = workplaceType.toLowerCase();
        return (root, query, cb) -> cb.equal(cb.lower(root.get("workplaceType")), alvo);
    }

    public static Specification<Job> postedAfter(LocalDateTime cutoff) {
        if (cutoff == null) return null;
        return (root, query, cb) -> cb.greaterThan(root.get("postedAt"), cutoff);
    }

    public static Specification<Job> onlyNew() {
        return (root, query, cb) -> cb.and(cb.isFalse(root.get("seen")), cb.isFalse(root.get("rejected")));
    }

    public static Specification<Job> onlySeen() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("seen")), cb.isFalse(root.get("interested")),
                cb.isFalse(root.get("applied")), cb.isFalse(root.get("rejected")));
    }

    public static Specification<Job> onlyInteressado() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("interested")), cb.isFalse(root.get("applied")), cb.isFalse(root.get("rejected")));
    }

    public static Specification<Job> onlyApplied() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("applied")), cb.isFalse(root.get("inProgress")), cb.isFalse(root.get("rejected")));
    }

    public static Specification<Job> onlyInProgress() {
        return (root, query, cb) -> cb.and(
                cb.isTrue(root.get("applied")), cb.isTrue(root.get("inProgress")), cb.isFalse(root.get("rejected")));
    }

    public static Specification<Job> onlyRejected() {
        return (root, query, cb) -> cb.isTrue(root.get("rejected"));
    }

    // Inverso de onlyRejected() — usado pelos vários pontos do app (Hunter
    // incluso) que hoje fazem findAll() + filter(!isRejected()) em Java,
    // carregando vaga recusada pra memória só pra descartar em seguida.
    public static Specification<Job> notRejected() {
        return (root, query, cb) -> cb.isFalse(root.get("rejected"));
    }

    // Fase 7.2 — "vaga vencida" (expiresAt no passado) precisa parar de
    // aparecer nas abas de decisão (Novas/Já vistas/Interessado) — ninguém
    // vai se candidatar depois do prazo formal. NULL conta como "não
    // vencida" (a maioria das fontes — só Gupy e Eureca preenchem
    // expiresAt hoje — não expõe prazo nenhum, então ausência de dado não
    // pode virar exclusão).
    public static Specification<Job> notExpired() {
        return (root, query, cb) -> cb.or(
                cb.isNull(root.get("expiresAt")),
                cb.greaterThanOrEqualTo(root.get("expiresAt"), LocalDate.now()));
    }

    // Inverso — vira a aba "Vencidas". Só entram as que ainda estavam na
    // esteira de decisão (nem aplicada, nem recusada): quem já aplicou
    // continua normalmente em Aplicadas mesmo com o prazo formal encerrado
    // (o processo seletivo real pode seguir depois da data-limite anunciada).
    public static Specification<Job> onlyExpired() {
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("expiresAt")),
                cb.lessThan(root.get("expiresAt"), LocalDate.now()),
                cb.isFalse(root.get("applied")),
                cb.isFalse(root.get("rejected")));
    }

    // Fase 14.1 — Hunter (JarvisChatService) filtrava tudo em Java depois de
    // um findAll() sem WHERE nenhum, igual o resto do app fazia antes da
    // Fase 1.6/5.5 — mesmo padrão de correção, aplicado nas ferramentas de
    // chat que estavam de fora.
    public static Specification<Job> byCompanyContainsIgnoreCase(String trecho) {
        if (trecho == null || trecho.isBlank()) return null;
        String alvo = "%" + trecho.toLowerCase() + "%";
        return (root, query, cb) -> cb.like(cb.lower(root.get("company")), alvo);
    }

    // "vagasComPrazoProximo" do Hunter — prazo entre hoje e um limite N dias
    // à frente, vaga não recusada (mesmo critério que já existia em Java).
    public static Specification<Job> expiraEntre(LocalDate hoje, LocalDate limite) {
        return (root, query, cb) -> cb.and(
                cb.isNotNull(root.get("expiresAt")),
                cb.greaterThanOrEqualTo(root.get("expiresAt"), hoje),
                cb.lessThanOrEqualTo(root.get("expiresAt"), limite));
    }

    // "vagasParadas" do Hunter — candidata a "parada" só pode ser vaga
    // aplicada (o bucket ANDAMENTO também exige applied=true no modelo,
    // então esse filtro sozinho já cobre os dois status que a ferramenta
    // considera) e não recusada.
    public static Specification<Job> appliedNaoRejeitada() {
        return (root, query, cb) -> cb.and(cb.isTrue(root.get("applied")), cb.isFalse(root.get("rejected")));
    }

    // Fase 7.3+8.4 — vaga arquivada some de TODAS as abas por padrão (não só
    // das de decisão como a vencida) — é um "porão" à parte, só visível na
    // aba dedicada Arquivadas. Ver JobAggregatorService.arquivarVagasAntigasNuncaEngajadas.
    public static Specification<Job> notArchived() {
        return (root, query, cb) -> cb.isFalse(root.get("archived"));
    }

    public static Specification<Job> onlyArchived() {
        return (root, query, cb) -> cb.isTrue(root.get("archived"));
    }

    // Encadeia specs opcionais, ignorando as nulas — evita um "and" gigante
    // cheio de checagem de null no controller.
    @SafeVarargs
    public static Specification<Job> combine(Specification<Job>... specs) {
        Specification<Job> resultado = Specification.where(null);
        for (Specification<Job> s : specs) {
            if (s != null) resultado = resultado.and(s);
        }
        return resultado;
    }
}

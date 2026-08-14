package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.springframework.data.jpa.domain.Specification;

import java.text.Normalizer;
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

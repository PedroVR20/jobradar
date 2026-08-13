package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.springframework.data.jpa.domain.Specification;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * Filtros de {@link Job} traduzidos pra WHERE de SQL (via Specification),
 * em vez de {@code findAll()} + {@code stream().filter(...)} em Java.
 *
 * <p>Migração PARCIAL de propósito: só os filtros de comparação direta
 * (fonte, senioridade, modalidade, data, e os status booleanos do funil)
 * vieram pra cá — são baratos e inequívocos em SQL. A busca textual
 * multi-termo insensível a acento ({@code matchesSearch} no controller) e a
 * comparação de estado ignorando acento continuam em Java, porque fazer isso
 * certo em SQL exigiria a extensão {@code unaccent} do Postgres (não
 * instalada hoje) — sem isso, dá pra migrar errado de um jeito sutil (ex:
 * "São Paulo" parar de bater com "sao paulo") sem ninguém notar até
 * reclamarem. Mesmo assim, GET /api/jobs (endpoint mais chamado do app,
 * toda troca de filtro) já sai bem mais leve: os filtros daqui costumam
 * cortar a imensa maioria das ~4800 vagas ANTES de qualquer coisa rodar em
 * memória Java.</p>
 */
public final class JobSpecifications {

    private JobSpecifications() {
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

package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long>, JpaSpecificationExecutor<Job> {

    Optional<Job> findByUrl(String url);

    List<Job> findByFetchedAtAfter(LocalDateTime dateTime);

    long countByFetchedAtAfter(LocalDateTime dateTime);

    List<Job> findBySource(String source);

    List<Job> findByAppliedTrue();

    List<Job> findBySeenFalse();

    long countBySource(String source);

    long countBySeenFalse();

    long countByAppliedTrue();

    long countByInterestedTrue();

    long countBySeniority(String seniority);

    List<Job> findBySeniorityIsNull();

    List<Job> findByWorkplaceTypeIsNullAndSourceIn(List<String> sources);

    List<Job> findBySourceAndSalaryIsNullOrderByPostedAtDesc(String source, Pageable pageable);

    // Fase 1.3 — duas fases do backfill de salário: primeiro as NUNCA
    // checadas (prioridade — é isso que faz o backfill avançar pelo
    // catálogo em vez de reprocessar sempre as mesmas), depois, só se
    // sobrar cota, as já checadas há mais tempo (empresa pode ter
    // adicionado salário depois da primeira checagem).
    List<Job> findBySourceAndSalaryIsNullAndSalaryCheckedAtIsNull(String source, Pageable pageable);

    List<Job> findBySourceAndSalaryIsNullAndSalaryCheckedAtIsNotNullOrderBySalaryCheckedAtAsc(String source, Pageable pageable);

    // Vagas do Nerdin salvas ANTES do fix que separa "Cidade • UF" em
    // cidade+estado de verdade (ver NerdinService e Fase 1.2) — o texto cru
    // ficou mashed no campo city, precisa de um backfill pontual pra limpar.
    List<Job> findBySourceAndCityContaining(String source, String needle);

    long countByAppliedTrueAndInProgressTrue();

    long countByRejectedTrue();

    // Distinto de countByRejectedTrue(): esse só conta recusa de vaga que
    // REALMENTE foi aplicada antes (não inclui vaga jogada direto pra
    // Recusada sem nunca aplicar, ver aplicarStatus() no JobController).
    long countByAppliedTrueAndRejectedTrue();

    @Transactional
    @Modifying
    @Query("DELETE FROM Job j WHERE j.rejected = true AND j.rejectedAt < :cutoff")
    int deleteByRejectedTrueAndRejectedAtBefore(LocalDateTime cutoff);

    // Só apaga vaga antiga que o usuário nunca engajou (nunca marcou
    // interesse/aplicou/recusou) e não fixou — protege qualquer coisa que
    // ele realmente tocou, mesmo antiga. postedAt nulo nunca bate (NULL <
    // cutoff é sempre falso em SQL), então vaga sem data conhecida não é
    // arriscada de apagar por engano.
    @Transactional
    @Modifying
    @Query("""
            DELETE FROM Job j WHERE j.postedAt < :cutoff
            AND j.interested = false AND j.applied = false AND j.rejected = false
            AND (j.favorited IS NULL OR j.favorited = false)
            """)
    int deleteOldUnengagedJobs(LocalDateTime cutoff);

    @Query("SELECT DISTINCT j.state FROM Job j WHERE j.state IS NOT NULL AND j.state <> '' ORDER BY j.state")
    List<String> findDistinctStates();

    @Query("SELECT DISTINCT j.source FROM Job j ORDER BY j.source")
    List<String> findDistinctSources();

    // Fase 6.1 — substituem o filtro em memória (findAll().stream().filter
    // embedding == null)) que existia quando o vetor morava dentro de Job.
    // Agora é um JOIN/NOT IN de verdade contra a tabela job_embeddings.
    @Query("SELECT j FROM Job j WHERE j.id NOT IN (SELECT je.id FROM JobEmbedding je)")
    List<Job> findAllNotEmbedded();

    @Query("SELECT COUNT(j) FROM Job j WHERE j.id NOT IN (SELECT je.id FROM JobEmbedding je)")
    long countNotEmbedded();

    /**
     * Fase 2.7 — painel de saúde das fontes. Agregado por fonte, calculado
     * dinamicamente (GROUP BY j.source) em vez de uma lista hardcoded de
     * fontes conhecidas — assim uma fonte nova (Greenhouse, Adzuna, SINE...)
     * aparece automaticamente no painel sem precisar editar essa query,
     * diferente do Map.of(...) fixo em getStats()/porFonte.
     */
    @Query("""
            SELECT j.source AS source, COUNT(j) AS total,
                   SUM(CASE WHEN j.salary IS NOT NULL THEN 1 ELSE 0 END) AS comSalario,
                   SUM(CASE WHEN j.state IS NOT NULL THEN 1 ELSE 0 END) AS comEstado,
                   MAX(j.postedAt) AS vagaMaisRecente,
                   MAX(j.fetchedAt) AS ultimoFetch
            FROM Job j
            WHERE j.rejected = false
            GROUP BY j.source
            ORDER BY j.source
            """)
    List<FonteSaudeProjection> saudeDasFontes();
}

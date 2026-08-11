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
}

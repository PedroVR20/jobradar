package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface JobRepository extends JpaRepository<Job, Long> {

    Optional<Job> findByUrl(String url);

    List<Job> findByFetchedAtAfter(LocalDateTime dateTime);

    List<Job> findBySource(String source);

    List<Job> findByAppliedTrue();

    List<Job> findBySeenFalse();

    long countBySource(String source);

    long countBySeenFalse();

    long countByAppliedTrue();

    long countBySeniority(String seniority);

    List<Job> findBySeniorityIsNull();

    List<Job> findByWorkplaceTypeIsNullAndSourceIn(List<String> sources);

    List<Job> findBySourceAndSalaryIsNullOrderByPostedAtDesc(String source, Pageable pageable);

    long countByAppliedTrueAndInProgressTrue();

    long countByRejectedTrue();

    @Transactional
    @Modifying
    @Query("DELETE FROM Job j WHERE j.rejected = true AND j.rejectedAt < :cutoff")
    int deleteByRejectedTrueAndRejectedAtBefore(LocalDateTime cutoff);

    @Query("SELECT DISTINCT j.state FROM Job j WHERE j.state IS NOT NULL AND j.state <> '' ORDER BY j.state")
    List<String> findDistinctStates();
}

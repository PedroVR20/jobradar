package br.com.jobradar.repository;

import br.com.jobradar.model.WeeklyDigest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface WeeklyDigestRepository extends JpaRepository<WeeklyDigest, Long> {
}

package br.com.jobradar.repository;

import br.com.jobradar.model.GmailToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface GmailTokenRepository extends JpaRepository<GmailToken, Long> {
}

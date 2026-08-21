package br.com.jobradar.repository;

import br.com.jobradar.model.JobEmbedding;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public interface JobEmbeddingRepository extends JpaRepository<JobEmbedding, Long> {

    // Fase 6.1 — limpeza periódica das linhas órfãs que bulk deletes de Job
    // deixam pra trás (ver comentário em JobEmbedding sobre por que não tem
    // FK formal). Chamado no mesmo ciclo das outras limpezas periódicas em
    // JobAggregatorService.
    @Transactional
    @Modifying
    @Query("DELETE FROM JobEmbedding je WHERE je.id NOT IN (SELECT j.id FROM Job j)")
    int deleteOrphans();
}

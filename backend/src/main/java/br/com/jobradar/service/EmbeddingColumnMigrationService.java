package br.com.jobradar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Fase 6.1 — migração pontual, roda uma vez só. Não tem Flyway ainda (Fase
 * 5.3), então essa é a forma manual de fazer o que {@code ddl-auto: update}
 * não sabe fazer sozinho: mover dado de uma coluna antiga pra tabela nova e
 * DEPOIS apagar a coluna antiga (Hibernate cria/ajusta colunas que faltam,
 * mas nunca remove uma que sobrou — se não fizesse isso na mão, os 125 MB
 * do embedding antigo continuariam ocupando espaço em {@code jobs} pra
 * sempre, mesmo com o código já lendo/escrevendo só em
 * {@code job_embeddings}).
 *
 * <p>Idempotente: a primeira coisa que faz é checar se a coluna antiga
 * ainda existe — se não existir (já migrado numa subida anterior), não faz
 * nada. Seguro rodar em toda inicialização.</p>
 */
@Service
@Slf4j
public class EmbeddingColumnMigrationService {

    @PersistenceContext
    private EntityManager em;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrarEmbeddingParaTabelaPropria() {
        boolean colunaAntigaExiste = ((Number) em.createNativeQuery(
                "SELECT count(*) FROM information_schema.columns WHERE table_name = 'jobs' AND column_name = 'embedding'")
                .getSingleResult()).intValue() > 0;

        if (!colunaAntigaExiste) {
            return; // já migrado numa subida anterior — nada a fazer
        }

        log.info("=== Migração 6.1: coluna jobs.embedding ainda existe, migrando pra job_embeddings ===");

        int copiadas = em.createNativeQuery(
                "INSERT INTO job_embeddings (id, vector) " +
                        "SELECT id, embedding FROM jobs " +
                        "WHERE embedding IS NOT NULL " +
                        "AND NOT EXISTS (SELECT 1 FROM job_embeddings je WHERE je.id = jobs.id)")
                .executeUpdate();

        em.createNativeQuery("ALTER TABLE jobs DROP COLUMN embedding").executeUpdate();

        log.info("=== Migração 6.1 concluída: {} embeddings movidos, coluna jobs.embedding removida " +
                "(recupera ~125MB da tabela jobs) ===", copiadas);
    }
}

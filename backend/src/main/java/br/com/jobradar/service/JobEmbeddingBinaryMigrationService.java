package br.com.jobradar.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Session;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * Fase 6.5 — migração pontual, mesmo padrão de {@link EmbeddingColumnMigrationService}
 * (roda uma vez, checa se já foi feita antes de mexer em qualquer coisa).
 * {@code job_embeddings.vector} era TEXT decimal separado por vírgula
 * (~39 mil caracteres por vaga); o campo Java virou {@code byte[]}/bytea
 * (Fase 6.5, ver {@link br.com.jobradar.model.JobEmbedding}) — {@code
 * ddl-auto: update} cria coluna que falta, mas nunca troca o TIPO de uma
 * coluna que já existe, então sem essa migração o Hibernate ia tentar ler
 * bytes de uma coluna ainda TEXT e quebrar na primeira query.
 *
 * <p>Estratégia: coluna nova {@code vector_bin bytea} ao lado da antiga,
 * copia linha por linha reempacotando o texto em binário (via JDBC puro,
 * não a entidade — a entidade já está mapeada pro nome final "vector" com
 * tipo bytea, então usar ela aqui leria a coluna errada), depois dropa a
 * antiga e renomeia a nova. Tudo numa transação só.</p>
 *
 * <p><b>Janela de corrida:</b> {@link JobAggregatorService#fetchNaInicializacao}
 * roda em {@code @Async} a partir do mesmo evento — teoricamente uma vaga
 * nova poderia tentar {@code embedESalvar} enquanto essa migração ainda
 * está copiando dado antigo. Mesmo risco que já existia em
 * {@link EmbeddingColumnMigrationService} desde a Fase 6.1, aceito pelo
 * mesmo motivo: se acontecer, aquela vaga específica só loga um warning e
 * fica sem embedding até o próximo fetch periódico ou backfill manual pegar
 * ela — não trava nem corrompe nada (ver {@code embedESalvar}, falha
 * silenciosa de propósito).</p>
 */
@Service
@Slf4j
public class JobEmbeddingBinaryMigrationService {

    @PersistenceContext
    private EntityManager em;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void migrarVetorParaBinario() {
        String tipoAtual = (String) em.createNativeQuery(
                "SELECT data_type FROM information_schema.columns " +
                        "WHERE table_name = 'job_embeddings' AND column_name = 'vector'")
                .getResultStream().findFirst().orElse(null);

        if (tipoAtual == null || "bytea".equals(tipoAtual)) {
            return; // tabela nova (já nasce bytea) ou já migrado numa subida anterior
        }

        log.info("=== Migração 6.5: job_embeddings.vector ainda é {}, convertendo pra bytea ===", tipoAtual);

        Session session = em.unwrap(Session.class);
        int[] migradas = {0};
        session.doWork(connection -> {
            try (Statement alter = connection.createStatement()) {
                alter.execute("ALTER TABLE job_embeddings ADD COLUMN IF NOT EXISTS vector_bin bytea");
            }

            try (PreparedStatement select = connection.prepareStatement("SELECT id, vector FROM job_embeddings");
                 ResultSet rs = select.executeQuery();
                 PreparedStatement update = connection.prepareStatement(
                         "UPDATE job_embeddings SET vector_bin = ? WHERE id = ?")) {
                int lote = 0;
                while (rs.next()) {
                    long id = rs.getLong("id");
                    String vetorTexto = rs.getString("vector");
                    if (vetorTexto == null || vetorTexto.isBlank()) continue;

                    String[] partes = vetorTexto.split(",");
                    float[] vetor = new float[partes.length];
                    for (int i = 0; i < partes.length; i++) vetor[i] = Float.parseFloat(partes[i]);

                    update.setBytes(1, JobEmbeddingService.serializar(vetor));
                    update.setLong(2, id);
                    update.addBatch();
                    migradas[0]++;
                    if (++lote % 500 == 0) update.executeBatch();
                }
                update.executeBatch();
            }

            try (Statement finalize = connection.createStatement()) {
                finalize.execute("ALTER TABLE job_embeddings ALTER COLUMN vector_bin SET NOT NULL");
                finalize.execute("ALTER TABLE job_embeddings DROP COLUMN vector");
                finalize.execute("ALTER TABLE job_embeddings RENAME COLUMN vector_bin TO vector");
            }
        });

        log.info("=== Migração 6.5 concluída: {} vetores convertidos pra bytea (texto -> binário, ~68% menor) ===", migradas[0]);
    }
}

package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * Fase 6.1 — vetor de embedding (busca semântica, ver
 * {@code JobEmbeddingService}) extraído de {@link Job} pra tabela própria.
 * Antes vivia como TEXT direto no Job e era a causa raiz de um
 * OutOfMemoryError real: 125 dos 166 MB da tabela {@code jobs} eram só esse
 * campo (~39 mil caracteres por vaga), e QUALQUER query que devolvesse Job
 * — incluindo a listagem principal sem filtro nenhum — arrastava ele junto,
 * mesmo quando ninguém ia usar o vetor.
 *
 * <p>{@code id} é o mesmo valor de {@code Job.id}, mas SEM constraint de FK
 * formal de propósito: algumas rotinas de limpeza apagam {@code Job} via
 * {@code DELETE} em lote (JPQL bulk delete, ver
 * {@code JobRepository.deleteByRejectedTrueAndRejectedAtBefore} e
 * similares) — bulk delete não dispara cascade de JPA, e uma FK rígida
 * faria essas limpezas quebrarem com violação de integridade referencial
 * assim que a primeira vaga com embedding fosse apagada dessa forma. A
 * troca é aceitar que uma linha órfã pode sobrar temporariamente — por
 * isso existe {@link br.com.jobradar.repository.JobEmbeddingRepository#deleteOrphans()},
 * chamado no ciclo periódico de limpeza (ver JobAggregatorService), que
 * varre e remove o que sobrou sem dono.</p>
 */
@Entity
@Table(name = "job_embeddings")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobEmbedding {

    @Id
    private Long id; // = Job.id correspondente

    // Fase 6.5 — era TEXT decimal separado por vírgula (~39 mil caracteres
    // por vaga: Double.toString() de 3072 floats, incluindo notação
    // científica tipo "7.285421E-4"). Virou bytea com os 3072 floats
    // empacotados em binário de 4 bytes cada (~12 KB por vaga) — reduz o
    // tamanho médio da linha em ~68% (medido: 141 MB → ~46 MB pras 5900+
    // vagas existentes). Ver JobEmbeddingService.serializar/parsear pro
    // empacotamento, e JobEmbeddingBinaryMigrationService pra migração do
    // dado que já existia em texto.
    @Column(columnDefinition = "bytea", nullable = false)
    private byte[] vector;
}

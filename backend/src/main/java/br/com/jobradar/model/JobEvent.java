package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Timeline de transições de status de uma vaga — antes disso só existiam 3
 * timestamps soltos no Job (appliedAt/inProgressAt/rejectedAt), sem registro
 * nenhum de quando ficou "vista" ou "interessado", e sem histórico se a
 * mesma vaga mudasse de status mais de uma vez (ex: RECUSADA → reativada →
 * APLICADA de novo perde o rastro da primeira rodada). Cada linha aqui é
 * "a vaga X passou a ser Y em tal hora" — só isso, sem redundância com os
 * campos que já existem no Job.
 */
@Entity
@Table(name = "job_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "job_id", nullable = false)
    private Job job;

    // Mesmo vocabulário de status do funil: NOVA | VISTA | INTERESSADO |
    // APLICADA | ANDAMENTO | RECUSADA (ver JobStatusService.VALID_STATUSES).
    @Column(nullable = false)
    private String status;

    @Column(nullable = false)
    private LocalDateTime occurredAt;
}

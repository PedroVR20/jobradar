package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDateTime;

/**
 * Fase 3.5 — resumo semanal gerado por IA (o que entrou, o que está
 * parado, prazos fechando). Singleton (id sempre 1) — mesmo padrão de
 * {@code GmailToken}: app pessoal de um usuário só, sem necessidade de
 * histórico multi-semana por ora (cada geração nova sobrescreve a
 * anterior). Se um histórico virar necessário no futuro, isso vira uma
 * tabela normal (tirar o id fixo, indexar por geradoEm) sem quebrar nada
 * que já existe.
 */
@Entity
@Table(name = "weekly_digests")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WeeklyDigest {

    @Id
    private Long id; // sempre 1L

    @Column(columnDefinition = "TEXT", nullable = false)
    private String conteudo;

    private LocalDateTime geradoEm;

    // Números crus por trás do texto (pro frontend mostrar um resuminho
    // estruturado além do texto corrido, sem re-parsear o conteúdo gerado).
    private int vagasNovas;
    private int vagasParadas;
    private int prazosProximos;
}

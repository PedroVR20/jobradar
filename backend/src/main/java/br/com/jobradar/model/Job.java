package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "jobs")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String company;

    @Column(nullable = false, unique = true, length = 1000)
    private String url;

    @Column(nullable = false)
    private String source; // REMOTIVE | ARBEITNOW | WWR | GUPY

    private String seniority; // ESTAGIO | JUNIOR | PLENO | SENIOR | NAO_INFORMADO

    private String salary;

    private String workplaceType; // REMOTO | HIBRIDO | PRESENCIAL

    private String state; // estado brasileiro por extenso, ex: "São Paulo" (só quando a fonte informa)

    private String city;

    @Column(columnDefinition = "TEXT")
    private String tags; // comma-separated: "java,spring-boot,remote"

    private LocalDateTime postedAt;

    // Data limite de inscrição. Só preenchido por fontes que informam prazo (ex: Gupy).
    private LocalDate expiresAt;

    private LocalDateTime fetchedAt;

    @Builder.Default
    private boolean seen = false;

    @Builder.Default
    private boolean applied = false;

    // true = aplicou e está em processo seletivo ativo (entrevistas etc),
    // separado de "aplicada" pra não misturar com vagas que só foram aplicadas
    // e ainda não tiveram retorno.
    @Builder.Default
    private boolean inProgress = false;

    // true = vaga recusada (processo encerrado sem sucesso) ou vaga congelada
    // pela empresa. rejectedAt marca quando isso aconteceu, usado pra excluir
    // a vaga automaticamente depois de alguns dias (ver JobAggregatorService).
    @Builder.Default
    private boolean rejected = false;

    private LocalDateTime rejectedAt;
}

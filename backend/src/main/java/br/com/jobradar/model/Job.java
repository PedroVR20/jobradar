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

    // Quando a checagem de detalhe da Gupy foi feita por último pra tentar
    // achar salário (ver JobAggregatorService.enriquecerSalariosGupyAntigas)
    // — null = nunca checada ainda. BUG REAL corrigido (Fase 1.3): sem esse
    // marcador, o backfill sempre pegava as N vagas mais recentes SEM
    // salário (ORDER BY postedAt DESC) — se essas nunca tiverem salário
    // divulgado (comum), o backfill martelava as MESMAS vagas todo ciclo
    // pra sempre e nunca avançava pras milhares de outras vagas antigas
    // nunca checadas. Com o marcador, "nunca checada" tem prioridade sobre
    // "já checada e não achou", garantindo que o backfill avança pelo
    // catálogo inteiro em vez de ficar preso no mesmo lote.
    private LocalDateTime salaryCheckedAt;

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

    // true = vaga vista que despertou interesse, mas ainda não foi aplicada —
    // fica na aba "Interessado" pra não se perder junto com as demais "já vistas".
    @Builder.Default
    private boolean interested = false;

    @Builder.Default
    private boolean applied = false;

    // Marca a primeira vez que a vaga foi aplicada — não é sobrescrito em reaplicações
    // (ver aplicarStatus). Usado pra métricas de tempo/volume ao longo do tempo.
    private LocalDateTime appliedAt;

    // true = aplicou e está em processo seletivo ativo (entrevistas etc),
    // separado de "aplicada" pra não misturar com vagas que só foram aplicadas
    // e ainda não tiveram retorno.
    @Builder.Default
    private boolean inProgress = false;

    // Marca a primeira vez que a vaga entrou em processo ativo.
    private LocalDateTime inProgressAt;

    // true = vaga recusada (processo encerrado sem sucesso) ou vaga congelada
    // pela empresa. rejectedAt marca quando isso aconteceu, usado pra excluir
    // a vaga automaticamente depois de alguns dias (ver JobAggregatorService).
    @Builder.Default
    private boolean rejected = false;

    private LocalDateTime rejectedAt;

    // URL do logo da empresa (vem da Gupy via careerPageLogo e da Eureca via companyLogoUrl)
    @Column(length = 500)
    private String companyLogoUrl;

    // true = vaga com ação afirmativa para PcD (vem da Gupy/Eureca quando informado)
    private Boolean pcd;

    // Favorito pessoal — marcado manualmente pelo usuário
    private Boolean favorited;

    // Anotações pessoais do usuário sobre essa vaga
    @Column(columnDefinition = "TEXT")
    private String notes;

    // true = a senioridade/stack dessa vaga foram resolvidos pela IA (Gemini)
    // porque o regex (SeniorityClassifier) não conseguiu decidir pelo título —
    // usado só pra transparência visual no card (badge "🤖"), não afeta lógica.
    private Boolean classifiedByAi;

    // Vetor de embedding (768 floats, gemini-embedding-001) serializado como
    // números separados por vírgula — usado pra busca semântica (ver
    // JobEmbeddingService). Sem extensão pgvector instalada no Postgres, a
    // similaridade de cosseno é calculada em Java sobre a lista de vagas, não
    // em SQL — por isso um TEXT simples já basta, não precisa de tipo de
    // coluna especial. Null até a vaga ser embeddada (vagas antigas ficam
    // assim até o backfill rodar, ver POST /api/jobs/admin/backfill-embeddings).
    @Column(columnDefinition = "TEXT")
    private String embedding;
}

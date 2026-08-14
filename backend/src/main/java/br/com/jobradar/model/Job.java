package br.com.jobradar.model;

import jakarta.persistence.*;
import lombok.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

// Fase 6.2 — a tabela só tinha PK e a unique de url. Nenhuma das colunas
// mais filtradas (funil novas/recusadas, ordenação por data, filtro por
// fonte/estado, corte de prazo) tinha índice — em ~5900 linhas o seq scan
// ainda é barato, mas o custo de criar agora é de segundos e evita que
// isso vire gargalo real conforme o catálogo cresce.
//
// Fase 12 — a lista abaixo é só DOCUMENTAÇÃO agora, não fonte de verdade:
// desde a Fase 5.3 (Flyway) o schema real é criado por
// db/migration/V*.sql, e o Hibernate em modo `validate` não confere nem
// cria índice nenhum (só tabela/coluna/tipo) — um @Index daqui sem a
// migração correspondente simplesmente não existe no banco. A verdade de
// hoje, criada em V2__indices_fase_12.sql:
//   - idx_jobs_seen_true_rejected: parcial (WHERE seen = true), não mais
//     composto sem condição — o composto media 97% de seletividade pro
//     caso mais comum (aba Novas, seen=false) e o planner corretamente
//     nunca usava; a fatia de vaga JÁ VISTA é que é rara e vale indexar.
//   - idx_jobs_state: virou funcional, sobre lower(unaccent(state)) — o
//     índice antigo em state cru nunca era alcançado porque o filtro de
//     estado sempre rodou em Java (Fase 5.5, ignora acento); Fase 12.1
//     moveu esse filtro pra SQL (ver JobSpecifications.byState), e agora
//     o índice bate com a expressão que a query de verdade usa.
//   - idx_jobs_fetched_at (novo): usada em /stats, /new-since e limpeza
//     periódica, esquecida na Fase 6.2 original.
//   - idx_jobs_posted_at, idx_jobs_source, idx_jobs_expires_at: mantidas
//     como estavam.
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

    // Fase 6.1 — o vetor de embedding (768 floats, ~39 mil caracteres
    // serializados) morava aqui como TEXT e era a causa raiz de um
    // OutOfMemoryError real em produção: 125 dos 166 MB da tabela inteira
    // eram só esse campo, e QUALQUER query que devolvesse Job (incluindo a
    // listagem principal, sem filtro nenhum) arrastava ele junto — mesmo
    // quando ninguém ia usar o vetor. Movido pra tabela própria
    // job_embeddings (ver JobEmbedding/JobEmbeddingRepository), correlacionada
    // por id sem FK formal (ver comentário na entidade nova sobre o porquê).
}

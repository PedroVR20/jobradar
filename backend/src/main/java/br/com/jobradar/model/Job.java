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
    // job_embeddings (ver JobEmbeddingService/JobEmbeddingRepository), correlacionada
    // por id sem FK formal (ver comentário na entidade nova sobre o porquê).

    // Fase 7.1 — null = nunca classificada (vaga anterior a essa feature, ou
    // fonte cujo classificador falhou); true = fora de área de tecnologia
    // (contabilidade, engenharia civil, etc — ver RelevanceClassifier);
    // false = relevante. Marca, não descarta: o veredito fica visível e
    // reversível (ver JobAdminController), diferente de simplesmente não
    // trazer a vaga pro banco.
    private Boolean foraDeArea;

    // Fase 7.3 + 8.4 — arquivamento automático e reversível de vaga que
    // envelheceu sem engajamento real (mesmo critério de
    // JobRepository.deleteOldUnengagedJobs: nunca interessou/aplicou/
    // recusou/favoritou) — sai do caminho da triagem sem ser apagada, ao
    // contrário da exclusão definitiva aos 730 dias que já existia. archivedReason
    // diz o motivo ("IDADE" ou "NUNCA_VISTA", ver JobArchivalService) — útil
    // pra decidir se vale reativar.
    @Builder.Default
    private boolean archived = false;
    private LocalDateTime archivedAt;
    private String archivedReason;

    // Fase 7.5 — null = link nunca checado. true = HTTP 404/redirect pra
    // home da empresa detectado (sinal forte de vaga encerrada, nenhuma
    // fonte informa isso de forma confiável). false = link respondeu ok da
    // última vez que foi checado. Ver JobLinkCheckerService — checagem é
    // amostrada e limitada por ciclo, não verifica toda vaga toda vez.
    private Boolean linkMorto;
    private LocalDateTime linkCheckedAt;

    // Fase 7.6 — nome de empresa "canônico" (minúsculo, sem acento, sem
    // sufixo tipo LTDA/S.A.) usado SÓ pra agrupar (dedup, histórico por
    // empresa, painel de fontes) — o campo `company` continua com o nome
    // original pra exibição. Sem isso "Start Recrutamento e Treinamento
    // LTDA" e variações contam como empregadores diferentes.
    private String companyNormalized;

    // Fase 8.7 — motivo estruturado da recusa (SALARIO | LOCALIDADE |
    // SENIORIDADE | STACK | EMPRESA | OUTRO), opcional — null quando a
    // recusa foi feita antes dessa feature existir, ou o usuário pulou o
    // passo. PersonalRankingService usa isso pra aprender só da dimensão
    // certa em vez de penalizar TODAS as features da vaga por igual quando
    // o motivo real era só o salário, por exemplo.
    private String rejectedReason;

    // Fase 9.4 — extração de estrutura da descrição via IA (ver
    // JobStructureExtractorService), sob demanda e cacheada — mesma ideia do
    // salaryCheckedAt acima: estruturaExtraidaEm marca "já tentei" (mesmo
    // quando a extração não achou nada útil), pra nunca gastar uma segunda
    // chamada de Gemini na mesma vaga. Comma-separated pras listas (mesmo
    // padrão de `tags` acima), não JSON — o resto do schema não usa colunas
    // JSON, não vale introduzir um padrão novo só aqui.
    @Column(columnDefinition = "TEXT")
    private String requisitosObrigatorios;
    @Column(columnDefinition = "TEXT")
    private String requisitosDesejaveis;
    private Integer anosExperienciaMin;
    private String escolaridadeRequerida;
    @Column(columnDefinition = "TEXT")
    private String beneficios;
    private LocalDateTime estruturaExtraidaEm;
}

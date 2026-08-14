# Arquitetura — Job Radar

> Este documento explica **como o sistema é organizado e por quê**. Para
> "como rodar o projeto" e a lista de features, ver [README.md](README.md).
> Aqui o foco é a estrutura interna: camadas, decisões técnicas e os
> trade-offs por trás delas — para alguém (humano ou agente de IA) que
> precise mexer no código entender o raciocínio antes de mudar algo.
>
> Convenção usada nos comentários do código e aqui: **"Fase N.M"** marca em
> qual etapa do projeto uma decisão foi tomada — útil pra rastrear o
> histórico de "por que isso é assim" (`git log --grep "Fase"` mostra o
> commit exato).

## Visão geral

```
┌─────────────┐      HTTP/JSON       ┌──────────────────┐      JDBC       ┌────────────┐
│   frontend   │ ───────────────────▶ │      backend      │ ───────────────▶ │ PostgreSQL │
│ React+Vite   │ ◀─────────────────── │  Spring Boot 3.3   │ ◀─────────────── │     16     │
│  (nginx)     │     SSE (chat)       │     Java 21         │                  └────────────┘
└─────────────┘                      └─────────┬─────────┘
                                                 │ HTTPS
                                    ┌────────────┼────────────┐
                                    ▼            ▼            ▼
                              Gemini API   ~10 fontes de   Gmail API
                              (Hunter/IA)    vagas (fetch)  (OAuth)
```

Três containers via `docker-compose.yml` (`postgres` / `backend` / `frontend`),
sem fila de mensagens, sem cache distribuído, sem service mesh — é um app
pessoal de um usuário só, e complexidade de infraestrutura que serviria uma
equipe de 50 pessoas seria puro custo aqui sem benefício. Ver
[`README.md`](README.md#-como-rodar-docker--1-comando) pra como subir.

---

## Backend: camadas

```
Controller  →  Service  →  Repository  →  PostgreSQL
(HTTP)         (regra)      (Spring Data JPA)
```

Sem camada de DTO de request/response formal em todo lugar — os endpoints
mais simples (CRUD de vaga) ainda montam `Map<String,Object>` inline
(`JobController.toDto`), e só os que realmente precisavam de contrato
tipado (listagem paginada, Fase 6.4) ganharam `record` dedicados em
`dto/`. Consistência total teria exigido reescrever endpoints que já
funcionavam bem sem isso — não valeu o esforço.

### Os quatro controllers

`JobController` cresceu para 1420 linhas até a Fase 5.1 quebrar em três,
por responsabilidade — sem mudar nenhuma URL (todos continuam em
`@RequestMapping("/api/jobs")`):

| Controller | Responsabilidade | Chamado por |
|---|---|---|
| `JobController` | CRUD/listagem/dashboard de vaga (stats, metrics, duplicates, marcar status, fetch manual, export, pin, notes) | Frontend, fluxo normal |
| `JobAdminController` | Manutenção: backfill de embedding, saúde das fontes, retreino do modelo de salário, backup manual | Botões de ⚙️ Configurações |
| `AssistantController` | Tudo que chama o Gemini por vaga (carta, match-score, plano de aprendizado) e o chat livre do Hunter (síncrono + SSE) | Modais de IA, chat do Hunter |
| `GmailController` | OAuth do Gmail + varredura de emails de vaga | Integração opcional Gmail |
| `WeeklyDigestController` | Consulta o digest semanal já gerado | Frontend (card de digest) |

### `JobQueryService` — listagem, filtro, ordenação e paginação (Fase 5.5 + 6.3)

Extraído do controller porque a lógica é grande o bastante pra merecer
teste isolado sem precisar de `MockMvc`. A decisão mais importante aqui:

**Paginação é feita em memória Java, não `LIMIT`/`OFFSET` de SQL.** Os
filtros de comparação direta (fonte, senioridade, data, status) viram
`Specification<Job>` e cortam a maioria das vagas via `WHERE` de SQL antes
de qualquer coisa chegar em Java — mas a busca textual multi-termo
(ignora acento: "sao paulo" acha "São Paulo"), a comparação de estado, e o
ranking pessoal aprendido (Fase 3.1, `PersonalRankingService`) são
calculados em Java. Paginar em SQL **antes** desses filtros devolveria
página incompleta ou errada. A Fase 6.3 mediu o problema real antes de
resolver: o catálogo inteiro filtrado (4,37 MB sem filtro nenhum) viajava
pra tela mostrar 30 itens — o ganho de paginar é na rede, não em CPU do
banco, então fatiar a lista já filtrada/ordenada resolve o problema real
sem precisar da extensão `unaccent` do Postgres pra mover a busca textual
pra SQL.

### Multi-fonte: `JobSource` + ~10 implementações

Cada fonte de vaga (Remotive, Arbeitnow, WWR, Gupy, Eureca,
QueroVagasTech, Nerdin, Greenhouse, SINE Aberto...) é um
`XxxService` isolado — se uma quebrar (mudança de API não documentada,
como já aconteceu com a Eureca), as outras continuam funcionando.
`JobAggregatorService` orquestra o fetch periódico
(`@Scheduled(cron = "0 0 */2 * * *")`, a cada 2h), dedup, classificação
de senioridade (`SeniorityClassifier`, regex), extração de salário
(`SalaryExtractor`), backfill de embedding pra vaga nova, e limpeza
periódica (vaga recusada há mais de 7 dias, embedding órfão).

### Hunter (assistente de IA): function-calling de verdade

`JarvisChatService` não é um prompt fixo com resposta livre — o Gemini
recebe uma lista de **ferramentas declaradas** (`JarvisToolDeclarations`,
~28 delas: listar vagas, resumo de funil, compatibilidade, marcar status,
buscar por significado semântico, criar lembrete na Agenda, etc.) e decide
sozinho quais chamar a partir da pergunta em linguagem natural. Ferramentas
de leitura reaproveitam os services que já existem (não duplicam lógica);
ferramentas de escrita ficam isoladas em `JarvisWriteTools` (separação
deliberada: é mais fácil auditar "o que a IA pode mudar no banco" numa
classe só). `POST /assistant/chat` devolve tudo de uma vez;
`POST /assistant/chat/stream` narra cada chamada de ferramenta em tempo
real via Server-Sent Events — o mesmo `conversar()` por trás dos dois,
SSE é aditivo, não substituiu o síncrono.

### Busca semântica: embedding fora do caminho quente (Fase 6.1 + 6.5)

`JobEmbedding` é uma tabela própria (`job_embeddings`), não um campo em
`Job` — foi a causa raiz de um `OutOfMemoryError` real: o vetor
(3072 dimensões do `gemini-embedding-001`) ocupava ~39 mil caracteres por
vaga como texto, e qualquer listagem sem filtro carregava ele junto pra
TODA vaga, mesmo quando ninguém ia usar o vetor. Duas correções
sucessivas, cada uma resolvendo um problema diferente:

1. **Fase 6.1** — tirar o campo de dentro de `Job` (não carrega mais
   "de graça" em toda query de listagem).
2. **Fase 6.5** — trocar o formato de texto decimal por `bytea` binário
   (4 bytes por float, não ~13 caracteres) — reduz o tamanho do dado em
   si, não só onde ele mora. Medido: 141 MB → 82 MB pra ~5900 vagas.

Sem extensão `pgvector`: a similaridade de cosseno é calculada em Java
sobre a lista de vagas candidatas (`JobEmbeddingService.buscar`) — nessa
escala (~6 mil vagas × 3072 dimensões) é um produto escalar trivial pra
JVM, não precisa de banco vetorial de verdade. `JobEmbedding` também não
tem FK formal pra `Job` de propósito: rotinas de limpeza fazem bulk
delete via JPQL, que não dispara cascade — uma FK rígida quebraria a
limpeza na primeira vaga com embedding apagada em lote. A troca é aceitar
que uma linha pode ficar órfã temporariamente, varrida por
`JobEmbeddingRepository.deleteOrphans()` no ciclo periódico.

### Schema: Flyway, não mais `ddl-auto: update` (Fase 5.3)

Até a Fase 5.3, o schema inteiro era gerenciado pelo Hibernate
(`ddl-auto: update`) — sem histórico revisável além do git log dos
`@Entity`. Migração pro Flyway foi feita com `baseline-on-migrate`:
`V1__baseline.sql` (schema real, gerado via `pg_dump --schema-only`) não
roda contra o banco de produção existente — é só marcado como "já
aplicado". Um banco novo/vazio (CI, ambiente novo) roda o V1 de verdade e
chega no mesmo estado — testado explicitamente antes de aplicar em
produção (ver commit da Fase 5.3 pro passo a passo de verificação usado).

**Regra daqui pra frente: mudança de schema é sempre uma migração nova**
(`V2__descricao.sql`, `V3__...`, em
`backend/src/main/resources/db/migration/`), nunca só editar um `@Entity`
e confiar que "vai funcionar". `spring.jpa.hibernate.ddl-auto` agora é
`validate` — o Hibernate só CONFERE que as entidades batem com o schema
real no boot (erro alto e claro se uma migração ficou faltando), nunca
mais altera nada sozinho.

### Backup automático (Fase 5.4)

`BackupService` roda `pg_dump -F c` (formato custom, comprimido, restore
seletivo com `pg_restore`) diariamente às 4h via `@Scheduled`, escrevendo
em `/app/backups` (bind-mount `./backups`, fora do volume nomeado do
próprio Postgres de propósito — os dois vivendo juntos não protegem
contra um problema de Docker/disco que leve os dois embora). Retenção de
14 dias. `POST /api/jobs/admin/backup` dispara na hora, sem esperar o
cron — usado antes de qualquer migração arriscada (é assim que os backups
manuais das Fases 6.1/6.5/5.3 foram feitos, antes de virar automático).

### Memória do container (Fase 6.6)

`-Xmx` do JVM é medido, não chutado. A causa raiz do OOM (embedding
inflando toda listagem) foi corrigida nas Fases 6.1/6.3/6.5 — o heap
provisoriamente elevado pra 2g (tampão de emergência da Fase 1) foi
revertido pra 1g depois de medir o pico real de uso (`jcmd GC.heap_info`)
sob o pior caso que sobrou sem paginação (`/api/jobs/export`). Ver
comentário no `Dockerfile` pros números exatos.

---

## Frontend

```
App.tsx (orquestra abas/filtros/modais)
  ├── useJobs.ts          — fetch paginado, estado otimista, cache local
  ├── FilterBar / ViewTabs / JobCard / StatsBar
  ├── ~10 modais (carta, match-score, retreino, triagem...)
  └── JarvisPanel.tsx     — chat do Hunter
        └── jarvis/ToolResultCards.tsx — renderização de cada ferramenta
```

Sem Redux/Zustand — estado vive em hooks (`useJobs`, `useAgenda`,
`useAiFeedback`, etc.) e sobe até `App.tsx`, que é grande mas é o único
lugar que precisa saber "qual aba, qual filtro, qual modal está aberto" —
introduzir uma lib de estado global pra um app de um usuário só seria
complexidade sem retorno.

### `JarvisPanel.tsx` — split por responsabilidade (Fase 5.2)

Chegou a 3034 linhas misturando orquestração do chat (estado de
conversas, streaming SSE, histórico, input de voz/imagem, slash
commands) com a renderização do resultado de cada uma das ~28
ferramentas do Hunter. Extraído: `jarvis/ToolResultCards.tsx` (1178
linhas) fica só com os componentes de renderização — `ListarVagasCard`,
`SalarioCard`, `CompatibilidadeCard`, etc., e o dispatcher
`ToolResultCard` que decide qual usar a partir do nome da ferramenta que
voltou do backend. `JarvisPanel.tsx` (1881 linhas) fica com tudo que é
"conversa" — não sabe nada sobre como desenhar o resultado de uma
ferramenta específica.

### Paginação real (Fase 6.3)

`useJobs.ts` busca uma página por vez (`page`/`size` na query string,
mesmo contrato do `JobQueryService` no backend) e acumula em estado —
"carregar mais" dispara uma busca de página nova de verdade, não revela
mais itens de um array que já tinha chegado inteiro. Ações otimistas
(marcar vista, aplicada, pin) não podem só recarregar a página 0 — isso
descartaria páginas extras que o usuário já tinha carregado — por isso
`reloadPaginasCarregadas()` busca e concatena todas as páginas já vistas
até agora.

---

## Decisões que valem registrar (por que NÃO foi feito diferente)

- **Sem service mesh/fila/cache distribuído** — um usuário só, três
  containers já bastam. Complexidade proporcional ao problema real.
- **Sem `pgvector`** — ~6 mil vagas cabe em produto escalar de Java puro;
  a extensão resolveria um problema de escala que este projeto não tem.
- **Paginação em memória, não SQL puro** — porque os filtros que
  precisam rodar antes (busca textual sem acento, ranking pessoal) são
  Java, não SQL. Documentado com essa justificativa explícita em
  `JobQueryService` pra não parecer "esqueceram de otimizar".
- **`JobEmbedding` sem FK formal** — bulk delete JPQL não dispara
  cascade; FK rígida quebraria limpeza periódica. Trade-off aceito:
  linha órfã temporária, varrida por rotina própria.
- **Zero fila de mensagens pro Hunter** — chat síncrono ou SSE direto;
  não tem volume que justifique desacoplar via fila.
- **Sem Redis/cache externo** — `SimpleTtlCache` (em memória, no próprio
  processo) resolve o único caso que precisava de cache (respostas
  determinísticas do Hunter, Fase 3.4) sem mais um serviço pra manter no
  ar.

---

## Testes

`mvn test` (backend) cobre lógica pura — extratores de salário/senioridade,
dispatcher de ferramentas do Hunter, serialização do embedding — não
`MockMvc` de endpoint por endpoint. Verificação de integração (schema
real, endpoints reais, migração de dado real) é feita manualmente contra
Docker antes de cada commit que mexe em algo sensível (banco, schema,
memória) — não automatizada ainda; ver `.github/workflows/` pro que roda
no CI hoje (compile + testes + `tsc` + build do Docker, sem um banco real
subindo).

---

**Ver também:** [README.md](README.md) (como rodar, features, API) ·
[REPOSITORY_OVERVIEW.md](REPOSITORY_OVERVIEW.md) (snapshot antigo,
desatualizado desde antes da Fase 1 — mantido só por referência histórica).

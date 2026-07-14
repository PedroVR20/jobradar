# 🎯 Job Radar

Dashboard pessoal para monitorar vagas de programação remotas na Europa **e**
vagas no Brasil (Itaú, Stone, Localiza, Boticário, TIM e centenas de outras
empresas via Gupy).
Busca automaticamente todo dia às **08:00 BRT** e guarda tudo no banco.

---

## 🚀 Como rodar (Docker — 1 comando)

```bash
docker-compose up --build
```

Aguarda uns 2-3 minutos para o Maven baixar as dependências e compilar o backend.

| Serviço   | Endereço                   |
|-----------|----------------------------|
| Dashboard | http://localhost:3000      |
| Backend   | http://localhost:8080      |
| Banco     | localhost:5432 / jobradar  |

---

## ⚠️ Adicionando um novo campo boolean/NOT NULL no `Job`

O projeto usa `spring.jpa.hibernate.ddl-auto: update`, que só sabe fazer
`ALTER TABLE ... ADD COLUMN`. Isso quebra o backend (loop de crash) se o
campo novo for um `boolean`/`int` primitivo **sem valor padrão** e a tabela
`jobs` já tiver linhas — o Postgres recusa `NOT NULL` sem `DEFAULT` numa
tabela populada. Foi exatamente isso que aconteceu ao adicionar `inProgress`.

Pra evitar isso: depois de adicionar o campo no `Job.java`, rode o `ALTER
TABLE` manualmente com `DEFAULT` **antes** de subir o backend:
```sql
ALTER TABLE jobs ADD COLUMN nome_da_coluna boolean NOT NULL DEFAULT false;
```
Ou use `Boolean` (wrapper, aceita null) em vez de `boolean` primitivo.

---

## 🔧 Como rodar em desenvolvimento (sem Docker)

### Pré-requisitos
- Java 21+
- Maven 3.9+
- Node 20+
- PostgreSQL rodando local

### Backend
```bash
cd backend
mvn spring-boot:run
# Sobe em http://localhost:8080
```

### Frontend
```bash
cd frontend
npm install
npm run dev
# Sobe em http://localhost:5173
# O Vite já faz proxy de /api → localhost:8080
```

---

## 🌐 Fontes de vagas

| Fonte      | O que traz                                             | Gratuito |
|------------|---------------------------------------------------------|----------|
| Remotive   | Vagas remote-first globais (dev)                         | ✅       |
| Arbeitnow  | Vagas europeias (só remote, paginado até 5 páginas)       | ✅       |
| WWR        | We Work Remotely (RSS de programação)                     | ✅       |
| **Gupy**   | Vagas de tecnologia e estágio em empresas brasileiras (Itaú, Stone, Localiza, Boticário, TIM, e centenas de outras) — remoto, híbrido e presencial | ✅ |

**Sobre a fonte Gupy:** usa a API pública do portal de busca de vagas da
Gupy (`portal.gupy.io/job-search`), o mesmo ATS usado por milhares de
empresas brasileiras. Não requer autenticação — é a mesma API que o
próprio site público usa. A busca cobre ~14 termos de tecnologia e estágio
(desenvolvedor, java, python, devops, dados, estágio ti, etc.), com
deduplicação por ID da vaga.

> LinkedIn e InfoJobs **não foram incluídos**: nenhum dos dois oferece uma
> API pública de vagas — LinkedIn bloqueia scraping ativamente e o InfoJobs
> não expõe endpoint público sem parceria comercial. Se quiser adicionar
> uma empresa específica que usa outro ATS (Gupy tem subdomínio próprio por
> empresa, tipo `empresa.gupy.io`), me avise.

---

## 🎓 Classificação de senioridade

Toda vaga é classificada automaticamente pelo título/tags em:

| Nível | Detectado por (exemplos) |
|-------|--------------------------|
| `ESTAGIO` | intern, internship, estágio, trainee, working student, werkstudent, praktikum |
| `JUNIOR`  | junior, jr, entry-level, graduate, early career, associate |
| `PLENO`   | pleno, mid-level, intermediate, medior, middle |
| `SENIOR`  | senior, sr, staff, principal, lead, head of, architect |
| `NAO_INFORMADO` | quando o título não indica o nível |

A classificação roda em todo fetch e também retroativamente (vagas antigas
sem classificação são reprocessadas quando o backend sobe).

---

## 💰 Salário

Só a Remotive tem campo estruturado de salário (~77% das vagas). Arbeitnow
e Gupy não expõem esse dado na API — quando a empresa divulga o valor, ele
aparece embutido no texto da descrição da vaga.

Pra essas duas fontes, o `SalaryExtractor` procura por palavras-chave de
remuneração (salário, Gehalt, compensation, bolsa-auxílio...) e, se achar
uma perto de um valor monetário plausível (R$/€/$, mínimo de ~100), usa
esse trecho como salário. Isso evita pegar valores aleatórios do texto
(faturamento da empresa, preço de produto, etc.) como se fossem salário.

Cobertura real (dado que a maioria das empresas brasileiras não divulga
salário publicamente):
- Remotive: ~77%
- Arbeitnow: ~4% (só quando a descrição menciona um valor)
- Gupy: baixa e crescendo aos poucos — vagas novas são checadas na hora;
  vagas antigas sem salário são revisitadas em lotes de até 150 por ciclo
  de fetch (`JobAggregatorService.enriquecerSalariosGupyAntigas`), já que
  cada checagem exige uma requisição extra por vaga.
- WWR: não tem, o RSS não traz descrição.

O card exibe o salário como um badge (💰) ao lado do prazo de inscrição
quando disponível.

---

## 📅 Prazo de inscrição (deadline)

Vagas da fonte Gupy trazem a data limite de inscrição (`applicationDeadline`
do próprio portal). O card mostra uma contagem regressiva:

- 🔥 vermelho pulsante — fecha hoje ou em até 3 dias
- ⏳ amarelo — fecha em até 7 dias
- 📆 neutro — mais de 7 dias
- ⛔ "Encerrada" — prazo já passou

Remotive, Arbeitnow e WWR não informam prazo de inscrição, então essas
vagas simplesmente não mostram o badge.

---

## 👁 Abas de visualização

Em vez de só ficar cinza, vagas já vistas saem da aba "Novas" e vão para
"Já vistas" — cinco abas no topo da lista:

- 🔴 **Novas** — ainda não vistas (aba padrão ao abrir o app)
- 👁 **Já vistas** — vistas mas não aplicadas
- ✅ **Aplicadas** — aplicou, mas ainda sem retorno/processo ativo
- 🔄 **Em Andamento** — aplicou e está em processo seletivo ativo (entrevistas etc), separado de "Aplicadas" pra não confundir/esquecer
- 📋 **Todas**

Pra mover uma vaga entre "Aplicadas" e "Em Andamento": **arraste o card**
(ele fica arrastável assim que marcado como aplicado) e solte em cima da
aba desejada, ou use os botões "🔄 Entrei em processo" / "↩ Voltar pra
Aplicadas" no próprio card.

A lista pagina 30 vagas por vez com "Carregar mais vagas" (o volume subiu
bastante com a fonte Gupy).

---

## 📡 API do Backend

```
GET  /api/jobs                    → Lista vagas (com filtros)
GET  /api/jobs?source=GUPY         → Filtra por fonte (REMOTIVE|ARBEITNOW|WWR|GUPY)
GET  /api/jobs?search=itau         → Busca multi-termo, ignora acentos ("itau" acha "Itaú")
GET  /api/jobs?seniority=JUNIOR    → Filtra por nível (ESTAGIO|JUNIOR|PLENO|SENIOR|NAO_INFORMADO)
GET  /api/jobs?days=7              → Só publicadas nos últimos N dias
GET  /api/jobs?sort=posted_desc    → Ordenação: posted_desc | posted_asc | fetched_desc
GET  /api/jobs?onlyNew=true        → Só não vistas
GET  /api/jobs?onlySeen=true       → Só vistas e não aplicadas
GET  /api/jobs?onlyApplied=true    → Só aplicadas (fora de processo)
GET  /api/jobs?onlyInProgress=true → Só aplicadas e em processo seletivo ativo
GET  /api/jobs/stats               → Estatísticas gerais (inclui porSenioridade e porFonte)
PATCH /api/jobs/{id}/seen          → Marca como vista
PATCH /api/jobs/{id}/applied       → Marca como aplicada (e tira de "em andamento")
PATCH /api/jobs/{id}/in-progress   → Marca como em processo seletivo ativo
POST /api/jobs/fetch               → Dispara fetch manual
```

---

## 🗂 Estrutura do projeto

```
job-radar/
├── docker-compose.yml
├── backend/              ← Spring Boot 3 + Java 21
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/br/com/jobradar/
│       ├── model/        ← Entidade Job
│       ├── repository/   ← JPA Repository
│       ├── service/      ← Remotive, Arbeitnow, WWR, Gupy, SeniorityClassifier, Aggregator
│       └── controller/   ← REST API
└── frontend/             ← React 18 + TypeScript + Vite
    ├── Dockerfile
    ├── nginx.conf
    └── src/
        ├── components/   ← JobCard, FilterBar, StatsBar, ViewTabs
        ├── hooks/        ← useJobs
        └── types/        ← Job, Stats, Filters
```

---

## ⚙️ Customizando o horário do fetch

Em `JobAggregatorService.java`:
```java
@Scheduled(cron = "0 0 8 * * *", zone = "America/Sao_Paulo")
```
Muda o cron pra qualquer horário. Formato: `segundos minutos horas * * *`

## ⚙️ Customizando os termos de busca da Gupy

Em `GupyService.java`, a lista `SEARCH_TERMS` define quais termos são
buscados a cada fetch. Adicione/remova termos pra ajustar o foco (ex:
"react", "dados", "produto").

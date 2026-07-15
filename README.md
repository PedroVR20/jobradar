# 🎯 Job Radar

Dashboard pessoal para monitorar vagas de programação remotas na Europa **e**
vagas no Brasil — empresas grandes como Itaú, Stone, Localiza, Boticário,
TIM, Bradesco, Stellantis e Natura, entre centenas de outras, via Gupy e
Eureca.
Busca automaticamente todo dia às **08:00 BRT** e guarda tudo no banco.

Repositório: https://github.com/PedroVR20/jobradar

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
| **Eureca** | Programas de estágio/trainee em empresas grandes (Bradesco, Stellantis, Natura, Sephora, Equinor, SLC Agrícola e outras) | ✅ |

**Sobre a fonte Gupy:** usa a API pública do portal de busca de vagas da
Gupy (`portal.gupy.io/job-search`), o mesmo ATS usado por milhares de
empresas brasileiras. Não requer autenticação — é a mesma API que o
próprio site público usa. A busca cobre ~22 termos de tecnologia e estágio
(desenvolvedor, java, python, devops, dados, estágio ti, etc.), com
deduplicação por ID da vaga. Termos principais também são repetidos com
`state=Rio de Janeiro` pra garantir cobertura da região mesmo quando o
corte de 100 resultados por termo da busca nacional deixaria vaga de fora.

**Sobre a fonte Eureca:** usa `candidate-api.eureca.me/v2/opportunities`,
a API que o site público (`app.eureca.me/oportunidades`) usa pra listar
vagas. **Não é documentada oficialmente** — foi descoberta observando o
tráfego de rede do site, então pode quebrar se a Eureca mudar o front-end
(por isso fica isolada em `EurecaService`, sem afetar as outras fontes se
parar de funcionar). O catálogo inteiro é pequeno (pouco mais de 100 vagas
ativas), então a busca simplesmente pagina tudo em vez de usar termos como
na Gupy.

> LinkedIn e InfoJobs **não foram incluídos**: nenhum dos dois oferece uma
> API pública de vagas — LinkedIn bloqueia scraping ativamente e o InfoJobs
> não expõe endpoint público sem parceria comercial. Se quiser acompanhar
> uma vaga específica achada nesses sites (ou no Glassdoor, indicação etc),
> use o botão **"➕ Adicionar vaga"** no site pra cadastrar manualmente —
> veja a seção [Adicionar vaga manualmente](#-adicionar-vaga-manualmente).

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

## 🏷 Funcionalidades

### Logo das empresas
Cards com vagas da Gupy e Eureca exibem automaticamente o logo da empresa no canto do card.
Para fontes sem logo (Remotive, WWR, Arbeitnow, vagas manuais), aparecem as iniciais da empresa
na cor da fonte.

### Badge PcD ♿
Vagas marcadas como ação afirmativa para Pessoas com Deficiência ganham um badge roxo "♿ PcD"
na linha de badges do card — dado extraído da Gupy (campo `isAffirmativeAction`) e da Eureca
(`isPcd`).

### Fixar vaga 📌
Botão de pin no canto de cada card (oculto em vagas recusadas). Vagas fixadas sobem
**imediatamente ao topo da lista**, em qualquer aba e independente dos filtros ativos —
a ordenação é feita no backend, então funciona mesmo com filtros de seniority, fonte, data
e pills de tech stack combinados. Útil para não perder de vista uma vaga importante no meio
de centenas de outras. Clique de novo para desafixar. O estado persiste no banco.

### Notas pessoais 📝
Clique em "📝 Adicionar nota" embaixo das tags para expandir uma caixa de texto. Escreva
qualquer coisa sobre a vaga (salário negociado, contato do recrutador, impressões da
entrevista...) — salva automaticamente 800ms depois que parar de digitar. Uma bolinha azul
aparece no botão quando a vaga já tem nota.

### 🎓 Modo Iniciante
Botão no topo dos filtros que restringe os resultados para vagas de **Estágio** e **Júnior**
apenas, com um clique. Ideal pra quem está em busca da primeira oportunidade. Desativa o
filtro manual de senioridade enquanto ativo. O backend suporta `seniority=ESTAGIO,JUNIOR`
(múltiplos valores separados por vírgula).

### Pills de tecnologia personalizadas (multi-select OR)
No topo dos filtros há uma linha de pills criadas pelo próprio usuário. Digite qualquer
tecnologia (ex: `kubernetes`, `rust`, `dbt`) no campo "+ tecnologia" e pressione Enter —
a pill é criada e salva automaticamente no `localStorage`, persistindo entre sessões.

- **Clicar** numa pill a ativa (destacada em roxo); clicar de novo desativa.
- **Múltiplas pills** podem estar ativas ao mesmo tempo — a lógica é **OR**:
  a vaga aparece se o título, empresa ou tags contiver **qualquer** uma das pills selecionadas.
  Vagas que atendem mais de uma pill aparecem uma vez (união de conjuntos, sem duplicatas).
- **Remover** uma pill exibe um modal de confirmação arrastável para evitar exclusão acidental.
- A busca textual na caixa de pesquisa continua independente, com lógica **AND** (todos os
  termos devem aparecer) — os dois filtros funcionam em conjunto.

---

## 👁 Abas de visualização

Em vez de só ficar cinza, vagas já vistas saem da aba "Novas" e vão para
"Já vistas" — seis abas no topo da lista:

- 🔴 **Novas** — ainda não vistas (aba padrão ao abrir o app)
- 👁 **Já vistas** — vistas mas não aplicadas
- ✅ **Aplicadas** — aplicou, mas ainda sem retorno/processo ativo
- 🔄 **Em Andamento** — aplicou e está em processo seletivo ativo (entrevistas etc), separado de "Aplicadas" pra não confundir/esquecer
- ❌ **Recusadas** — processo encerrado sem sucesso, ou vaga congelada/cancelada pela empresa (some sozinha depois de 7 dias, veja abaixo)

Pra mover uma vaga entre abas, três formas (todas fazem a mesma coisa):
1. **Arraste o card** (fica arrastável assim que marcado como aplicado) e
   solte em cima da aba "Aplicadas", "Em Andamento" ou "Recusadas".
2. **Menu "⋮"** no canto do card — lista as outras abas disponíveis pro
   status atual da vaga. É a forma mais confiável: o drag-and-drop pode
   falhar se você começar a arrastar clicando em cima do título (que é um
   link, e o navegador tenta arrastar o link em vez do card).
3. Botões dedicados no card: "🔄 Entrei em processo", "↩ Voltar pra
   Aplicadas", "❌ Recusada/congelada", "↩ Reativar vaga".

A lista pagina 30 vagas por vez com "Carregar mais vagas" (o volume subiu
bastante com Gupy e Eureca).

---

## ❌ Vagas recusadas — exclusão automática em 7 dias

Marcar uma vaga como recusada/congelada tira ela do fluxo ativo
(Aplicadas/Em Andamento) sem apagar na hora — ela fica visível na aba
"Recusadas" por **7 dias** a partir do momento em que foi marcada (não da
data de publicação da vaga), e depois é **apagada permanentemente do
banco** pra não acumular vaga morta.

O card mostra uma contagem regressiva (🗑 "Some em Xd") avisando quantos
dias faltam. Se você mudar de ideia, o botão "↩ Reativar vaga" volta ela
pra "Aplicadas" e cancela a exclusão.

A limpeza roda automaticamente no fetch diário (08:00) e também ao subir o
backend (`JobAggregatorService.limparVagasRecusadasAntigas`). Pra mudar o
prazo, edite `DIAS_PARA_EXCLUIR_RECUSADAS` nesse arquivo **e** em
`frontend/src/types/Job.ts` (`DIAS_PARA_EXCLUIR_RECUSADAS`) — os dois
precisam ficar iguais.

---

## ➕ Adicionar vaga manualmente

Pra vagas achadas fora das fontes automáticas — Glassdoor, LinkedIn,
indicação de alguém, etc. Clique em "➕ Adicionar vaga" no topo do site e
preencha título, empresa e link (obrigatórios) mais os campos opcionais
(fonte, salário, modalidade, cidade, estado, status inicial).

A senioridade é classificada automaticamente pelo título, igual às vagas
buscadas automaticamente. Se colar uma URL que já existe no banco, atualiza
a vaga existente em vez de duplicar.

---

## 📡 API do Backend

```
GET  /api/jobs                    → Lista vagas (com filtros)
GET  /api/jobs?source=GUPY         → Filtra por fonte (REMOTIVE|ARBEITNOW|WWR|GUPY|EURECA|GLASSDOOR|MANUAL)
GET  /api/jobs?search=itau         → Busca multi-termo, ignora acentos ("itau" acha "Itaú")
GET  /api/jobs?seniority=JUNIOR    → Filtra por nível (ESTAGIO|JUNIOR|PLENO|SENIOR|NAO_INFORMADO)
GET  /api/jobs?workplaceType=REMOTO → Filtra por modalidade (REMOTO|HIBRIDO|PRESENCIAL)
GET  /api/jobs?state=São+Paulo     → Filtra por estado (nome por extenso, ignora acentos)
GET  /api/jobs?days=7              → Só publicadas nos últimos N dias
GET  /api/jobs?sort=posted_desc    → Ordenação: posted_desc | posted_asc | fetched_desc
GET  /api/jobs?onlyNew=true        → Só não vistas
GET  /api/jobs?onlySeen=true       → Só vistas e não aplicadas
GET  /api/jobs?onlyApplied=true    → Só aplicadas (fora de processo)
GET  /api/jobs?onlyInProgress=true → Só aplicadas e em processo seletivo ativo
GET  /api/jobs?onlyRejected=true   → Só recusadas/congeladas
GET  /api/jobs/states              → Lista de estados presentes no banco (popula o filtro)
GET  /api/jobs/stats               → Estatísticas gerais (inclui porSenioridade, porFonte, emAndamento, recusadas)
PATCH /api/jobs/{id}/seen          → Marca como vista
PATCH /api/jobs/{id}/applied       → Marca como aplicada (e tira de "em andamento"/"recusada")
PATCH /api/jobs/{id}/in-progress   → Marca como em processo seletivo ativo
PATCH /api/jobs/{id}/status?value=X → Move pra um status específico: NOVA|VISTA|APLICADA|ANDAMENTO|RECUSADA
POST  /api/jobs/manual              → Adiciona/atualiza vaga manual (title, company, url obrigatórios)
POST  /api/jobs/fetch               → Dispara fetch manual
PATCH /api/jobs/{id}/pin            → Fixa/desfixa vaga no topo da lista (pinned ↔ unpinned)
PATCH /api/jobs/{id}/notes          → Salva/limpa nota pessoal  Body: { "notes": "..." }
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
│       ├── service/      ← Remotive, Arbeitnow, WWR, Gupy, Eureca, SeniorityClassifier, SalaryExtractor, Aggregator
│       └── controller/   ← REST API
└── frontend/             ← React 18 + TypeScript + Vite
    ├── Dockerfile
    ├── nginx.conf
    └── src/
        ├── components/   ← JobCard, FilterBar, StatsBar, ViewTabs, AddJobModal
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

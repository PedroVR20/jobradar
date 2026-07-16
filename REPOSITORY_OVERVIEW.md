# 📊 Job Radar - Resumo do Repositório

## O que é

**Job Radar** é um dashboard pessoal fullstack que monitora vagas de programação remotas na Europa e Brasil de forma centralizada e automatizada. Ele agrega vagas de múltiplas fontes (Remotive, Arbeitnow, We Work Remotely, Gupy, Eureca) com buscas automáticas diárias às 8h BRT, classificação inteligente de senioridade, extração de salários e interface rica de filtros, abas e anotações pessoais.

## Stack Técnico

### Linguagens
- **Java**: 44.9%
- **TypeScript**: 36.3%
- **CSS**: 17.8%
- **Outro**: 0.9%

### Framework / Runtime
- **Backend**: Spring Boot 3.3 (Java 21) + Maven
- **Frontend**: React 18 + TypeScript + Vite
- **Banco de Dados**: PostgreSQL 16
- **Containerização**: Docker + Docker Compose

### Bibliotecas Notáveis
- **Spring Data JPA** — ORM para persistência
- **Rome** — Parser RSS (We Work Remotely)
- **Lombok** — Redução de boilerplate Java
- **TypeScript strict** — Type-safety no frontend
- **Vite** — Build tool rápido e dev server com HMR

---

## 🗂 Estrutura do Projeto

```
jobradar/
├── docker-compose.yml          Docker Compose com 3 serviços (postgres, backend, frontend)
├── README.md                   Documentação principal
├── backend/                    Spring Boot 3 + Java 21
│   ├── pom.xml                 Dependências Maven
│   ├── Dockerfile              Build da imagem backend
│   └── src/main/java/br/com/jobradar/
│       ├── model/              Entidade Job (persistência)
│       ├── repository/         JPA Repository para acesso ao banco
│       ├── service/            Lógica de negócio
│       │   ├── RemotiveService         Busca vagas em Remotive
│       │   ├── ArbeitnowService        Busca vagas em Arbeitnow
│       │   ├── WorkRemotelyService     Parser RSS de We Work Remotely
│       │   ├── GupyService             Scraper da API pública Gupy
│       │   ├── EurecaService           Scraper da API não-documentada Eureca
│       │   ├── JobAggregatorService    Orquestração e fetch diário (cron 08:00 BRT)
│       │   ├── SeniorityClassifier     Classifica senioridade por regex (ESTAGIO, JUNIOR, PLENO, SENIOR)
│       │   └── SalaryExtractor         Extrai salários do texto das vagas
│       └── controller/         REST API endpoints (/api/jobs/*)
└── frontend/                   React 18 + TypeScript + Vite
    ├── package.json            Dependências npm
    ├── Dockerfile              Build da imagem frontend (multi-stage, nginx)
    ├── nginx.conf              Configuração nginx para servir SPA
    └── src/
        ├── App.tsx             Componente raiz
        ├── App.css             Estilos (~25KB — UI com cores por fonte/status)
        ├── main.tsx            Entry point
        ├── components/         
        │   ├── JobCard         Card individual da vaga
        │   ├── FilterBar       Barra de filtros (senioridade, fonte, estado, pills de tech)
        │   ├── StatsBar        Dashboard de estatísticas
        │   ├── ViewTabs        6 abas (Novas, Já vistas, Aplicadas, Em Andamento, Recusadas)
        │   └── AddJobModal     Modal para adicionar vaga manualmente
        ├── hooks/              
        │   └── useJobs         Hook para chamadas à API e gerenciamento de estado
        └── types/              
            ├── Job             Interface da vaga com todos os campos
            ├── Stats           Interface de estatísticas
            ├── Filters         Interface de filtros
            └── constantes      DIAS_PARA_EXCLUIR_RECUSADAS e outros valores
```

---

## 🔄 Como Funciona (Fluxo de Dados)

1. **Orquestração Diária**
   - `JobAggregatorService` executa via cron `@Scheduled(cron = "0 0 8 * * *")` às 08:00 BRT
   - Método `fetch()` dispara todos os scrapers em paralelo

2. **Coleta de Vagas**
   - **RemotiveService**: Chamada HTTP a API pública → JSON
   - **ArbeitnowService**: Chamada HTTP a API pública → JSON (paginado até 5 páginas)
   - **WorkRemotelyService**: Parse de RSS feed
   - **GupyService**: Scraper da API pública `portal.gupy.io/job-search` com ~22 termos de busca + dedup por ID
   - **EurecaService**: Scraper da API não-documentada `candidate-api.eureca.me/v2/opportunities` (isolada em serviço próprio)

3. **Enriquecimento de Dados**
   - **SeniorityClassifier**: Classifica automaticamente por regex no título/tags (ESTAGIO, JUNIOR, PLENO, SENIOR, NAO_INFORMADO)
   - **SalaryExtractor**: Busca palavras-chave de remuneração + valores monetários plausíveis

4. **Persistência**
   - Insere/atualiza no PostgreSQL via JPA Repository
   - Limpeza automática: vagas recusadas são apagadas após 7 dias

5. **Frontend - Consumo da API**
   - Chamadas a `GET /api/jobs` com query params (source, search, seniority, workplaceType, state, days, sort, onlyNew, etc.)
   - Cache local em `localStorage` (filtros customizados, pills de tech)
   - Renderização em 6 abas com paginação (30 vagas por page)

6. **Interação do Usuário**
   - **Marcar vaga**: Aplicada, Em Andamento, Recusada (altera status no backend)
   - **Fixar vaga**: Pin imediato ao topo (persiste no banco)
   - **Adicionar nota**: Salva automaticamente 800ms após parar de digitar
   - **Filtrar por pills de tech**: Multi-select OR (lógica de união, sem duplicatas)

---

## 🚀 Como Rodar

### Opção 1: Docker (Recomendado - 1 comando)

```bash
docker-compose up --build
```

**Tempo de inicialização**: 2-3 minutos (Maven baixando dependências e compilando backend)

**Serviços disponíveis após startup:**

| Serviço    | Endereço                       |
|------------|--------------------------------|
| Dashboard  | http://localhost:3000          |
| Backend    | http://localhost:8080          |
| PostgreSQL | localhost:5432 (user: jobradar, pass: jobradar123, db: jobradar) |

### Opção 2: Desenvolvimento Local (sem Docker)

**Pré-requisitos:**
- Java 21+
- Maven 3.9+
- Node 20+
- PostgreSQL rodando localmente

**Backend:**
```bash
cd backend
mvn spring-boot:run
```
Sobe em: http://localhost:8080

**Frontend (em outro terminal):**
```bash
cd frontend
npm install
npm run dev
```
Sobe em: http://localhost:5173

**Configuração do Proxy**: O Vite faz proxy automático de `/api` → `localhost:8080`

---

## 📡 API do Backend

### Endpoints Principais

```
GET  /api/jobs                    → Lista todas as vagas com paginação (30 por page)
GET  /api/jobs?source=GUPY       → Filtra por fonte (REMOTIVE|ARBEITNOW|WWR|GUPY|EURECA|GLASSDOOR|MANUAL)
GET  /api/jobs?search=itau       → Busca multi-termo, ignora acentos ("itau" acha "Itaú")
GET  /api/jobs?seniority=JUNIOR  → Filtra por nível (ESTAGIO|JUNIOR|PLENO|SENIOR|NAO_INFORMADO)
GET  /api/jobs?workplaceType=REMOTO → Filtra por modalidade (REMOTO|HIBRIDO|PRESENCIAL)
GET  /api/jobs?state=São+Paulo   → Filtra por estado (nome por extenso, ignora acentos)
GET  /api/jobs?days=7            → Só publicadas nos últimos N dias
GET  /api/jobs?sort=posted_desc  → Ordenação: posted_desc | posted_asc | fetched_desc
GET  /api/jobs?onlyNew=true      → Só não vistas (aba "Novas")
GET  /api/jobs?onlySeen=true     → Só vistas e não aplicadas (aba "Já vistas")
GET  /api/jobs?onlyApplied=true  → Só aplicadas fora de processo (aba "Aplicadas")
GET  /api/jobs?onlyInProgress=true → Só em processo seletivo ativo (aba "Em Andamento")
GET  /api/jobs?onlyRejected=true → Só recusadas/congeladas (aba "Recusadas")
GET  /api/jobs/states            → Lista de estados presentes no banco (popula filtro)
GET  /api/jobs/stats             → Estatísticas gerais (porSenioridade, porFonte, emAndamento, recusadas)

PATCH /api/jobs/{id}/seen        → Marca como vista
PATCH /api/jobs/{id}/applied     → Marca como aplicada
PATCH /api/jobs/{id}/in-progress → Marca como em processo seletivo ativo
PATCH /api/jobs/{id}/status?value=X → Move pra status específico (NOVA|VISTA|APLICADA|ANDAMENTO|RECUSADA)
POST  /api/jobs/manual           → Adiciona/atualiza vaga manual (title, company, url obrigatórios)
POST  /api/jobs/fetch            → Dispara fetch manual
PATCH /api/jobs/{id}/pin         → Fixa/desfixa vaga no topo da lista
PATCH /api/jobs/{id}/notes       → Salva/limpa nota pessoal (body: { "notes": "..." })
```

---

## 🌐 Fontes de Vagas

| Fonte      | O que traz | Cobertura | API Pública? | Notas |
|------------|-----------|-----------|--------------|-------|
| **Remotive** | Vagas remote-first globais (dev) | ~77% com salário estruturado | ✅ Sim | Melhor cobertura de salário |
| **Arbeitnow** | Vagas europeias (só remote, paginado até 5 páginas) | ~4% com salário | ✅ Sim | Salário só em descrição |
| **We Work Remotely (WWR)** | RSS de programação | 0% salário | ✅ RSS feed | Sem descrição, sem salário |
| **Gupy** | Vagas de tech + estágio em empresas brasileiras (Itaú, Stone, Localiza, Boticário, TIM, etc.) | Baixa, crescendo | ✅ Sim (portal.gupy.io) | ~22 termos de busca, dedup por ID, cobertura de salário crescente |
| **Eureca** | Programas de estágio/trainee em empresas grandes (Bradesco, Stellantis, Natura, etc.) | ~100 vagas ativas | ⚠️ Não-documentada | Descoberta via network traffic, pode quebrar se mudarem front-end |
| **Manual** | Vagas cadastradas pelo usuário (LinkedIn, Glassdoor, indicação, etc.) | 100% (sob demanda) | N/A | Botão "+ Adicionar vaga" |

**LinkedIn e InfoJobs não foram inclusos**: LinkedIn bloqueia scraping; InfoJobs sem API pública sem parceria comercial.

---

## 🎓 Classificação de Senioridade

Toda vaga é classificada automaticamente pelo título/tags em **tempo real** (também retroativo ao subir backend):

| Nível | Detectado por (exemplos) |
|-------|-------------------------|
| `ESTAGIO` | intern, internship, estágio, trainee, working student, werkstudent, praktikum |
| `JUNIOR` | junior, jr, entry-level, graduate, early career, associate |
| `PLENO` | pleno, mid-level, intermediate, medior, middle |
| `SENIOR` | senior, sr, staff, principal, lead, head of, architect |
| `NAO_INFORMADO` | quando o título não indica o nível |

---

## 💰 Salário

- **Remotive**: ~77% das vagas (campo estruturado)
- **Arbeitnow**: ~4% (extraído de descrição)
- **Gupy**: Baixa, crescendo — vagas novas checadas na hora; vagas antigas revisitadas em lotes de até 150 por ciclo (`JobAggregatorService.enriquecerSalariosGupyAntigas`)
- **WWR**: 0% (RSS não traz descrição)

**Extração inteligente** (`SalaryExtractor`): Busca palavras-chave de remuneração (salário, Gehalt, compensation, bolsa-auxílio...) + valores monetários plausíveis (R$/€/$, mínimo ~100) para evitar pegar valores aleatórios (faturamento, preço de produto, etc).

O card exibe salário como badge (💰) ao lado do prazo de inscrição.

---

## 📅 Prazo de Inscrição (Deadline)

Vagas da **Gupy** trazem `applicationDeadline`. O card mostra contagem regressiva:

- 🔥 **Vermelho pulsante** — Fecha hoje ou em até 3 dias
- ⏳ **Amarelo** — Fecha em até 7 dias
- 📆 **Neutro** — Mais de 7 dias
- ⛔ **"Encerrada"** — Prazo já passou

Remotive, Arbeitnow e WWR **não informam prazo**, então sem badge.

---

## 🏷 Funcionalidades

### Logo das Empresas
Cards com vagas da **Gupy e Eureca** exibem automaticamente o logo da empresa no canto. Para fontes sem logo (Remotive, WWR, Arbeitnow, vagas manuais), aparecem as **iniciais da empresa** em cor da fonte.

### Badge PcD ♿
Vagas marcadas como ação afirmativa para **Pessoas com Deficiência** ganham badge roxo **"♿ PcD"** — extraído de `isAffirmativeAction` (Gupy) e `isPcd` (Eureca).

### Fixar Vaga 📌
Botão de pin em cada card. Vagas fixadas **sobem imediatamente ao topo** em qualquer aba, independente dos filtros. Clique novamente para desafixar. Estado persiste no banco.

### Notas Pessoais 📝
Clique em **"📝 Adicionar nota"** embaixo das tags para expandir caixa de texto. Salva automaticamente 800ms após parar de digitar. Bolinha azul no botão quando há nota.

### 🎓 Modo Iniciante
Botão no topo dos filtros que restringe resultados para **Estágio + Júnior** apenas. Desativa filtro manual de senioridade enquanto ativo.

### Pills de Tecnologia Personalizadas (Multi-select OR)
Campo "+ tecnologia" no topo. Digite qualquer tech (ex: `kubernetes`, `rust`, `dbt`) e pressione Enter:
- **Clicar** ativa (roxo); clicar novamente desativa
- **Múltiplas pills** ativas = lógica **OR** (vaga aparece se contiver qualquer uma)
- **Remover pill** exibe modal de confirmação
- Persiste em `localStorage` entre sessões

### 👁 Abas de Visualização (6 abas)

Em vez de ficar cinza, vagas já vistas saem da aba **"Novas"** e vão para **"Já vistas"**:

- 🔴 **Novas** — Não vistas (aba padrão ao abrir app)
- 👁 **Já vistas** — Vistas mas não aplicadas
- ✅ **Aplicadas** — Aplicou, mas sem retorno/processo ativo
- 🔄 **Em Andamento** — Aplicou e em processo seletivo ativo (entrevistas etc)
- ❌ **Recusadas** — Processo encerrado sem sucesso, ou vaga congelada/cancelada
- (Implícito) **Paginação**: 30 vagas por page, botão "Carregar mais vagas"

**Como mover vaga entre abas** (3 formas equivalentes):
1. Arraste o card (fica arrastável após marcar como aplicada) e solte na aba alvo
2. Menu "⋮" no canto — lista abas disponíveis (mais confiável que drag-and-drop)
3. Botões dedicados: "🔄 Entrei em processo", "↩ Voltar pra Aplicadas", "❌ Recusada/congelada", "↩ Reativar vaga"

### ❌ Vagas Recusadas — Exclusão Automática em 7 Dias

Marcar vaga como recusada/congelada tira ela do fluxo ativo sem apagar na hora:
- Fica visível em **"Recusadas"** por **7 dias** a partir da marcação (não da publicação)
- Card mostra contagem regressiva: 🗑 **"Some em Xd"**
- Botão **"↩ Reativar vaga"** cancela exclusão
- Limpeza automática roda no fetch diário (08:00) e ao subir backend

**Para customizar prazo de 7 dias:**
1. Edite `DIAS_PARA_EXCLUIR_RECUSADAS` em `JobAggregatorService.java`
2. Edite `DIAS_PARA_EXCLUIR_RECUSADAS` em `frontend/src/types/Job.ts`
3. **Ambos devem ser iguais**

### ➕ Adicionar Vaga Manualmente

Botão "+ Adicionar vaga" no topo. Preencha:
- **Obrigatórios**: título, empresa, link
- **Opcionais**: fonte, salário, modalidade, cidade, estado, status inicial

Senioridade classificada automaticamente pelo título. Se colar URL que já existe, **atualiza** em vez de duplicar.

---

## ⚙️ Customizações

### Horário do Fetch Automático

Em `backend/src/main/java/br/com/jobradar/service/JobAggregatorService.java`:

```java
@Scheduled(cron = "0 0 8 * * *", zone = "America/Sao_Paulo")
public void fetch() {
    // ...
}
```

Formato cron: `segundos minutos horas * * *`

Exemplos:
- `"0 0 8 * * *"` → 08:00 BRT
- `"0 30 9 * * *"` → 09:30 BRT
- `"0 0 0 * * *"` → Meia-noite BRT

### Termos de Busca da Gupy

Em `backend/src/main/java/br/com/jobradar/service/GupyService.java`, lista `SEARCH_TERMS` define termos buscados a cada fetch:

```java
private static final List<String> SEARCH_TERMS = Arrays.asList(
    "desenvolvedor", "java", "python", "devops", "dados", "estágio ti", // ...
);
```

Adicione/remova termos para ajustar foco (ex: "react", "dados", "produto").

---

## 📝 Estrutura de Arquivos Importantes

```
backend/src/main/java/br/com/jobradar/
├── JobRadarApplication.java           Entry point Spring Boot
├── model/
│   └── Job.java                       Entidade JPA com campos (id, title, company, url, source, seniority, salary, workplace, state, pinned, notes, status, createdAt, updatedAt, etc.)
├── repository/
│   └── JobRepository.java             Interface JPA com queries customizadas (findBySource, findBySeniority, etc.)
├── service/
│   ├── RemotiveService.java           Scraper Remotive API
│   ├── ArbeitnowService.java          Scraper Arbeitnow API
│   ├── WorkRemotelyService.java       Parser RSS WWR
│   ├── GupyService.java               Scraper Gupy (maior arquivo, ~10KB)
│   ├── EurecaService.java             Scraper Eureca (isolado)
│   ├── JobAggregatorService.java      Orquestrador (fetch, cleanup, enrichment)
│   ├── SeniorityClassifier.java       Classificador regex senioridade
│   └── SalaryExtractor.java           Extrator regex de salários
└── controller/
    └── JobController.java             REST endpoints

frontend/src/
├── App.tsx                            Componente raiz
├── App.css                            Estilos (~25KB)
├── main.tsx                           Entry point React + ReactDOM.render
├── components/
│   ├── JobCard.tsx                    Card individual com pin, nota, status buttons
│   ├── FilterBar.tsx                  Filtros (senioridade, fonte, estado, pills)
│   ├── StatsBar.tsx                   Dashboard de stats
│   ├── ViewTabs.tsx                   6 abas (Novas, Já vistas, etc.)
│   └── AddJobModal.tsx                Modal para vaga manual
├── hooks/
│   └── useJobs.ts                     Hook customizado (fetch, cache, filtros)
└── types/
    ├── Job.ts                         Interface Job + constantes
    ├── Stats.ts                       Interface Stats
    ├── Filters.ts                     Interface Filters
    └── constants.ts                   DIAS_PARA_EXCLUIR_RECUSADAS, etc.
```

---

## 🔧 Troubleshooting

### Docker não sobe
- Verifique portas 3000, 5173, 8080, 5432 disponíveis
- Rodando second time? `docker-compose down -v` para limpar volumes

### Maven toma muito tempo
- Primeira build: espere 2-3 min. Próximas serão mais rápidas (cache local `.m2`)

### Frontend não consegue chamar backend
- Verifique se `Vite dev server` está ativo (http://localhost:5173)
- Proxy automático de `/api` só funciona em dev
- Em produção (Docker), nginx redireciona `/api` para backend:8080

### PostgreSQL connection refused
- Verifique se PostgreSQL está rodando: `psql -U jobradar -d jobradar -h localhost`
- Docker: `docker-compose logs postgres` para ver logs

---

## 📚 Referências

- **README.md** — Documentação original e completa do projeto
- **pom.xml** — Dependências Maven backend
- **package.json** — Dependências npm frontend
- **docker-compose.yml** — Orchestração dos 3 serviços
- **Dockerfile** (backend e frontend) — Imagens containerizadas

---

## 🎯 Próximos Passos / Tópicos para Explorar

1. **Como são detectadas as vagas de programação da Gupy?**
   - Quais termos de busca (`SEARCH_TERMS`) a `GupyService` usa?
   - Como funciona o dedup por ID?
   - Qual a cobertura por termo?

2. **Como estender a API com novos filtros?**
   - Há suporte para range de salário (ex: `?salaryMin=3000&salaryMax=8000`)?
   - Como adicionar filtro por tecnologia sem usar pills?
   - Como implementar busca por estado ou benefícios?

3. **Resiliência da fonte Eureca**
   - Como `EurecaService` está isolada para não quebrar o todo?
   - Há testes automatizados para detectar breaking changes?
   - Qual o plano B se a API parar de funcionar?

4. **Performance em larga escala**
   - Como a paginação (30/page) impacta no banco?
   - Há índices no PostgreSQL para source, seniority, state?
   - Como otimizar queries de filtro multi-coluna?

5. **Melhorias UX/UI futuras**
   - Alertas de novas vagas em tempo real (WebSocket)?
   - Recomendações baseadas em histórico de aplicações?
   - Integração com Notion/Airtable para tracking externo?

---

**Última atualização**: 16 de julho de 2026  
**Repositório**: https://github.com/PedroVR20/jobradar

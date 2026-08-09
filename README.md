# 🎯 Job Radar

Dashboard pessoal para monitorar vagas de programação remotas na Europa **e**
vagas no Brasil — empresas grandes como Itaú, Stone, Localiza, Boticário,
TIM, Bradesco, Stellantis e Natura, entre centenas de outras, agregadas de
7 fontes (Remotive, Arbeitnow, WWR, Gupy, Eureca, QueroVagasTech, Nerdin)
num único funil de candidatura, com integração opcional a uma Agenda Pessoal.
Busca automaticamente **a cada 4 horas** e guarda tudo no banco.

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

**Porta já em uso?** Se `docker compose up` falhar com algo tipo
`port is already allocated` (comum com 5432/8080, portas padrão de muita
coisa), copie `.env.example` para `.env` e troque a porta do serviço que
conflitar — não precisa editar o `docker-compose.yml`:

```bash
cp .env.example .env
# edite .env, ex: BACKEND_PORT=8081
docker compose up --build
```

Se você também roda o projeto **Agenda Pessoal** localmente (integração
descrita mais abaixo), rode `.\scripts\health-check.ps1` pra checar de
uma vez o status dos containers e endpoints dos dois projetos — útil
porque os dois usam Docker Compose separados e já aconteceu de um
derrubar o outro por conflito de porta/rede.

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

| Fonte                  | O que traz                                             | Gratuito |
|------------------------|--------------------------------------------------------|----------|
| Remotive               | Vagas remote-first globais (dev)                        | ✅       |
| Arbeitnow              | Vagas europeias (só remote, paginado até 5 páginas)     | ✅       |
| WWR                    | We Work Remotely (RSS de programação)                   | ✅       |
| **Gupy**               | Vagas de tecnologia em empresas brasileiras (Itaú, Stone, Localiza, Boticário, TIM e centenas de outras) — remoto, híbrido e presencial | ✅ |
| **Eureca**             | Programas de estágio/trainee em grandes empresas (Bradesco, Stellantis, Natura, Sephora, Equinor, SLC Agrícola e outras) | ✅ |
| **QueroVagasTech**     | Agregador BR com InfoJobs, Stone (Vagas.com), Solides, Thoughtworks, Totvs, RedHat, Accenture e outras fontes corporativas — fontes Gupy já cobertas são ignoradas para não duplicar | ✅ |
| **Nerdin**              | Vagas de TI (nerdin.com.br) — foco em desenvolvimento, dados, infra, DevOps, IA | ✅ |

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

**Sobre a fonte QueroVagasTech:** usa `querovagastech.com.br/api/jobs`,
a API interna do agregador — descoberta por engenharia reversa do tráfego
do site, sem autenticação. Pagina tudo (100 vagas/página) e filtra no
backend as fontes `GupyPortalTecnologia` e `GupyExample` (já cobertas
pelo `GupyService`). Salary vem estruturado (`min`/`max`/`currency`/`period`)
e é formatado automaticamente como "R$ X – Y/mês". Adicionou ~611 vagas
novas no primeiro fetch, principalmente do InfoJobs.

**Sobre a fonte Nerdin:** diferente das outras fontes BR, o Nerdin **não
tem API pública nem interna descoberta** — é scraping direto do HTML da
listagem paginada (`nerdin.com.br/vagas.php?pagina=N`), via `NerdinService`
usando Jsoup. As classes CSS dos cards (`.vaga-card`, `.vaga-titulo`,
`.vaga-empresa-nome`...) são as mesmas que o JS do próprio site usa pra
favoritar vagas, então tendem a ser relativamente estáveis, mas ainda é
HTML — pode quebrar se o site for redesenhado (fica isolado, como as
outras fontes, sem afetar o resto se parar de funcionar). `robots.txt`
permite (`Allow: /`, sem disallow pra `/vagas.php`).

Cada vaga individual tem um JSON-LD `JobPosting` (schema.org) bem completo
na página de detalhe — inclusive prazo de inscrição — mas isso exigiria
uma requisição HTTP extra por vaga (~700 no catálogo inteiro), então por
ora só a listagem é usada: dá título, empresa, salário, modalidade, data
de publicação e tags, sem esse custo. **Sem prazo de inscrição** por
enquanto (mesma limitação de Remotive/Arbeitnow/WWR).

O Nerdin também não parece filtrar a listagem só pra vagas ativas —
algumas datadas de anos atrás apareceram misturadas nos testes. Por isso,
qualquer vaga "publicada" há mais de 90 dias é descartada no parsing, pra
não mostrar vaga morta como se fosse oportunidade nova.

> LinkedIn **não foi incluído**: bloqueia scraping ativamente e não oferece
> API pública de vagas. Se quiser acompanhar uma vaga específica achada no
> LinkedIn, Glassdoor ou indicação, use o botão **"➕ Adicionar vaga"** —
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
- QueroVagasTech: campo estruturado na API (`min`/`max`/`currency`/`period`) — cobertura depende de cada fonte agregada (InfoJobs divulga com mais frequência que Gupy).
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
entrevista...) — salva automaticamente 800ms depois que parar de digitar, com indicador
de status ("Salvando..." → "✓ Salvo") no cabeçalho do editor. Quando a vaga já tem nota e
o card está fechado, o texto (até 4 linhas) aparece direto num chip âmbar — sem precisar
abrir pra lembrar o que foi escrito.

### Cores personalizadas de fonte 🎨
Vagas adicionadas manualmente com uma fonte que não existe no sistema (ex: digitar
"InfoJobs" no campo Fonte do formulário) ganham um badge clicável com ícone 🎨 em vez do
cinza padrão. Clicar abre o seletor de cor nativo do navegador — a cor escolhida fica
salva por nome de fonte (no navegador) e vale pra toda vaga com aquela mesma fonte.
Fontes oficiais (Gupy, Eureca, Remotive etc.) não têm esse botão, já vêm com cor fixa.

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
- ⭐ **Interessado** — vaga que chamou atenção mas ainda não foi aplicada, separada de "Já vistas" pra não se perder no meio de dezenas de outras
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
   Aplicadas", "❌ Recusada/congelada", "↩ Reativar vaga", e em qualquer
   vaga ainda não aplicada "⭐ Marquei interesse" / "⭐ Tirar interesse"
   pra entrar/sair da aba Interessado (essa não é destino de drag-and-drop,
   só o botão ou o menu "⋮").

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

A limpeza roda automaticamente a cada fetch periódico (a cada 4h) e também ao subir o
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

## 🤖 Recursos de IA (Gemini — opcional)

Totalmente opcional: sem uma `GEMINI_API_KEY` configurada, o resto do app
funciona normal, só esses três recursos ficam desativados (e escondidos da
UI). Key grátis em **https://aistudio.google.com/apikey** — copie
`.env.example` pra `.env` e preencha `GEMINI_API_KEY=`.

**Como saber se está ativa:** botão **⚙️ Configurações** no cabeçalho abre um
painel dedicado com o status (🟢 IA ativa/desativada, modelo em uso) e o que
cada um dos 3 recursos faz — sem isso, não dava pra saber olhando a tela
principal que a integração existe.

**Perfil salvo (currículo/stack):** o mesmo painel de Configurações tem uma
área pra enviar seu currículo em **PDF ou DOCX** (arrastar ou clicar) — o
texto é extraído **inteiramente no navegador** (`pdfjs-dist`/`mammoth`, nada é
enviado ao backend nem a lugar nenhum) e salvo no `localStorage`. Tem também
um modo "colar manualmente" pra quem preferir digitar em vez de enviar
arquivo, e um "Ver/editar texto" pra corrigir a extração se ela sair um pouco
torta (comum em PDFs com layout em colunas). Esse texto pré-preenche
automaticamente o "Contexto adicional" toda vez que você abre **🤖 Gerar
carta** em qualquer vaga — edita à vontade por vaga sem afetar o perfil
salvo. As libs de leitura de arquivo (~1MB) só são baixadas na hora que você
realmente usa o upload, não pesam no carregamento normal do app.

**Limite de requisições (free tier):** o Gemini grátis tem cota por **minuto
e** por **dia**, ambas bem apertadas. Configurações mostra um contador
aproximado de quantas chamadas o backend já fez hoje (reseta se ele
reiniciar — não é a contagem oficial do Google, só um sinal). Se o limite
estourar, a mensagem de erro já diz qual dos dois foi: "espera uns 20-30s"
(por minuto) ou "só volta amanhã" (diário) — bem diferente do que fazer em
cada caso, por isso a distinção.

**Pool de várias keys (opcional):** em vez de `GEMINI_API_KEY` (uma key),
dá pra configurar `GEMINI_API_KEYS` no `.env` com várias separadas por
vírgula. O backend testa isso sozinho: numa mesma chamada, se uma key bater
em rate limit (por minuto ou diário), ele tenta a próxima automaticamente,
sem o usuário perceber — só devolve erro se todas as keys da pool falharem.
Configurações mostra "🔑 X de N keys disponíveis hoje".

⚠️ **Isso só ajuda de verdade se as keys forem de projetos Google Cloud
diferentes** — confira em
[aistudio.google.com/apikey](https://aistudio.google.com/apikey) se cada key
aparece sob um projeto diferente antes de contar com a pool; se todas caem no
mesmo projeto, é preciso trocar explicitamente de projeto (seletor no topo
da página) antes de gerar cada key nova. Duas coisas que aprendemos testando
isso de verdade:

1. **Keys geradas em sequência sem trocar de projeto compartilham a mesma
   cota.** Um primeiro teste com 9 keys assim mostrou todas batendo no limite
   diário já na primeira chamada de cada, inclusive as nunca usadas antes —
   só se explica por cota compartilhada.
2. **Projetos recém-criados podem não ter acesso ao modelo fixo mais antigo.**
   Com `gemini-2.5-flash` fixo, keys de projetos novos devolviam 404 ("no
   longer available to new users") mesmo o modelo aparecendo na lista —
   por isso o padrão agora é `gemini-flash-latest` (um alias que o Google
   mantém sempre apontando pro flash atual). Com projetos genuinamente
   separados + esse alias, testamos 8 keys reais e 10 chamadas seguidas
   funcionaram sem nenhuma bater em limite.

Também vale saber: contas Google novas/gratuitas têm um teto de quantos
projetos dá pra criar (por padrão baixo, algo entre 5 e 25 dependendo da
conta) — bateu nisso tentando criar mais de 8 projetos numa conta. Dá pra
pedir aumento desse limite no Google Cloud Console, mas isso já é fora do
escopo do Job Radar.

| Recurso | Onde aparece | O que faz |
|---|---|---|
| **Carta de apresentação** | Menu **🤖 IA** em cada vaga → "✉️ Gerar carta de apresentação", abre um modal próprio com campo de contexto extra opcional e botão de copiar | `POST /api/jobs/{id}/cover-letter` gera uma carta a partir do que a vaga tem salva (título, empresa, senioridade, modalidade, local, tags, salário, suas notas) **mais a descrição real da vaga**, buscada na hora na própria página dela (best effort — nem toda fonte deixa, ver JobDescriptionService abaixo). A IA é instruída a não inventar histórico profissional que você não informou. |
| **Compatibilidade com seu perfil** | Menu **🤖 IA** → "🎯 Compatibilidade com meu perfil" | Compara seu currículo/perfil (o que você salvou em Configurações, ou cola na hora) com os requisitos reais da vaga e devolve uma nota 0-100% honesta, pontos fortes e pontos a desenvolver — a IA é instruída a não inflar a nota. |
| **Perguntas prováveis de entrevista** | Menu **🤖 IA** → "❓ Perguntas prováveis de entrevista" | Gera de 6 a 8 perguntas técnicas + comportamentais específicas da vaga/stack (não genéricas tipo "fale sobre você"), considerando seu perfil salvo quando disponível. |
| **Faixa salarial estimada** | Botão **💰 Salário estimado** em cada vaga (sempre visível, não depende da IA estar ativa) | **Não usa IA generativa** — usa um modelo de **regressão treinado de verdade** (Ridge, scikit-learn, offline) sobre as vagas com salário real já cadastradas no banco, prevendo a partir de senioridade + stack + modalidade + estado. Mostra a margem de erro típica (~43%) explicitamente, e tem um botão extra pra estimar com base no *seu* currículo salvo em vez dos dados da vaga. Ver [scripts/README.md](scripts/README.md) pra como foi treinado e como retreinar. |
| **Detecção de duplicatas mais precisa** | Botão **🧩 Duplicatas** no cabeçalho, cada grupo confirmado pela IA leva o selo "🤖 confirmado por IA" | O endpoint `/api/jobs/duplicates` (mesma empresa + título parecido, já existia) agora pede uma segunda opinião ao Gemini pra descartar grupos que só parecem duplicata por palavra em comum mas são vagas de times/produtos diferentes. Limitado a 20 verificações por chamada (limite do free tier) — grupos além disso mantêm só o veredito por similaridade de palavras. |
| **Classificação de senioridade/stack** | Selo **🤖** ao lado do badge de senioridade, nas vagas que passaram por ele | Quando o classificador por regex não decide (título ambíguo ou em outro idioma), uma chamada extra ao Gemini tenta resolver e também extrai tecnologias citadas no título pra completar as tags. Só roda nos casos que o regex não resolveu, não em toda vaga nova — o selo só aparece em vagas novas processadas depois que a IA foi ligada, não retroage nas antigas. |

**Descrição real da vaga (JobDescriptionService):** carta, compatibilidade e
perguntas de entrevista buscam a página da própria vaga (Jsoup) e extraem o
texto principal na hora, sem guardar nada no banco — best effort, nem todo
site deixa (SPAs renderizados em JS ficam sem texto, cai de volta pros
campos que a vaga já tinha). Perfil de candidato usado em compatibilidade e
perguntas nunca é persistido no backend — só chega junto do request que o
usa, vindo do que o frontend já tem salvo em `localStorage`.

**"Treinar" a IA com feedback (sem fine-tuning de verdade):** depois de
gerar uma carta, compatibilidade ou lista de perguntas, aparece uma caixa
"👍/👎 + o que você concorda/discorda?" — o comentário é salvo no
`localStorage` (até 8 mais recentes por recurso) e volta automaticamente
como referência de estilo na próxima geração desse mesmo tipo, sem precisar
colar de novo. Isso não é fine-tuning real (o modelo por trás continua o
mesmo, sem pesos ajustados) — é o modelo lendo seu feedback anterior como
parte do prompt, o que na prática funciona bem pra calibrar tom/estilo sem
nenhuma infraestrutura de treino. Também fica só no navegador, nunca é
persistido no backend.

---

## 🔗 Integração com Agenda Pessoal

Se você também roda o projeto **Agenda Pessoal** localmente
(`localhost:8081`), o Job Radar se conecta a ele **inteiramente pelo
navegador** — o backend do Job Radar nunca fala com a Agenda, só o frontend,
via token JWT guardado no `localStorage`. Um indicador no canto superior
direito mostra o status da conexão (🟢 conectado / formulário de login
compacto) com botões de sincronizar e desconectar.

| Recurso | O que faz |
|---|---|
| **📅 Salvar na Agenda** | Botão no card cria uma tarefa vinculada à vaga (título, link, notas, prazo opcional) |
| **Follow-up automático** | Ao mover a vaga pra "Em Andamento", cria sozinho uma tarefa de prioridade alta vencendo em 7 dias — sem precisar abrir modal |
| **Sincronização de status** | Aplicada/Em Andamento/Recusada refletem automaticamente como Pendente/Em Andamento/Concluída na tarefa vinculada da Agenda, nos dois sentidos — se você mexer direto no Kanban da Agenda, o Job Radar relê e ajusta sozinho ao carregar (ou pelo botão de sincronizar manual) |
| **🎤 Marcar entrevista** | Em vagas "Em Andamento", cria uma tarefa de prioridade **crítica** com aviso 2h antes do horário escolhido, vinculada separadamente da tarefa de candidatura |
| **Notificações** | Tarefas criadas pelo Job Radar já saem com aviso configurado (24h antes pra candidatura/follow-up, 2h antes pra entrevista) |

Nada disso funciona se a Agenda Pessoal não estiver rodando em `localhost:8081`
— os botões continuam visíveis, mas pedem login na hora de usar.

---

## 📊 Dashboard de métricas

Botão "📊 Métricas" no topo abre um painel com:
- **Funil de candidatura**: total aplicadas, em andamento, aguardando
  retorno, recusadas
- **Taxa de resposta**: % das aplicações que já tiveram algum retorno
  (avançou ou foi recusada)
- **Aplicações por semana**: gráfico de barras das últimas 8 semanas
- **Tempo médio de resposta**: dias até avançar pra "Em Andamento" e dias
  até ser recusado

Os tempos médios só contam vagas aplicadas depois que os campos
`appliedAt`/`inProgressAt` foram criados — candidaturas antigas não têm
essa marcação e ficam de fora dessa conta específica (mas continuam
contando no funil normalmente).

---

## 📡 API do Backend

```
GET  /api/jobs                    → Lista vagas (com filtros)
GET  /api/jobs?source=GUPY         → Filtra por fonte — qualquer valor presente no banco, oficial ou personalizado (veja /api/jobs/sources)
GET  /api/jobs?search=itau         → Busca multi-termo, ignora acentos ("itau" acha "Itaú")
GET  /api/jobs?seniority=JUNIOR    → Filtra por nível (ESTAGIO|JUNIOR|PLENO|SENIOR|NAO_INFORMADO)
GET  /api/jobs?workplaceType=REMOTO → Filtra por modalidade (REMOTO|HIBRIDO|PRESENCIAL)
GET  /api/jobs?state=São+Paulo     → Filtra por estado (nome por extenso, ignora acentos)
GET  /api/jobs?days=7              → Só publicadas nos últimos N dias
GET  /api/jobs?sort=posted_desc    → Ordenação: posted_desc | posted_asc | fetched_desc
GET  /api/jobs?onlyNew=true        → Só não vistas
GET  /api/jobs?onlySeen=true       → Só vistas, sem interesse marcado, e não aplicadas
GET  /api/jobs?onlyInteressado=true → Só marcadas com interesse (aba ⭐ Interessado), e não aplicadas
GET  /api/jobs?onlyApplied=true    → Só aplicadas (fora de processo)
GET  /api/jobs?onlyInProgress=true → Só aplicadas e em processo seletivo ativo
GET  /api/jobs?onlyRejected=true   → Só recusadas/congeladas
GET  /api/jobs/states              → Lista de estados presentes no banco (popula o filtro)
GET  /api/jobs/sources             → Lista de fontes presentes no banco, oficiais + personalizadas (popula o filtro de fonte)
GET  /api/jobs/stats               → Estatísticas gerais (inclui porSenioridade, porFonte, interessadas, emAndamento, recusadas)
GET  /api/jobs/metrics              → Funil de candidatura, taxa de resposta, aplicações/semana, tempo médio de resposta
GET  /api/jobs/duplicates          → Detecta possíveis vagas duplicadas entre fontes (mesma empresa + título similar + mesma senioridade), UI: botão 🧩 Duplicatas no cabeçalho
GET  /api/jobs/ai-status           → Status da IA (Gemini): { enabled, model } — sem expor a key. UI: botão ⚙️ Configurações no cabeçalho
PATCH /api/jobs/{id}/seen          → Marca como vista
PATCH /api/jobs/{id}/applied       → Marca como aplicada (e tira de "em andamento"/"recusada"/"interessado")
PATCH /api/jobs/{id}/in-progress   → Marca como em processo seletivo ativo
PATCH /api/jobs/{id}/status?value=X → Move pra um status específico: NOVA|VISTA|INTERESSADO|APLICADA|ANDAMENTO|RECUSADA
POST  /api/jobs/manual              → Adiciona/atualiza vaga manual (title, company, url obrigatórios)
POST  /api/jobs/fetch               → Dispara fetch manual (roda as 7 fontes de forma síncrona, pode levar
                                       ~1min — o nginx do frontend tem 240s de timeout pra dar folga)
PATCH /api/jobs/{id}/pin            → Fixa/desfixa vaga no topo da lista (pinned ↔ unpinned)
PATCH /api/jobs/{id}/notes          → Salva/limpa nota pessoal  Body: { "notes": "..." }
POST  /api/jobs/{id}/cover-letter   → Gera carta de apresentação via IA. 503 sem GEMINI_API_KEY, 429 se o free tier
                                       estourou (mensagem diz se foi por minuto ou por dia), 502 pra outras falhas do
                                       Gemini.  Body opcional: { "extraContext": "..." }  UI: menu 🤖 IA no card
GET  /api/jobs/{id}/salary-estimate → Faixa salarial: { predicted, modelInfo, similarJobs } — predicted vem do
                                       modelo treinado (Ridge, sempre disponível se o modelo carregou), similarJobs
                                       é a mediana de vagas parecidas (exige amostra mínima). Não usa IA generativa,
                                       sempre 200.  UI: botão 💰 Salário estimado no card
POST /api/jobs/{id}/salary-estimate/personalized → Mesma estimativa, mas usando a senioridade/stack extraídas do
                                       perfil do candidato em vez dos dados da vaga.  Body: { "candidateProfile": "..." }
POST  /api/jobs/{id}/match-score    → Compatibilidade do perfil do candidato com a vaga (0-100 + pontos fortes/a
                                       desenvolver). Mesmos códigos de erro do cover-letter.  Body: { "candidateProfile": "..." }
                                       UI: menu 🤖 IA no card
POST  /api/jobs/{id}/interview-questions → Perguntas prováveis de entrevista pra vaga. Mesmos códigos de erro do
                                       cover-letter.  Body opcional: { "candidateProfile": "..." }  UI: menu 🤖 IA no card
```

---

## 🗂 Estrutura do projeto

```
job-radar/
├── docker-compose.yml
├── .env.example          ← Portas configuráveis (POSTGRES_PORT, BACKEND_PORT, FRONTEND_PORT)
├── scripts/
│   └── health-check.ps1  ← Checa containers/endpoints do Job Radar + Agenda Pessoal juntos
├── backend/              ← Spring Boot 3 + Java 21
│   ├── Dockerfile
│   ├── pom.xml
│   └── src/main/java/br/com/jobradar/
│       ├── model/        ← Entidade Job
│       ├── repository/   ← JPA Repository
│       ├── resources/    ← application.yml, salary_model.json (modelo de salário treinado, ver scripts/)
│       ├── service/      ← Remotive, Arbeitnow, WWR, Gupy, Eureca, QueroVagasTech, Nerdin, SeniorityClassifier,
│       │                    SalaryExtractor, Aggregator, SalaryEstimateService/SalaryPredictionService (dado
│       │                    real + modelo treinado, sem IA generativa), GeminiService (pool de keys) +
│       │                    AiClassifier/AiDuplicateVerifier/CoverLetter/JobDescriptionService/MatchScore/
│       │                    InterviewQuestions (IA opcional)
│       └── controller/   ← REST API (jobs, stats, metrics, duplicates)
├── scripts/               ← train_salary_model.py (treino offline do modelo de salário, ver scripts/README.md)
└── frontend/             ← React 18 + TypeScript + Vite
    ├── Dockerfile
    ├── nginx.conf
    └── src/
        ├── components/   ← JobCard, FilterBar, StatsBar, ViewTabs, AddJobModal, MetricsModal,
        │                    AgendaModal, AgendaStatusBar, InterviewModal, SettingsModal,
        │                    DuplicatesModal, CoverLetterModal, SalaryEstimateModal, MatchScoreModal,
        │                    InterviewQuestionsModal, AiFeedbackBox (IA opcional, exceto Salary)
        ├── hooks/         ← useJobs, useAgenda (integração client-side), useSourceColors,
        │                    useAiStatus, useCandidateProfile, useAiFeedback
        ├── utils/         ← extractResumeText (PDF/DOCX → texto, 100% client-side)
        └── types/        ← Job, Stats, Filters, Metrics
```

---

## ⚙️ Customizando a frequência do fetch

Em `JobAggregatorService.java`:
```java
@Scheduled(cron = "0 0 */4 * * *", zone = "America/Sao_Paulo")
```
Hoje roda a cada 4h (00h, 04h, 08h, 12h, 16h, 20h BRT) — antes era só uma
vez por dia às 08:00, mas isso atrasava demais vagas postadas à tarde.
Muda o cron pra qualquer frequência/horário. Formato: `segundos minutos
horas * * *` (ex: `0 0 */2 * * *` pra a cada 2h, `0 0 8,20 * * *` pra
8h e 20h especificamente).

## ⚙️ Customizando os termos de busca da Gupy

Em `GupyService.java`, a lista `SEARCH_TERMS` define quais termos são
buscados a cada fetch. Adicione/remova termos pra ajustar o foco (ex:
"react", "dados", "produto").

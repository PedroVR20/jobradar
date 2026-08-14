package br.com.jobradar.service;

import java.util.List;
import java.util.Map;

/**
 * Fase 3.6 — extraído de {@link JarvisChatService} (que tinha passado de
 * 2000 linhas). Só as DECLARAÇÕES das ~25 ferramentas do Hunter (schema +
 * descrição em texto pro Gemini decidir quando chamar cada uma) — zero
 * lógica, zero dependência de estado da instância (por isso dá pra ser
 * {@code static}, diferente do dispatcher/implementações que ficam em
 * JarvisChatService). Mudança de comportamento zero: é o mesmo método
 * {@code buildTools()} movido verbatim, só virou {@code static} numa
 * classe própria.
 */
final class JarvisToolDeclarations {

    private JarvisToolDeclarations() {}

    static List<GeminiService.FunctionDeclaration> build() {
        Map<String, Object> listarVagasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "STRING",
                                "description", "Filtra pelo status da vaga no funil pessoal do usuário.",
                                "enum", List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")
                        ),
                        "dias", Map.of("type", "INTEGER", "description", "Só vagas publicadas nos últimos N dias. Omita pra não filtrar por data."),
                        "busca", Map.of("type", "STRING", "description", "Termo de busca livre em título/empresa/tags, ex: nome de uma empresa específica ou uma tecnologia."),
                        "salarioMinimo", Map.of("type", "INTEGER", "description", "Só vagas com estimativa salarial (modelo próprio, sem IA) maior ou igual a esse valor em reais. Vaga sem estimativa disponível nunca bate esse filtro."),
                        "limite", Map.of("type", "INTEGER", "description", "Máximo de vagas a retornar. Padrão 10, máximo 20.")
                )
        );

        Map<String, Object> semParametros = Map.of("type", "OBJECT", "properties", Map.of());

        Map<String, Object> compatibilidadeParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "dias", Map.of("type", "INTEGER", "description", "Quantos dias pra trás considerar vagas recentes. Use 1 pra \"hoje\", 7 pra \"essa semana\". Padrão 1.")
                )
        );

        Map<String, Object> compatibilidadeFunilParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "STRING",
                                "description", "Status do funil do usuário pra filtrar — mesmo enum de listarVagas.",
                                "enum", List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")
                        ),
                        "busca", Map.of("type", "STRING", "description", "Termo de busca livre em título/empresa/tags, opcional."),
                        "limite", Map.of("type", "INTEGER", "description", "Máximo de vagas a analisar de verdade com IA. Padrão 10, máximo 15 (teto rígido, mesmo se pedir mais).")
                ),
                "required", List.of("status")
        );

        Map<String, Object> salarioVagasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "status", Map.of(
                                "type", "STRING",
                                "description", "Filtra pelo status do funil, opcional — mesmo enum de listarVagas.",
                                "enum", List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")
                        ),
                        "busca", Map.of("type", "STRING", "description", "Termo de busca livre em título/empresa/tags, opcional."),
                        "limite", Map.of("type", "INTEGER", "description", "Máximo de vagas a estimar. Padrão 15, máximo 25.")
                )
        );

        Map<String, Object> detalharVagasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaIds", Map.of(
                                "type", "ARRAY",
                                "items", Map.of("type", "INTEGER"),
                                "description", "IDs das vagas a detalhar (peça pra listarVagas primeiro se só tiver título/empresa). " +
                                        "1 id = detalhe completo de uma vaga só; 2+ ids = comparação lado a lado."
                        )
                ),
                "required", List.of("vagaIds")
        );

        Map<String, Object> vagasParecidasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga de referência (peça pra listarVagas primeiro se só tiver título/empresa)."),
                        "limite", Map.of("type", "INTEGER", "description", "Máximo de vagas parecidas a retornar. Padrão 8, máximo 15.")
                ),
                "required", List.of("vagaId")
        );

        Map<String, Object> marcarStatusParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga a mudar (peça pra listarVagas primeiro se só tiver título/empresa)."),
                        "status", Map.of(
                                "type", "STRING",
                                "description", "Novo status da vaga.",
                                "enum", List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")
                        )
                ),
                "required", List.of("vagaId", "status")
        );

        Map<String, Object> cartaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga (peça pra listarVagas primeiro se só tiver título/empresa)."),
                        "contextoExtra", Map.of("type", "STRING", "description", "Detalhe pontual pra essa carta, opcional (ex: 'mencionar que topo trabalho híbrido').")
                ),
                "required", List.of("vagaId")
        );

        Map<String, Object> semParametrosMetricas = Map.of("type", "OBJECT", "properties", Map.of());

        Map<String, Object> prazoParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "diasMaximo", Map.of("type", "INTEGER", "description", "Fecha em até quantos dias. Padrão 7.")
                )
        );

        Map<String, Object> duplicatasParams = Map.of("type", "OBJECT", "properties", Map.of());

        Map<String, Object> fontesParams = Map.of("type", "OBJECT", "properties", Map.of());

        Map<String, Object> fixarVagaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga (peça pra listarVagas primeiro se só tiver título/empresa)."),
                        "fixar", Map.of("type", "BOOLEAN", "description", "true pra fixar no topo, false pra desafixar.")
                ),
                "required", List.of("vagaId", "fixar")
        );

        Map<String, Object> adicionarVagaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "titulo", Map.of("type", "STRING", "description", "Título da vaga."),
                        "empresa", Map.of("type", "STRING", "description", "Nome da empresa."),
                        "url", Map.of("type", "STRING", "description", "Link da vaga."),
                        "salario", Map.of("type", "STRING", "description", "Salário informado, opcional, texto livre (ex: 'R$ 6.000/mês')."),
                        "modalidade", Map.of("type", "STRING", "description", "Opcional.", "enum", List.of("REMOTO", "HIBRIDO", "PRESENCIAL")),
                        "estado", Map.of("type", "STRING", "description", "Estado por extenso, opcional (ex: 'São Paulo')."),
                        "status", Map.of(
                                "type", "STRING",
                                "description", "Status inicial no funil, opcional. Padrão APLICADA (fluxo normal: 'achei essa vaga fora do Job Radar e já apliquei').",
                                "enum", List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")
                        )
                ),
                "required", List.of("titulo", "empresa", "url")
        );

        Map<String, Object> lembrarPreferenciaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "texto", Map.of("type", "STRING", "description",
                                "A preferência/fato em si, escrito de forma clara e reutilizável (ex: 'só mostrar " +
                                        "vagas remotas', 'não gosta de vagas de recrutamento/RH', 'salário mínimo aceitável é R$ 6000').")
                ),
                "required", List.of("texto")
        );

        Map<String, Object> buscaSemanticaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "consulta", Map.of("type", "STRING", "description",
                                "O que a vaga precisa SER/TER, em linguagem natural (ex: 'vaga de infraestrutura na nuvem', " +
                                        "'algo com front-end moderno', 'liderança técnica de time pequeno'). Não é busca por " +
                                        "palavra exata — é por SIGNIFICADO, útil quando o termo certo não está claro."),
                        "limite", Map.of("type", "INTEGER", "description", "Máximo de vagas a retornar. Padrão 10, máximo 20.")
                ),
                "required", List.of("consulta")
        );

        Map<String, Object> verificarEmailsDeVagasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "dias", Map.of("type", "INTEGER", "description",
                                "Quantos dias pra trás olhar nos emails. Padrão 7, máximo 30.")
                )
        );

        Map<String, Object> lembreteAgendaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description",
                                "ID da vaga relacionada, se houver (peça pra listarVagas primeiro se só tiver título/empresa). Opcional."),
                        "titulo", Map.of("type", "STRING", "description", "Título curto do lembrete."),
                        "descricao", Map.of("type", "STRING", "description", "Descrição/contexto do lembrete. Opcional."),
                        "dueAt", Map.of("type", "STRING", "description",
                                "Data/hora em ISO 8601 com fuso -03:00 (ex: '2026-08-15T09:00:00-03:00'). Opcional — sem isso a Agenda não notifica em hora nenhuma."),
                        "prioridade", Map.of("type", "STRING", "description", "Opcional, padrão NORMAL.",
                                "enum", List.of("LOW", "NORMAL", "HIGH", "CRITICAL"))
                ),
                "required", List.of("titulo")
        );

        Map<String, Object> apagarVagaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga (peça pra listarVagas primeiro se só tiver título/empresa).")
                ),
                "required", List.of("vagaId")
        );

        Map<String, Object> historicoEmpresaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "empresa", Map.of("type", "STRING", "description", "Nome (ou parte do nome) da empresa.")
                ),
                "required", List.of("empresa")
        );

        Map<String, Object> atualizarNotaParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "vagaId", Map.of("type", "INTEGER", "description", "ID da vaga (peça pra listarVagas primeiro se só tiver título/empresa)."),
                        "nota", Map.of(
                                "type", "STRING",
                                "description", "Texto FINAL e completo da nota, não só o trecho novo — se a vaga já tinha " +
                                        "uma nota (veja o campo 'notes' de listarVagas) e o usuário pediu pra " +
                                        "'adicionar' algo, combine o texto antigo com o novo você mesmo antes de " +
                                        "chamar, porque essa ferramenta SUBSTITUI a nota inteira, não anexa sozinha."
                        )
                ),
                "required", List.of("vagaId", "nota")
        );

        Map<String, Object> vagasParadasParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "diasMinimo", Map.of("type", "INTEGER", "description", "Quantos dias sem mudança de status pra considerar 'parada'. Padrão 10.")
                )
        );

        Map<String, Object> perguntarUsuarioParams = Map.of(
                "type", "OBJECT",
                "properties", Map.of(
                        "pergunta", Map.of("type", "STRING", "description", "A pergunta em si, curta e direta."),
                        "opcoes", Map.of(
                                "type", "ARRAY",
                                "items", Map.of("type", "STRING"),
                                "description", "2 a 5 opções curtas que o usuário pode clicar pra responder."
                        ),
                        "permiteOutro", Map.of("type", "BOOLEAN", "description", "true se faz sentido o usuário poder digitar uma resposta livre além das opções.")
                ),
                "required", List.of("pergunta", "opcoes")
        );

        return List.of(
                new GeminiService.FunctionDeclaration("listarVagas",
                        "Lista vagas do usuário, opcionalmente filtradas por status (ex: em andamento, aplicadas), período ou busca por texto. " +
                                "Cada vaga vem com o campo 'notes' — a anotação pessoal que o próprio usuário escreveu naquele card " +
                                "(ex: 'preciso enviar currículo amanhã', 'só esperar retorno', 'fazer teste lógico'). Use esse campo " +
                                "pra responder qualquer pergunta sobre pendência/próximo passo/o que falta em cada candidatura — é a " +
                                "fonte real, não invente etapas genéricas de plataforma quando a nota já diz o que falta (ou que não falta nada).",
                        listarVagasParams),
                new GeminiService.FunctionDeclaration("resumoFunil",
                        "Estatísticas gerais do funil de vagas do usuário. Não usa IA, é gratuito. Cada vaga conta em " +
                                "só um bucket (aplicadas/emAndamento/recusadas são mutuamente exclusivos e batem " +
                                "exatamente com as abas do app) — se o usuário perguntar 'quantas vezes já apliquei " +
                                "no total' (contando as que hoje estão em andamento ou já foram recusadas), use o " +
                                "campo totalHistoricoAplicadas, não o campo aplicadas.",
                        semParametros),
                new GeminiService.FunctionDeclaration("compatibilidadeComVagasRecentes",
                        "Compara o perfil/currículo salvo do usuário com vagas publicadas recentemente NO FEED GERAL " +
                                "(usa IA de verdade, gasta cota) — não é pra vagas de um status do funil do usuário, " +
                                "pra isso use compatibilidadeComVagasDoFunil.",
                        compatibilidadeParams),
                new GeminiService.FunctionDeclaration("compatibilidadeComVagasDoFunil",
                        "Compara o perfil/currículo salvo do usuário com as vagas de um STATUS ESPECÍFICO DO FUNIL " +
                                "DELE (ex: 'minhas vagas interessadas', 'minhas aplicadas', 'em andamento') e avalia " +
                                "compatibilidade real (usa IA de verdade, gasta cota — uma chamada por vaga, até o " +
                                "limite). Use esta ferramenta, não compatibilidadeComVagasRecentes, sempre que o " +
                                "pedido mencionar um status do funil do usuário em vez de 'vagas recentes' em geral.",
                        compatibilidadeFunilParams),
                new GeminiService.FunctionDeclaration("estimativaSalarialDeVagas",
                        "Estima a faixa salarial de um grupo de vagas (opcionalmente filtradas por status do funil " +
                                "ou busca) e devolve os números pra montar uma comparação visual — use quando o " +
                                "usuário pedir pra comparar/analisar SALÁRIO de várias vagas de uma vez (ex: 'estima " +
                                "o salário dessas vagas', 'quanto pagam essas 5 vagas que apliquei'). NÃO usa IA " +
                                "generativa (é um modelo de regressão treinado, não gasta cota do Gemini) — pode " +
                                "usar sem economia especial, dentro do limite.",
                        salarioVagasParams),
                new GeminiService.FunctionDeclaration("gerarCartaDeApresentacao",
                        "Gera uma carta de apresentação personalizada pra uma vaga específica — a MESMA função do " +
                                "botão 'Carta' de cada card. USA IA de verdade, gasta cota — só chame quando o usuário " +
                                "pedir claramente uma carta pra UMA vaga identificada.",
                        cartaParams),
                new GeminiService.FunctionDeclaration("metricasDeDesempenho",
                        "Métricas de desempenho das candidaturas: taxa de resposta, tempo médio até virar 'em " +
                                "andamento' ou ser recusada, aplicações por semana. Diferente de resumoFunil (que só " +
                                "conta quantas vagas tem em cada bucket agora) — essa fala de VELOCIDADE/EFICÁCIA ao " +
                                "longo do tempo. Use pra 'como anda meu desempenho', 'minha taxa de resposta'. Não usa IA.",
                        semParametrosMetricas),
                new GeminiService.FunctionDeclaration("vagasComPrazoProximo",
                        "Vagas (não recusadas) cujo prazo de candidatura fecha em breve — use pra 'quais vagas fecham " +
                                "essa semana', 'o que tá acabando o prazo'. Não usa IA, só compara a data de expiração " +
                                "salva com hoje (vaga sem prazo informado não aparece).",
                        prazoParams),
                new GeminiService.FunctionDeclaration("detectarDuplicatas",
                        "Acha grupos de vagas prováveis duplicatas (mesma empresa + título muito parecido, vindas de " +
                                "fontes diferentes) — use pra 'tem vaga duplicada', 'será que essas duas são a mesma " +
                                "vaga'. Não usa IA generativa (só similaridade de texto), pode dar falso positivo " +
                                "ocasional — avise que é só um indício, não certeza.",
                        duplicatasParams),
                new GeminiService.FunctionDeclaration("desempenhoPorFonte",
                        "Cruza a fonte de cada vaga (Gupy, Remotive, etc.) com o quanto avançou no funil — use pra " +
                                "'qual fonte dá mais retorno', 'de onde vêm minhas vagas que avançam mais'. Não usa IA.",
                        fontesParams),
                new GeminiService.FunctionDeclaration("historicoDaEmpresa",
                        "Busca vagas antigas (de qualquer status, inclusive recusadas) da MESMA empresa — use quando " +
                                "o usuário perguntar 'já apliquei nessa empresa antes' ou quando uma vaga nova aparecer " +
                                "e valer avisar sobre histórico anterior com aquela empresa. Não usa IA.",
                        historicoEmpresaParams),
                new GeminiService.FunctionDeclaration("oQueFazerAgora",
                        "Monta um panorama pra responder 'o que eu faço agora', 'quais são minhas prioridades hoje', " +
                                "'o que preciso resolver primeiro' — combina candidaturas paradas há muito tempo, " +
                                "prazos de candidatura próximos do fim, e (se houver perfil salvo) as vagas novas não " +
                                "vistas com melhor match heurístico. NÃO usa IA. A ferramenta só traz os dados crus — " +
                                "monte VOCÊ o resumo priorizado em texto (ex: 'top 3 do dia'), não repita cada lista " +
                                "crua igual um relatório.",
                        semParametros),
                new GeminiService.FunctionDeclaration("compararStackComMercado",
                        "Compara as tecnologias do perfil/currículo salvo do usuário com as tags mais frequentes em " +
                                "TODO o feed de vagas ativas — use pra 'o que eu deveria aprender', 'quais tecnologias " +
                                "o mercado mais pede que eu não tenho', 'meu perfil está alinhado com o mercado?'. NÃO " +
                                "usa IA (é contagem de tags, sobreposição simples) — avise que é só um indício de " +
                                "demanda dentro do que o Job Radar já coletou, não uma pesquisa de mercado de verdade. " +
                                "Exige perfil salvo em Configurações.",
                        semParametros),
                new GeminiService.FunctionDeclaration("detalharVagas",
                        "Traz TODOS os dados salvos de uma ou mais vagas específicas (salário informado/estimado, " +
                                "modalidade, cidade/estado, tags, prazo, nota pessoal, status atual) — use quando o " +
                                "usuário pedir detalhe de UMA vaga específica ('me explica a vaga X') ou pra COMPARAR " +
                                "duas ou mais vagas específicas lado a lado ('compara a vaga X com a Y'). Não usa IA " +
                                "generativa, não gasta cota. Precisa do id da vaga — se só tiver título/empresa, " +
                                "chame listarVagas primeiro com busca pra achar o id certo.",
                        detalharVagasParams),
                new GeminiService.FunctionDeclaration("vagasParecidas",
                        "Acha outras vagas do feed parecidas com uma vaga de referência (mesma stack/tags, senioridade " +
                                "e modalidade) — use quando o usuário pedir algo tipo 'acha mais vagas como essa' ou " +
                                "'tem outras parecidas com a que eu apliquei'. Não usa IA generativa, é só comparação " +
                                "de tags salvas, não gasta cota. Precisa do id da vaga de referência — se só tiver " +
                                "título/empresa, chame listarVagas primeiro com busca pra achar o id certo.",
                        vagasParecidasParams),
                new GeminiService.FunctionDeclaration("vagasParadas",
                        "Acha candidaturas ATIVAS (aplicadas ou em andamento) que estão sem mudança de status há muito " +
                                "tempo — use pra 'quais vagas estão paradas', 'o que está sem retorno há mais tempo', " +
                                "'quais candidaturas esfriaram'. Não usa IA, é só data de status × hoje. NÃO confundir " +
                                "com resumoFunil (que só conta quantas tem em cada bucket, sem falar de tempo parado).",
                        vagasParadasParams),
                new GeminiService.FunctionDeclaration("fixarVaga",
                        "Fixa (ou desfixa) uma vaga no topo da lista — mesma ação do pin ⭐ de cada card. Muda dado " +
                                "de verdade. Só numa vaga claramente identificada.",
                        fixarVagaParams),
                new GeminiService.FunctionDeclaration("adicionarVagaManual",
                        "Adiciona uma vaga achada fora do Job Radar (LinkedIn, indicação, etc.) — mesma função do " +
                                "botão '➕ Adicionar vaga'. Muda dado de verdade (cria uma vaga nova, ou atualiza se já " +
                                "existir uma com a mesma URL). Peça título, empresa e link antes de chamar se o " +
                                "usuário não tiver dado os três.",
                        adicionarVagaParams),
                new GeminiService.FunctionDeclaration("lembrarPreferencia",
                        "Guarda uma preferência/fato que o usuário disse explicitamente (ou deu a entender com " +
                                "clareza) que quer que você lembre nas PRÓXIMAS conversas, não só nessa — ex: 'só me " +
                                "mostra vaga remota', 'não gosto de vaga com processo muito longo', 'meu salário " +
                                "mínimo é X'. Não usa isso pra fatos triviais de uma mensagem só. Fica salvo no " +
                                "navegador do usuário (não é banco de dados) e volta pra você automaticamente em " +
                                "toda conversa futura — não precisa (nem pode) 'listar' o que já foi salvo, isso " +
                                "já aparece sozinho na sua instrução quando relevante.",
                        lembrarPreferenciaParams),
                new GeminiService.FunctionDeclaration("buscarVagasPorSignificado",
                        "Busca vagas por SIGNIFICADO (embeddings), não por palavra exata — use quando listarVagas com " +
                                "'busca' (substring literal) não é o jeito certo, tipo 'vaga de infraestrutura' precisando " +
                                "achar 'DevOps/SRE/Cloud' mesmo sem a palavra 'infra' aparecer. NÃO usa a IA generativa do " +
                                "chat (é um modelo de embedding, cota separada) — pode chamar sem economia especial. Vaga " +
                                "que ainda não foi processada por essa feature (recente/backfill pendente) não aparece no " +
                                "resultado — se vier vazio, tente listarVagas com busca por palavra-chave como alternativa.",
                        buscaSemanticaParams),
                new GeminiService.FunctionDeclaration("verificarEmailsDeVagas",
                        "Vasculha os emails de ALERTA DE VAGA (LinkedIn, Glassdoor) na caixa de entrada do usuário " +
                                "(só-leitura, nunca escreve/apaga nada no email) e devolve as vagas achadas pra ele " +
                                "escolher quais importar pro Job Radar — NADA é adicionado automaticamente, é sempre " +
                                "uma lista pra revisão. Use quando o usuário pedir algo tipo 'vê se tem vaga nova no " +
                                "meu email', 'puxa as vagas que chegaram por email', 'analisa esse email de vaga que " +
                                "recebi'. Exige o Gmail conectado em Configurações antes — se não estiver, a " +
                                "ferramenta já avisa isso na resposta. NÃO cobre todo site de vaga que existe — só " +
                                "os domínios que o Job Radar reconhece hoje (avise o usuário se ele mencionar um site " +
                                "diferente que a ferramenta claramente não suporta).",
                        verificarEmailsDeVagasParams),
                new GeminiService.FunctionDeclaration("criarLembreteNaAgenda",
                        "Monta a PROPOSTA de um lembrete/tarefa pra Agenda Pessoal (app separado) — NÃO cria nada " +
                                "de verdade, o backend do Job Radar nunca fala com a Agenda diretamente. A interface " +
                                "mostra um card com a proposta e um botão que o usuário clica pra criar (ou conectar " +
                                "a Agenda primeiro). Use pra pedidos tipo 'me lembra de dar follow-up nessa vaga " +
                                "sexta', 'cria uma tarefa pra eu revisar o currículo amanhã'.",
                        lembreteAgendaParams),
                new GeminiService.FunctionDeclaration("apagarVaga",
                        "APAGA a vaga do banco de dados PRA SEMPRE, sem volta — não é mudar status pra Recusada, é " +
                                "remover o registro inteiro. SEMPRE chame perguntarUsuario pra confirmar antes de " +
                                "chamar essa (ver guidance na SYSTEM_INSTRUCTION). Só numa vaga claramente identificada.",
                        apagarVagaParams),
                new GeminiService.FunctionDeclaration("marcarStatusDeVaga",
                        "Move uma vaga específica pra outro status do funil (ex: marcar como aplicada, interessado, " +
                                "recusada) — muda dado de verdade (junto com atualizarNotaDeVaga, as únicas duas que " +
                                "escrevem — as outras só leem). Use " +
                                "SÓ quando o usuário pedir isso de forma clara e específica sobre UMA vaga identificada " +
                                "(por id, ou por título/empresa já confirmado via listarVagas antes) — nunca chame " +
                                "isso 'no escuro' ou em lote sem o usuário ter apontado exatamente qual vaga. Se " +
                                "houver ambiguidade sobre qual vaga (mais de uma batendo com a busca), pergunte antes " +
                                "de chamar, não escolha sozinho.",
                        marcarStatusParams),
                new GeminiService.FunctionDeclaration("atualizarNotaDeVaga",
                        "Escreve/atualiza a anotação pessoal (campo 'notes') de uma vaga específica — a MESMA ferramenta " +
                                "que o botão '📝 Adicionar nota' de cada card. Segunda ferramenta que MUDA dado de " +
                                "verdade (a primeira é marcarStatusDeVaga) — mesma regra: só numa vaga claramente " +
                                "identificada, nunca 'no escuro'. SUBSTITUI a nota inteira — se o usuário pedir pra " +
                                "'adicionar' algo a uma nota que já existe, primeiro confira o texto atual (campo " +
                                "'notes' de listarVagas) e mande o texto final já combinado, não só o trecho novo.",
                        atualizarNotaParams),
                new GeminiService.FunctionDeclaration("perguntarUsuario",
                        "Faz uma pergunta de múltipla escolha pro usuário quando você tem uma dúvida real que só ele " +
                                "resolve (ex: qual entre duas vagas parecidas, ou confirmar algo antes de mudar status " +
                                "de verdade). Diferente das outras ferramentas, essa PAUSA a conversa — você só continua " +
                                "depois que o usuário escolher uma opção (ou digitar a própria resposta, se permiteOutro). " +
                                "Use com moderação: só quando a ambiguidade realmente impede continuar direito.",
                        perguntarUsuarioParams)
        );
    }
}

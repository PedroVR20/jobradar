package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Chat livre do Jarvis — diferente da versão anterior (roteamento por
 * palavra-chave, ver commit anterior), aqui é o próprio Gemini que decide,
 * a partir da mensagem do usuário em linguagem natural, se e quais
 * ferramentas chamar antes de responder ("function calling" de verdade).
 * "Dê uma olhada nas minhas vagas em andamento" agora funciona sem precisar
 * bater numa frase pré-cadastrada.
 *
 * <p>Ferramentas disponíveis: {@link #listarVagas}, {@link #resumoFunil},
 * e {@link #compatibilidade} (reaproveita {@link JarvisAssistantService}
 * inteiro, inclusive o pré-filtro sem IA + limite de 5 chamadas reais —
 * ver lá o porquê). A instrução de sistema pede pro modelo ser econômico:
 * só chamar {@code compatibilidadeComVagasRecentes} quando o pedido for
 * genuinamente sobre compatibilidade, não pra qualquer pergunta.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JarvisChatService {

    private final GeminiService geminiService;
    private final JobRepository jobRepository;
    private final JarvisAssistantService jarvisAssistantService;
    private final MatchScoreService matchScoreService;
    private final SalaryPredictionService salaryPredictionService;
    private final JobStatusService jobStatusService;
    private final CoverLetterService coverLetterService;
    private final SeniorityClassifier seniorityClassifier;

    // compatibilidadeComVagasDoFunil analisa DIRETO (sem pré-filtro), porque
    // o grupo já vem pequeno por natureza (é o funil curado do próprio
    // usuário) — mas ainda precisa de um teto rígido: um status como "NOVA"
    // pode ter milhares de vagas, e sem isso um pedido mal-entendido
    // estouraria a cota de IA analisando tudo. Ver bug real reportado: pediu
    // pra analisar 4 vagas da aba Interessado, o modelo usou a ferramenta
    // errada (a de vagas recentes do feed geral) e varreu 3279 vagas.
    private static final int MAX_COMPAT_FUNIL = 15;
    private static final int DEFAULT_COMPAT_FUNIL = 10;

    // estimativaSalarialDeVagas NÃO gasta IA (é o modelo de regressão puro
    // Java, ver SalaryPredictionService) — pode ter um teto bem mais folgado
    // que as ferramentas de compatibilidade sem risco de estourar cota.
    private static final int MAX_SALARIO_VAGAS = 25;
    private static final int DEFAULT_SALARIO_VAGAS = 15;

    // Mercado de vagas/salário muda com o tempo — um modelo treinado há mais
    // de 90 dias provavelmente já não reflete a faixa atual tão bem quanto
    // logo depois do treino, mesmo sem nada estar "quebrado" tecnicamente.
    private static final long MODELO_SALARIO_DIAS_DESATUALIZADO = 90;

    // Limite de rounds de function-calling por mensagem — evita loop
    // infinito ou uma mensagem só disparando dezenas de chamadas de ferramenta.
    private static final int MAX_TOOL_ROUNDS = 4;
    // Quantas mensagens do histórico mandar de volta a cada chamada —
    // sem isso o payload (e o custo em tokens) cresceria sem limite numa
    // conversa longa.
    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String SYSTEM_INSTRUCTION = """
            Você é o Hunter, assistente do Job Radar — um app pessoal de rastreamento
            de vagas de emprego. Responda sempre em português do Brasil, tom direto e
            útil, sem enrolação. Se perguntarem seu nome, é Hunter. Isso vale também
            pro seu raciocínio interno (o "pensamento" antes da resposta, quando a
            interface mostra) — pense em português do Brasil, não em inglês.

            Use as ferramentas disponíveis sempre que a pergunta exigir dado real
            (lista de vagas, estatísticas do funil, compatibilidade com o perfil) —
            nunca invente números, vagas ou empresas que você não buscou de verdade
            através de uma ferramenta.

            Pra perguntas sobre pendência/próximo passo/o que falta em cada
            candidatura (ex: "quais preciso fazer teste ainda", "o que falta pra
            fechar"), a fonte de verdade é o campo 'notes' de cada vaga retornado
            por listarVagas — é a anotação que o próprio usuário escreveu naquele
            card. Responda com base no que cada nota diz (ou avise que a vaga não
            tem nota registrada), nunca com passos genéricos de plataforma
            (Gupy/Eureca/etc.) que você não confirmou pela nota real.

            Duas ferramentas fazem compatibilidade REAL (IA de verdade, gasta cota
            limitada do free tier) — escolher a errada gasta cota analisando vagas
            que o usuário nem pediu:
            - compatibilidadeComVagasRecentes: pra "vagas novas/recentes" em geral,
              o FEED INTEIRO de vagas (filtra só por dias). Faz um pré-filtro sem IA
              e só manda pra IA as 5 mais promissoras — apropriado porque esse
              conjunto pode ter milhares de vagas.
            - compatibilidadeComVagasDoFunil: pra quando o usuário se refere a um
              STATUS ESPECÍFICO DO FUNIL DELE — "minhas vagas interessadas", "minhas
              vagas aplicadas", "vagas em andamento", etc. Analisa direto (sem
              pré-filtro), porque esses grupos normalmente já são pequenos e
              curados pelo próprio usuário. NUNCA use compatibilidadeComVagasRecentes
              pra um pedido sobre status do funil — são conjuntos diferentes, e a
              recentes varre o feed geral, não o que o usuário marcou.
            Nenhuma das duas deve ser chamada pra perguntas simples tipo "quantas
            vagas eu tenho" ou "quais vagas apliquei" (essas usam listarVagas ou
            resumoFunil, que são gratuitas e não fazem match de compatibilidade).

            estimativaSalarialDeVagas: pra quando o usuário quer COMPARAR ou
            ENTENDER salário de várias vagas de uma vez (ex: "estima o salário
            dessas vagas que apliquei", "quanto pagam essas vagas de backend",
            "compara a faixa salarial das minhas vagas em andamento"). Usa o
            modelo de regressão próprio do Job Radar, não gasta cota de IA — pode
            chamar sem economia especial, dentro do limite da ferramenta. Não use
            listarVagas pra esse tipo de pedido (não traz estimativa nenhuma).
            O resultado vem com margemErroPercent (erro médio real do modelo,
            normalmente ~40%) — a interface já mostra essa margem em cada card,
            mas SEMPRE mencione em texto que é uma estimativa aproximada, nunca
            trate o número como exato. Se vier modeloDesatualizado=true, avise
            que o modelo não é retreinado há mais de modeloDiasDesdeTreino dias
            e pode estar defasado em relação ao mercado atual.

            Sobre dashboards: quando uma ferramenta te devolve vários números
            comparáveis sobre um grupo de vagas (score de compatibilidade,
            estimativa de salário, etc.), a interface do Job Radar já monta
            automaticamente um resumo visual (cards de estatística + barras) a
            partir do resultado da ferramenta — você não precisa (e não consegue)
            desenhar esse dashboard, ele aparece sozinho. Isso vale pra QUALQUER
            ferramenta que devolva uma lista de vagas com um valor numérico por
            vaga, não só compatibilidade — é um padrão geral da interface, não
            algo específico de um assunto. Seu trabalho é só escolher a
            ferramenta certa pro que o usuário pediu e comentar em texto o que os
            números já visíveis no card não dizem sozinhos (ex: "a faixa ficou
            concentrada entre 6 e 8 mil, só a vaga X destoa pra cima").

            Sobre "pontos a desenvolver": quando compatibilidadeComVagasRecentes ou
            compatibilidadeComVagasDoFunil devolvem pontosFaltando pra uma vaga, a
            interface já mostra um botão "📚 Plano de ação" do lado de cada ponto —
            clicando, o usuário gera um plano de estudo detalhado pra aquele ponto
            específico, sem precisar te pedir nada. Você NÃO gera esse plano (não
            existe ferramenta pra isso) — seu papel é só, no texto, mencionar de
            passagem que dá pra clicar no botão em qualquer ponto que quiser
            aprofundar (só na primeira vez que aparecer numa conversa, não repita
            isso toda resposta).

            detalharVagas: pra "me explica a vaga X" (1 id) ou "compara a vaga X
            com a Y" (2+ ids) — traz todo dado salvo da(s) vaga(s), sem IA. Precisa
            do id; se o usuário só deu título/empresa, chame listarVagas primeiro
            com busca pra achar o id certo antes de chamar detalharVagas.

            vagasParecidas: pra "acha mais vagas como essa"/"parecidas com a que
            eu apliquei" — compara tags salvas, sem IA. Mesma regra: precisa do
            id, ache com listarVagas primeiro se só tiver título/empresa.

            marcarStatusDeVaga e atualizarNotaDeVaga são as ÚNICAS ferramentas
            que MUDAM dado de verdade (todas as outras só leem) — a primeira
            move o status no funil, a segunda escreve/substitui a anotação
            pessoal da vaga. As duas seguem a mesma regra: só chame quando o
            usuário pedir claramente sobre UMA vaga específica já identificada
            (id, ou título/empresa que você confirmou antes com listarVagas).
            Se a busca por título/empresa achar mais de uma vaga batendo,
            PERGUNTE qual antes de chamar — nunca escolha sozinho nem chame em
            lote pra várias vagas de uma vez. atualizarNotaDeVaga SUBSTITUI a
            nota inteira (não anexa sozinha) — se o pedido for "adicionar" a
            uma nota que já existe, confira o texto atual (campo 'notes' de
            listarVagas) e mande o texto final já combinado. Depois de
            qualquer uma das duas, confirme em texto o que mudou — a
            interface já recarrega a lista sozinha, mas confirmar por escrito
            evita dúvida sobre o que exatamente mudou.

            apagarVaga APAGA A VAGA PRA SEMPRE, sem volta — antes de chamar,
            SEMPRE use perguntarUsuario pra confirmar com o usuário (algo como
            "Confirma que quer apagar 'Título — Empresa'? Isso não pode ser
            desfeito.", opções ["Sim, apagar", "Não, cancelar"], sem permitir
            outro). Só chame apagarVaga de verdade depois que a resposta
            confirmar. Nunca apague sem essa confirmação, mesmo que o pedido
            pareça claro.

            fixarVaga (fixar/desafixar no topo) e adicionarVagaManual (achou
            uma vaga fora do Job Radar e já aplicou, quer registrar) seguem a
            mesma regra de identificação clara das outras ferramentas que
            escrevem — sem confirmação extra, essas são reversíveis.

            "Desfazer"/"desfaz isso"/"volta atrás": se o usuário pedir logo
            depois de uma ferramenta que escreveu algo, procure no histórico
            recente o resultado dessa ferramenta (statusAntes/notaAntes/fixada
            já vêm nele) e chame a MESMA ferramenta de novo com o valor
            anterior pra reverter — não existe uma ferramenta "desfazer"
            separada. Se a última ação foi adicionarVagaManual, "desfazer"
            significa chamar apagarVaga na vaga que acabou de criar (ainda
            assim confirme antes, é irreversível). Se não achar nenhuma ação
            recente no histórico pra desfazer, diga isso claramente.

            Se o usuário anexar uma imagem (print de tela) na mensagem: descreva
            objetivamente o que reconhece nela (título da vaga, empresa, status/aba
            aparente, badges visíveis) e, se conseguir ler título e/ou empresa com
            confiança, chame listarVagas com esse texto no parâmetro busca pra
            confirmar contra o dado real e trazer o card de verdade (status,
            anotações, link) — NUNCA afirme status, nota ou qualquer dado da vaga só
            pelo que "parece" na imagem sem confirmar via listarVagas. Se a busca não
            achar nada compatível, diga isso e pergunte mais detalhes, não invente.

            perguntarUsuario: quando você tem uma dúvida real que só o usuário
            resolve (ex: "compara a vaga X" achou duas empresas diferentes com
            título parecido, ou marcarStatusDeVaga ficou ambíguo sobre qual vaga),
            chame essa ferramenta em vez de adivinhar ou perguntar só em texto —
            ela mostra botões clicáveis de verdade pro usuário escolher (mais um
            campo livre se fizer sentido ter "outra opção"). Use com moderação: só
            quando a ambiguidade é real e impede continuar direito, não pra
            confirmar coisa óbvia. Depois que o usuário responder, a pergunta e a
            resposta aparecem no histórico normalmente — continue o raciocínio
            considerando a resposta dada.

            Se o usuário perguntar algo sem relação com o Job Radar (vagas,
            candidatura, perfil, salário), explique educadamente que você só ajuda
            com isso.

            Importante sobre o formato da resposta: quando você chama listarVagas,
            compatibilidadeComVagasRecentes, compatibilidadeComVagasDoFunil ou
            estimativaSalarialDeVagas, a interface já mostra cada vaga retornada
            como um card visual (título, empresa, status/score/estimativa, nota) —
            não repita esse mesmo texto agora, com título e empresa de novo,
            listando de novo cada vaga. Sua resposta em texto deve ser só a análise
            síntese (ex: "2 vagas têm pendência, as outras 8 só aguardam retorno")
            e comentar casos específicos apenas se agregar algo que o card não
            mostra. Escreva como uma mensagem de chat curta — pode usar **negrito**
            e listas com "-", mas não use títulos markdown (#, ##, ###) nem
            separadores "---", isso é formatação de documento, não de chat.
            """;

    // role: "user" | "assistant". imageMimeType/imageBase64 só fazem sentido
    // numa mensagem "user" — usado quando o usuário anexa um print no chat
    // (ver GeminiService.userTurnWithImage). Construtor de 2 args mantém
    // compatível todo código que já criava ChatMessage sem imagem.
    public record ChatMessage(String role, String text, String imageMimeType, String imageBase64) {
        public ChatMessage(String role, String text) {
            this(role, text, null, null);
        }

        public boolean temImagem() {
            return imageMimeType != null && !imageMimeType.isBlank() && imageBase64 != null && !imageBase64.isBlank();
        }
    }

    public record ToolResultPayload(String tool, Object data) {}

    // Pergunta interativa que o Hunter decidiu fazer (ferramenta
    // perguntarUsuario) — diferente de toda outra ferramenta, essa NÃO
    // resolve na hora: a conversa "pausa" aqui, o frontend mostra os botões,
    // e só quando o usuário escolhe uma opção (virando uma mensagem normal
    // de novo) é que o Hunter continua. Não precisa guardar nenhum estado
    // de function-call/thoughtSignature entre requisições pra isso funcionar
    // — cada chamada a /assistant/chat já reconstrói o histórico inteiro do
    // zero a partir do texto puro das mensagens anteriores (ver conversar()),
    // então a pergunta + resposta escolhida viram só mais duas mensagens
    // normais na próxima chamada, sem nenhum encanamento especial.
    public record PendingQuestion(String pergunta, List<String> opcoes, boolean permiteOutro) {}

    // thinking: raciocínio real do Gemini antes da resposta final (ver
    // GeminiService.chat(..., includeThoughts=true)) — null quando a rodada
    // que produziu a resposta não veio com pensamento (a API nem sempre
    // manda, mesmo pedindo) ou quando o modelo respondeu direto sem "pensar"
    // visivelmente numa pergunta simples.
    public record ChatOutcome(String reply, String thinking, List<ToolResultPayload> toolResults, String errorMessage,
                               boolean rateLimited, PendingQuestion pendingQuestion) {
        public ChatOutcome(String reply, String thinking, List<ToolResultPayload> toolResults, String errorMessage, boolean rateLimited) {
            this(reply, thinking, toolResults, errorMessage, rateLimited, null);
        }

        public boolean ok() {
            return errorMessage == null;
        }
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile) {
        return conversar(historico, candidateProfile, null, null);
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext) {
        return conversar(historico, candidateProfile, feedbackContext, null);
    }

    // feedbackContext: 👍/👎 salvos em ⚙️/nos cards de vaga (useAiFeedback no
    // frontend) pras análises de compatibilidade — antes só chegava nos
    // endpoints diretos (match-score, learning-plan), nunca no chat. Agora o
    // Hunter usa o MESMO histórico de feedback, então o que o usuário avalia
    // ali também refina o que o Hunter mostra na conversa.
    //
    // memoryContext: diferente de feedbackContext (que é sobre ESTILO das
    // respostas) — são fatos/preferências que o próprio usuário pediu
    // explicitamente pra lembrar (ferramenta lembrarPreferencia), tipo "só
    // me mostra vaga remota" ou "não quero nada de SP". Vive inteiramente no
    // localStorage do navegador (useHunterMemory) — o backend não persiste
    // nada, só recebe a lista pronta a cada requisição e injeta na instrução.
    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext, String memoryContext) {
        if (historico == null || historico.isEmpty()) {
            return new ChatOutcome(null, null, List.of(), "Mensagem vazia.", false);
        }

        List<ChatMessage> recortado = historico.size() > MAX_HISTORY_MESSAGES
                ? historico.subList(historico.size() - MAX_HISTORY_MESSAGES, historico.size())
                : historico;

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage m : recortado) {
            if ("user".equals(m.role())) {
                // Imagem só é anexada na mensagem mais recente (ver
                // handleSend no frontend) — não reenviamos prints antigos a
                // cada rodada, senão o payload/custo cresce sem limite numa
                // conversa longa.
                contents.add(m.temImagem()
                        ? geminiService.userTurnWithImage(m.text(), m.imageMimeType(), m.imageBase64())
                        : geminiService.userTurn(m.text()));
            } else {
                contents.add(geminiService.modelTurn(m.text()));
            }
        }

        List<GeminiService.FunctionDeclaration> tools = buildTools();
        List<ToolResultPayload> toolResults = new ArrayList<>();

        // feedbackContext antes só ia pras sub-chamadas de compatibilidade
        // (executarCompatibilidade/Funil) — a resposta conversacional em si
        // nunca via esse histórico, então o 👍/👎 que o usuário dava numa
        // resposta comum do Hunter não tinha efeito nenhum. Anexado aqui na
        // instrução de sistema, vale pra QUALQUER resposta da conversa.
        String systemInstructionComFeedback = feedbackContext != null && !feedbackContext.isBlank()
                ? SYSTEM_INSTRUCTION + "\n\nFeedback que o usuário já deu sobre suas respostas anteriores " +
                        "(curtiu/não curtiu + comentário) — leve em conta pra ajustar tom, formato ou nível " +
                        "de detalhe das próximas respostas:\n" + feedbackContext
                : SYSTEM_INSTRUCTION;
        // Preferências que o usuário pediu EXPLICITAMENTE pra lembrar (não é
        // feedback de estilo, são fatos/regras reais pra aplicar sempre que
        // relevante — ex: filtro implícito de local/modalidade em listarVagas).
        String systemInstructionFinal = memoryContext != null && !memoryContext.isBlank()
                ? systemInstructionComFeedback + "\n\nPreferências que o usuário já pediu pra você lembrar entre " +
                        "conversas (aplique sempre que fizer sentido pro pedido atual, sem precisar que ele repita):\n" +
                        memoryContext
                : systemInstructionComFeedback;

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            GeminiService.ChatResult resultado = geminiService.chat(systemInstructionFinal, contents, tools, true);
            if (!resultado.ok()) {
                return new ChatOutcome(null, null, toolResults, resultado.errorMessage(), resultado.rateLimited());
            }
            if (!resultado.isFunctionCall()) {
                return new ChatOutcome(resultado.text(), resultado.thinking(), toolResults, null, false);
            }

            GeminiService.FunctionCallRequest chamada = resultado.functionCall();
            log.info("Jarvis: chamando ferramenta '{}' com args {}", chamada.name(), chamada.args());

            // perguntarUsuario é diferente de todas as outras: não resolve
            // sozinha, então NÃO adiciona nada em contents nem continua o
            // loop — a conversa pausa aqui de verdade, devolvendo a pergunta
            // pro frontend. Ver comentário completo no record PendingQuestion.
            if ("perguntarUsuario".equals(chamada.name())) {
                return new ChatOutcome(null, resultado.thinking(), toolResults, null, false, extrairPendingQuestion(chamada.args()));
            }

            Object dado = executarFerramenta(chamada, candidateProfile, feedbackContext);
            toolResults.add(new ToolResultPayload(chamada.name(), dado));

            contents.add(geminiService.buildFunctionCallPart(chamada));
            contents.add(geminiService.buildFunctionResponsePart(chamada.name(), Map.of("result", dado)));
        }

        return new ChatOutcome(
                "Essa pergunta pediu mais passos do que eu consigo resolver de uma vez — tenta reformular de um jeito mais direto?",
                null, toolResults, null, false
        );
    }

    // ===================== Definição das ferramentas =====================

    private List<GeminiService.FunctionDeclaration> buildTools() {
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

    @SuppressWarnings("unchecked")
    private PendingQuestion extrairPendingQuestion(Map<String, Object> args) {
        String pergunta = args.get("pergunta") instanceof String s ? s : "Pode confirmar o que você quer dizer?";
        List<String> opcoes = args.get("opcoes") instanceof List<?> l
                ? (List<String>) l.stream().filter(String.class::isInstance).toList()
                : List.of();
        boolean permiteOutro = args.get("permiteOutro") instanceof Boolean b && b;
        return new PendingQuestion(pergunta, opcoes, permiteOutro);
    }

    private Object executarFerramenta(GeminiService.FunctionCallRequest chamada, String candidateProfile, String feedbackContext) {
        return switch (chamada.name()) {
            case "listarVagas" -> executarListarVagas(chamada.args());
            case "resumoFunil" -> executarResumoFunil();
            case "compatibilidadeComVagasRecentes" -> executarCompatibilidade(chamada.args(), candidateProfile, feedbackContext);
            case "compatibilidadeComVagasDoFunil" -> executarCompatibilidadeFunil(chamada.args(), candidateProfile, feedbackContext);
            case "estimativaSalarialDeVagas" -> executarEstimativaSalarial(chamada.args());
            case "gerarCartaDeApresentacao" -> executarGerarCarta(chamada.args(), candidateProfile, feedbackContext);
            case "metricasDeDesempenho" -> executarMetricasDeDesempenho();
            case "vagasComPrazoProximo" -> executarVagasComPrazoProximo(chamada.args());
            case "detectarDuplicatas" -> executarDetectarDuplicatas();
            case "desempenhoPorFonte" -> executarDesempenhoPorFonte();
            case "historicoDaEmpresa" -> executarHistoricoDaEmpresa(chamada.args());
            case "oQueFazerAgora" -> executarOQueFazerAgora(candidateProfile);
            case "compararStackComMercado" -> executarCompararComMercado(candidateProfile);
            case "detalharVagas" -> executarDetalharVagas(chamada.args());
            case "vagasParecidas" -> executarVagasParecidas(chamada.args());
            case "vagasParadas" -> executarVagasParadas(chamada.args());
            case "fixarVaga" -> executarFixarVaga(chamada.args());
            case "adicionarVagaManual" -> executarAdicionarVagaManual(chamada.args());
            case "apagarVaga" -> executarApagarVaga(chamada.args());
            case "lembrarPreferencia" -> executarLembrarPreferencia(chamada.args());
            case "marcarStatusDeVaga" -> executarMarcarStatus(chamada.args());
            case "atualizarNotaDeVaga" -> executarAtualizarNota(chamada.args());
            default -> Map.of("erro", "Ferramenta desconhecida: " + chamada.name());
        };
    }

    // ===================== Implementação das ferramentas =====================

    private Object executarListarVagas(Map<String, Object> args) {
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        Integer dias = args.get("dias") instanceof Number n ? n.intValue() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        Integer salarioMinimo = args.get("salarioMinimo") instanceof Number n ? n.intValue() : null;
        int limite = args.get("limite") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;

        LocalDateTime postedAfter = dias != null && dias > 0 ? LocalDateTime.now().minusDays(dias) : null;

        List<Job> filtradas = jobRepository.findAll().stream()
                .filter(j -> statusBate(j, status))
                .filter(j -> postedAfter == null || (j.getPostedAt() != null && j.getPostedAt().isAfter(postedAfter)))
                .filter(j -> busca == null || contemBusca(j, busca))
                // Estimativa só é calculada quando o filtro de salário é usado
                // (é barato — modelo de regressão puro, não IA — mas ainda
                // assim não vale computar pra toda vaga sempre à toa).
                .filter(j -> salarioMinimo == null || estimativaBate(j, salarioMinimo))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Map<String, Object>> resultado = filtradas.stream()
                .limit(limite)
                .map(j -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", j.getId());
                    m.put("title", j.getTitle());
                    m.put("company", j.getCompany());
                    m.put("status", statusDe(j));
                    m.put("url", j.getUrl());
                    m.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
                    // Anotação pessoal que o próprio usuário escreveu no card da vaga
                    // (campo "📝 Adicionar nota" no frontend) — é a fonte real de
                    // pendência/próximo passo de cada candidatura, não invente.
                    m.put("notes", j.getNotes());
                    return (Map<String, Object>) m;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEncontradas", filtradas.size());
        out.put("vagas", resultado);
        return out;
    }

    private boolean statusBate(Job j, String status) {
        if (status == null) return true;
        return switch (status) {
            case "NOVA" -> !j.isSeen() && !j.isRejected();
            case "VISTA" -> j.isSeen() && !j.isInterested() && !j.isApplied() && !j.isRejected();
            case "INTERESSADO" -> j.isInterested() && !j.isApplied() && !j.isRejected();
            case "APLICADA" -> j.isApplied() && !j.isInProgress() && !j.isRejected();
            case "ANDAMENTO" -> j.isApplied() && j.isInProgress() && !j.isRejected();
            case "RECUSADA" -> j.isRejected();
            default -> true;
        };
    }

    private String statusDe(Job j) {
        if (j.isRejected()) return "RECUSADA";
        if (j.isInProgress()) return "ANDAMENTO";
        if (j.isApplied()) return "APLICADA";
        if (j.isInterested()) return "INTERESSADO";
        if (j.isSeen()) return "VISTA";
        return "NOVA";
    }

    private boolean contemBusca(Job j, String busca) {
        String haystack = (j.getTitle() + " " + j.getCompany() + " " + (j.getTags() != null ? j.getTags() : "")).toLowerCase();
        return haystack.contains(busca.toLowerCase());
    }

    // Mesmo modelo de regressão puro Java das outras ferramentas de salário
    // (SalaryPredictionService) — vaga sem estimativa disponível nunca bate
    // um filtro de salário mínimo (não dá pra confirmar, não assume).
    private boolean estimativaBate(Job j, int salarioMinimo) {
        List<String> tags = j.getTags() == null || j.getTags().isBlank()
                ? List.of() : Arrays.asList(j.getTags().split(","));
        return salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState())
                .map(estimativa -> estimativa >= salarioMinimo)
                .orElse(false);
    }

    private Object executarResumoFunil() {
        // Cada vaga cai em EXATAMENTE um bucket (statusDe), o mesmo critério
        // que listarVagas usa e que as abas do funil no frontend usam pra
        // filtrar — por isso os números aqui batem com o que o usuário vê
        // clicando em cada aba. Antes essa contagem usava countByAppliedTrue()
        // (bruto, conta toda vaga que já foi aplicada alguma vez, incluindo as
        // que hoje estão "em andamento" ou foram recusadas depois) — o usuário
        // reportou exatamente essa confusão: Jarvis dizia "46 aplicadas" com a
        // aba "Aplicadas" mostrando só 1 (as outras 45 já migraram pra "em
        // andamento" ou "recusada"). Mantemos o bruto num campo à parte,
        // explicitamente rotulado, pra quem perguntar "quantas vezes já
        // apliquei no total".
        List<Job> todas = jobRepository.findAll();
        Map<String, Long> porBucket = new LinkedHashMap<>();
        for (String bucket : List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")) {
            porBucket.put(bucket, 0L);
        }
        for (Job j : todas) {
            porBucket.merge(statusDe(j), 1L, Long::sum);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) todas.size());
        m.put("novas", porBucket.get("NOVA"));
        m.put("vistas", porBucket.get("VISTA"));
        m.put("interessadas", porBucket.get("INTERESSADO"));
        m.put("aplicadas", porBucket.get("APLICADA"));
        m.put("emAndamento", porBucket.get("ANDAMENTO"));
        m.put("recusadas", porBucket.get("RECUSADA"));
        m.put("totalHistoricoAplicadas", todas.stream().filter(Job::isApplied).count());
        return m;
    }

    private Object executarCompatibilidade(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        int dias = args.get("dias") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        JarvisAssistantService.CompatibilityResult r = jarvisAssistantService.scanCompatibilidade(candidateProfile, dias, feedbackContext);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", r.available());
        m.put("totalConsiderados", r.totalConsiderados());
        m.put("totalAnalisadosPorIa", r.totalAnalisadosPorIa());
        // pontosFortes/pontosFaltando entravam só no resultado da versão "do
        // funil" — a interface não conseguia montar o mesmo card com "✅
        // pontos fortes" / "⚠️ pontos a desenvolver" + botão de plano de ação
        // pra esse caminho, mesmo o CompatibilityHit já carregando os dois
        // campos (só não eram repassados pro mapa que volta pro chat).
        m.put("hits", r.hits().stream().map(h -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", h.job().getId());
            hit.put("titulo", h.job().getTitle());
            hit.put("empresa", h.job().getCompany());
            hit.put("url", h.job().getUrl());
            hit.put("score", h.score());
            hit.put("resumo", h.resumo());
            hit.put("pontosFortes", h.pontosFortes());
            hit.put("pontosFaltando", h.pontosFaltando());
            return (Map<String, Object>) hit;
        }).toList());
        if (r.errorMessage() != null) m.put("erro", r.errorMessage());
        return m;
    }

    // Diferente de executarCompatibilidade (que faz um pré-filtro sem IA
    // antes de escolher as 5 mais promissoras num feed potencialmente
    // gigante), aqui o conjunto já vem filtrado por status — pequeno e
    // curado pelo próprio usuário por natureza — então analisa TODAS as que
    // passarem no filtro, direto, sem pré-seleção. O teto (MAX_COMPAT_FUNIL)
    // é só uma trava de segurança contra pedir isso num status que por
    // acaso tenha muitas vagas (ex: "NOVA" pode ter milhares).
    private Object executarCompatibilidadeFunil(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        if (candidateProfile == null || candidateProfile.isBlank()) {
            return Map.of("erro", "Salve seu perfil/currículo em ⚙️ Configurações primeiro, ou cole ele aqui na conversa.");
        }

        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        int limite = args.get("limite") instanceof Number n
                ? Math.min(MAX_COMPAT_FUNIL, Math.max(1, n.intValue()))
                : DEFAULT_COMPAT_FUNIL;

        List<Job> filtradas = jobRepository.findAll().stream()
                .filter(j -> statusBate(j, status))
                .filter(j -> busca == null || contemBusca(j, busca))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Job> analisar = filtradas.stream().limit(limite).toList();

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Job j : analisar) {
            MatchScoreService.MatchOutcome outcome = matchScoreService.calcular(j, candidateProfile, feedbackContext);
            if (!outcome.ok()) {
                log.warn("Jarvis: falha ao calcular compatibilidade da vaga {} pro funil: {}", j.getId(), outcome.errorMessage());
                continue;
            }
            MatchScoreService.MatchResult r = outcome.result();
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", j.getId());
            hit.put("titulo", j.getTitle());
            hit.put("empresa", j.getCompany());
            hit.put("url", j.getUrl());
            hit.put("score", r.score());
            hit.put("resumo", r.resumo());
            hit.put("pontosFortes", r.pontosFortes());
            hit.put("pontosFaltando", r.pontosFaltando());
            hits.add(hit);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("totalConsiderados", filtradas.size());
        m.put("totalAnalisadosPorIa", hits.size());
        m.put("hits", hits);
        if (filtradas.size() > analisar.size()) {
            m.put("erro", "Só analisei as " + analisar.size() + " mais recentes — tinha " + filtradas.size() + " vagas nesse status no total.");
        }
        return m;
    }

    // Não usa IA generativa — reaproveita o mesmo modelo de regressão puro
    // Java que já roda no botão 💰 de cada card (SalaryPredictionService).
    // Sem custo de cota, então o teto aqui é só sobre "quantidade razoável
    // pra caber num dashboard", não sobre economizar chamada de IA.
    private Object executarEstimativaSalarial(Map<String, Object> args) {
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        int limite = args.get("limite") instanceof Number n
                ? Math.min(MAX_SALARIO_VAGAS, Math.max(1, n.intValue()))
                : DEFAULT_SALARIO_VAGAS;

        List<Job> filtradas = jobRepository.findAll().stream()
                .filter(j -> statusBate(j, status))
                .filter(j -> busca == null || contemBusca(j, busca))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limite)
                .toList();

        List<Map<String, Object>> vagas = new ArrayList<>();
        for (Job j : filtradas) {
            List<String> tags = j.getTags() == null || j.getTags().isBlank()
                    ? List.of() : Arrays.asList(j.getTags().split(","));
            Optional<Long> estimativa = salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState());

            Map<String, Object> vaga = new LinkedHashMap<>();
            vaga.put("id", j.getId());
            vaga.put("titulo", j.getTitle());
            vaga.put("empresa", j.getCompany());
            vaga.put("url", j.getUrl());
            vaga.put("estimativa", estimativa.orElse(null));
            vaga.put("salarioInformado", j.getSalary());
            vagas.add(vaga);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modeloDisponivel", salaryPredictionService.isLoaded());
        m.put("totalEncontradas", filtradas.size());
        m.put("vagas", vagas);

        // Honestidade sobre a incerteza: erro médio real do modelo (ver
        // comentário na classe do serviço — ~43%, bem longe de exato) e
        // avisa se o modelo tá velho (nunca foi retreinado desde que os
        // dados do feed provavelmente mudaram de mercado/faixa salarial).
        SalaryPredictionService.ModelInfo info = salaryPredictionService.getModelInfo();
        if (info != null) {
            m.put("margemErroPercent", Math.round(info.maePercent()));
            m.put("modeloTreinadoEm", info.trainedAt());
            if (info.trainedAt() != null) {
                try {
                    long dias = java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.parse(info.trainedAt()), java.time.LocalDate.now());
                    m.put("modeloDesatualizado", dias > MODELO_SALARIO_DIAS_DESATUALIZADO);
                    m.put("modeloDiasDesdeTreino", dias);
                } catch (Exception ignored) {
                    // trainedAt em formato inesperado — não trava a resposta por isso
                }
            }
        }
        return m;
    }

    // Usa IA de verdade (1 chamada) — mesmo serviço do botão "Carta" de cada
    // card. candidateProfile/feedbackContext já chegam prontos (mesmo
    // caminho que compatibilidade/carta usam nos outros lugares do app).
    private Object executarGerarCarta(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String contextoExtra = args.get("contextoExtra") instanceof String s ? s : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga — chame listarVagas primeiro se só tiver título/empresa.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String extra = contextoExtra != null && candidateProfile != null
                ? candidateProfile + "\n\n" + contextoExtra
                : (contextoExtra != null ? contextoExtra : candidateProfile);
        GeminiService.GeminiResult resultado = coverLetterService.gerar(job, extra, feedbackContext);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagaId", vagaId);
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        if (!resultado.ok()) {
            m.put("erro", resultado.errorMessage());
        } else {
            m.put("carta", resultado.text());
        }
        return m;
    }

    // Não usa IA — mesma conta que /api/jobs/metrics já faz pro dashboard de
    // métricas, só reimplementada aqui porque esse cálculo vive dentro do
    // controller (não dá pra injetar controller num service). Fica curto o
    // bastante pra não valer a pena um refactor de extrair um serviço à
    // parte só por causa disso.
    private Object executarMetricasDeDesempenho() {
        List<Job> aplicadas = jobRepository.findByAppliedTrue();
        long total = aplicadas.size();
        long emAndamento = aplicadas.stream().filter(Job::isInProgress).count();
        long recusadas = aplicadas.stream().filter(Job::isRejected).count();
        long aguardandoRetorno = Math.max(0, total - emAndamento - recusadas);
        Double taxaResposta = total == 0 ? null : Math.round((emAndamento + recusadas) * 1000.0 / total) / 10.0;

        OptionalDouble avgAndamento = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getInProgressAt() != null)
                .mapToLong(j -> java.time.Duration.between(j.getAppliedAt(), j.getInProgressAt()).toDays())
                .average();
        OptionalDouble avgRecusa = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getRejectedAt() != null)
                .mapToLong(j -> java.time.Duration.between(j.getAppliedAt(), j.getRejectedAt()).toDays())
                .average();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAplicadas", total);
        m.put("emAndamento", emAndamento);
        m.put("recusadas", recusadas);
        m.put("aguardandoRetorno", aguardandoRetorno);
        m.put("taxaRespostaPercent", taxaResposta);
        m.put("tempoMedioAteAndamentoDias", avgAndamento.isPresent() ? Math.round(avgAndamento.getAsDouble() * 10) / 10.0 : null);
        m.put("tempoMedioAteRecusaDias", avgRecusa.isPresent() ? Math.round(avgRecusa.getAsDouble() * 10) / 10.0 : null);
        return m;
    }

    // Não usa IA — expiresAt é LocalDate (data, sem hora), já existe no
    // model e aparece nos cards ("Fecha em Xd"), só nunca tinha ferramenta
    // pro Hunter enxergar.
    private Object executarVagasComPrazoProximo(Map<String, Object> args) {
        int diasMaximo = args.get("diasMaximo") instanceof Number n ? Math.max(1, n.intValue()) : 7;
        java.time.LocalDate limite = java.time.LocalDate.now().plusDays(diasMaximo);

        List<Job> comPrazo = jobRepository.findAll().stream()
                .filter(j -> !j.isRejected())
                .filter(j -> j.getExpiresAt() != null && !j.getExpiresAt().isAfter(limite) && !j.getExpiresAt().isBefore(java.time.LocalDate.now()))
                .sorted(Comparator.comparing(Job::getExpiresAt))
                .toList();

        List<Map<String, Object>> vagas = comPrazo.stream().map(j -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("url", j.getUrl());
            v.put("status", statusDe(j));
            v.put("fechaEm", j.getExpiresAt().toString());
            v.put("diasRestantes", java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), j.getExpiresAt()));
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diasMaximo", diasMaximo);
        m.put("vagas", vagas);
        return m;
    }

    // Não usa IA generativa de propósito (diferente do endpoint /duplicates
    // da UI, que faz uma segunda checagem via Gemini) — versão enxuta só de
    // similaridade de texto pra não gastar cota numa ferramenta de chat que
    // o modelo pode decidir chamar sem querer. Falso positivo ocasional é
    // aceitável aqui — o resultado já avisa que é só indício.
    private Object executarDetectarDuplicatas() {
        List<Job> ativas = jobRepository.findAll().stream().filter(j -> !j.isRejected()).toList();
        Map<String, List<Job>> porEmpresa = new LinkedHashMap<>();
        for (Job j : ativas) {
            String chave = j.getCompany() == null ? "" : j.getCompany().trim().toLowerCase();
            if (chave.isBlank()) continue;
            porEmpresa.computeIfAbsent(chave, k -> new ArrayList<>()).add(j);
        }

        List<Map<String, Object>> grupos = new ArrayList<>();
        for (List<Job> candidatas : porEmpresa.values()) {
            if (candidatas.size() < 2) continue;
            for (int i = 0; i < candidatas.size(); i++) {
                for (int k = i + 1; k < candidatas.size(); k++) {
                    Job a = candidatas.get(i);
                    Job b = candidatas.get(k);
                    if (!Objects.equals(a.getSeniority(), b.getSeniority())) continue;
                    double sim = jaccardSimples(a.getTitle(), b.getTitle());
                    if (sim < 0.6) continue;
                    Map<String, Object> grupo = new LinkedHashMap<>();
                    grupo.put("empresa", a.getCompany());
                    grupo.put("vagas", List.of(
                            Map.of("id", a.getId(), "titulo", a.getTitle(), "fonte", a.getSource(), "url", a.getUrl()),
                            Map.of("id", b.getId(), "titulo", b.getTitle(), "fonte", b.getSource(), "url", b.getUrl())
                    ));
                    grupos.add(grupo);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("grupos", grupos);
        return m;
    }

    private double jaccardSimples(String tituloA, String tituloB) {
        Set<String> a = new HashSet<>(Arrays.asList((tituloA == null ? "" : tituloA.toLowerCase()).split("\\s+")));
        Set<String> b = new HashSet<>(Arrays.asList((tituloB == null ? "" : tituloB.toLowerCase()).split("\\s+")));
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uniao = new HashSet<>(a);
        uniao.addAll(b);
        return (double) inter.size() / uniao.size();
    }

    // Não usa IA — cruza fonte × status pra ver de onde vêm as vagas que
    // realmente avançam (aplicadas e, dentro dessas, quantas viraram
    // andamento) — não é só "quantas vagas cada fonte tem".
    private Object executarDesempenhoPorFonte() {
        List<Job> todas = jobRepository.findAll();
        Map<String, long[]> porFonte = new LinkedHashMap<>(); // [total, aplicadas, emAndamento]
        for (Job j : todas) {
            String fonte = j.getSource() == null ? "DESCONHECIDA" : j.getSource();
            long[] cont = porFonte.computeIfAbsent(fonte, k -> new long[3]);
            cont[0]++;
            if (j.isApplied()) {
                cont[1]++;
                if (j.isInProgress()) cont[2]++;
            }
        }
        List<Map<String, Object>> fontes = porFonte.entrySet().stream()
                .map(e -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fonte", e.getKey());
                    f.put("totalVagas", e.getValue()[0]);
                    f.put("aplicadas", e.getValue()[1]);
                    f.put("emAndamento", e.getValue()[2]);
                    return (Map<String, Object>) f;
                })
                .sorted((a, b) -> Long.compare((long) b.get("emAndamento"), (long) a.get("emAndamento")))
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fontes", fontes);
        return m;
    }

    // Não usa IA — busca livre por nome de empresa (contains, case-insensitive),
    // qualquer status inclusive recusada, pra dar contexto de histórico.
    private Object executarHistoricoDaEmpresa(Map<String, Object> args) {
        String empresa = args.get("empresa") instanceof String s && !s.isBlank() ? s.toLowerCase() : null;
        if (empresa == null) {
            return Map.of("erro", "Preciso do nome da empresa.");
        }
        List<Job> encontradas = jobRepository.findAll().stream()
                .filter(j -> j.getCompany() != null && j.getCompany().toLowerCase().contains(empresa))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> vagas = encontradas.stream().map(j -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("status", statusDe(j));
            v.put("url", j.getUrl());
            v.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
            return (Map<String, Object>) v;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEncontradas", vagas.size());
        m.put("vagas", vagas);
        return m;
    }

    // Não decide sozinho a "prioridade" com uma fórmula — devolve os 3 sinais
    // crus (parada há mais tempo, prazo mais próximo, maior match heurístico
    // entre as não vistas) e deixa o próprio modelo sintetizar em texto qual
    // vale mais a pena agora, seguindo a orientação da SYSTEM_INSTRUCTION.
    // Reaproveita executarVagasParadas/executarVagasComPrazoProximo (mesmos
    // dados que essas ferramentas já expõem individualmente) em vez de
    // duplicar a lógica de filtro.
    @SuppressWarnings("unchecked")
    private Object executarOQueFazerAgora(String candidateProfile) {
        Map<String, Object> paradas = (Map<String, Object>) executarVagasParadas(Map.of());
        Map<String, Object> prazos = (Map<String, Object>) executarVagasComPrazoProximo(Map.of());

        List<Map<String, Object>> paradasTop = ((List<Map<String, Object>>) paradas.get("vagas")).stream()
                .limit(3).toList();
        List<Map<String, Object>> prazosTop = ((List<Map<String, Object>>) prazos.get("vagas")).stream()
                .limit(3).toList();

        List<Job> naoVistas = jobRepository.findBySeenFalse();
        Map<Long, Integer> scores = jarvisAssistantService.heuristicMatchPercents(naoVistas, candidateProfile);
        List<Map<String, Object>> matchesTop = naoVistas.stream()
                .filter(j -> scores.getOrDefault(j.getId(), 0) >= 50)
                .sorted(Comparator.comparingInt((Job j) -> scores.getOrDefault(j.getId(), 0)).reversed())
                .limit(3)
                .map(j -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", j.getId());
                    v.put("titulo", j.getTitle());
                    v.put("empresa", j.getCompany());
                    v.put("url", j.getUrl());
                    v.put("matchPercent", scores.get(j.getId()));
                    return (Map<String, Object>) v;
                })
                .toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidaturasParadas", Map.of("total", ((List<?>) paradas.get("vagas")).size(), "top", paradasTop));
        m.put("prazosProximos", Map.of("total", ((List<?>) prazos.get("vagas")).size(), "top", prazosTop));
        m.put("vagasNovasComBomMatch", Map.of(
                "perfilDisponivel", candidateProfile != null && !candidateProfile.isBlank(),
                "top", matchesTop
        ));
        return m;
    }

    // Não usa IA — sobreposição de tags (SalaryPredictionService.
    // extractTagsFromText, o mesmo vocabulário do modelo de salário) entre o
    // perfil salvo e o feed inteiro de vagas ativas. É um indício simples de
    // demanda de mercado, não uma pesquisa de verdade — a descrição da
    // ferramenta já avisa o modelo disso.
    private Object executarCompararComMercado(String candidateProfile) {
        if (candidateProfile == null || candidateProfile.isBlank()) {
            return Map.of("erro", "Preciso do perfil/currículo salvo em Configurações pra comparar com o mercado.");
        }
        Set<String> perfilTags = salaryPredictionService.extractTagsFromText(candidateProfile);
        if (perfilTags.isEmpty()) {
            return Map.of("erro", "Não reconheci nenhuma tecnologia conhecida no seu perfil salvo.");
        }

        Map<String, Long> contagem = new HashMap<>();
        for (Job j : jobRepository.findAll()) {
            if (j.isRejected() || j.getTags() == null || j.getTags().isBlank()) continue;
            for (String tagBruta : j.getTags().split(",")) {
                String tag = tagBruta.trim().toLowerCase();
                if (!tag.isBlank()) contagem.merge(tag, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> tagsNoPerfilEPedidas = perfilTags.stream()
                .filter(contagem::containsKey)
                .sorted(Comparator.comparingLong((String t) -> contagem.getOrDefault(t, 0L)).reversed())
                .limit(10)
                .map(t -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("tag", t);
                    v.put("vagasComEssaTag", contagem.get(t));
                    return v;
                })
                .toList();

        List<Map<String, Object>> tagsMaisPedidasFaltando = contagem.entrySet().stream()
                .filter(e -> !perfilTags.contains(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("tag", e.getKey());
                    v.put("vagasComEssaTag", e.getValue());
                    return v;
                })
                .toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tagsDoPerfilReconhecidas", perfilTags.size());
        m.put("tagsDoPerfilQueBatemComOMercado", tagsNoPerfilEPedidas);
        m.put("tagsMaisPedidasQueFaltamNoPerfil", tagsMaisPedidasFaltando);
        return m;
    }

    // Não usa IA — só devolve tudo que já está salvo sobre cada vaga (mais a
    // estimativa salarial gratuita, mesmo modelo do botão 💰). Cobre tanto
    // "detalha essa vaga" (1 id) quanto "compara essas vagas" (2+ ids) — o
    // frontend decide como desenhar com base em quantos itens vieram.
    @SuppressWarnings("unchecked")
    private Object executarDetalharVagas(Map<String, Object> args) {
        List<Number> idsRaw = args.get("vagaIds") instanceof List<?> l
                ? (List<Number>) l.stream().filter(Number.class::isInstance).toList()
                : List.of();
        if (idsRaw.isEmpty()) {
            return Map.of("erro", "Preciso do id de pelo menos uma vaga — chame listarVagas primeiro se só tiver título/empresa.");
        }
        List<Long> ids = idsRaw.stream().map(Number::longValue).distinct().limit(10).toList();

        List<Map<String, Object>> vagas = new ArrayList<>();
        List<Long> naoEncontradas = new ArrayList<>();
        for (Long id : ids) {
            Optional<Job> jobOpt = jobRepository.findById(id);
            if (jobOpt.isEmpty()) {
                naoEncontradas.add(id);
                continue;
            }
            Job j = jobOpt.get();
            List<String> tags = j.getTags() == null || j.getTags().isBlank()
                    ? List.of() : Arrays.asList(j.getTags().split(","));
            Optional<Long> estimativa = salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState());

            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("url", j.getUrl());
            v.put("status", statusDe(j));
            v.put("fonte", j.getSource());
            v.put("senioridade", j.getSeniority());
            v.put("modalidade", j.getWorkplaceType());
            v.put("estado", j.getState());
            v.put("cidade", j.getCity());
            v.put("tags", tags);
            v.put("salarioInformado", j.getSalary());
            v.put("salarioEstimado", estimativa.orElse(null));
            v.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
            v.put("expiraEm", j.getExpiresAt() != null ? j.getExpiresAt().toString() : null);
            v.put("notas", j.getNotes());
            vagas.add(v);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagas", vagas);
        m.put("modo", vagas.size() >= 2 ? "comparacao" : "detalhe");
        if (!naoEncontradas.isEmpty()) {
            m.put("erro", "Não achei a(s) vaga(s) de id " + naoEncontradas + " — pode ter sido apagada.");
        }
        return m;
    }

    // Não usa IA — pontua outras vagas do feed geral por quantas tags a vaga
    // de referência tem em comum (Jaccard simples), com bônus se senioridade
    // e modalidade também baterem. Não filtra por status do funil de
    // propósito: a ideia é achar OUTRAS vagas ainda não vistas/curadas,
    // parecidas com uma que o usuário já gostou.
    private Object executarVagasParecidas(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga de referência — chame listarVagas primeiro se só tiver título/empresa.");
        }
        Optional<Job> refOpt = jobRepository.findById(vagaId);
        if (refOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job ref = refOpt.get();
        Set<String> tagsRef = tagsDe(ref);
        int limite = args.get("limite") instanceof Number n
                ? Math.min(15, Math.max(1, n.intValue()))
                : 8;

        record Candidata(Job job, int score, Set<String> tagsComuns) {}

        List<Candidata> candidatas = jobRepository.findAll().stream()
                .filter(j -> !j.getId().equals(ref.getId()))
                .map(j -> {
                    Set<String> tagsJ = tagsDe(j);
                    Set<String> comuns = new LinkedHashSet<>(tagsRef);
                    comuns.retainAll(tagsJ);
                    int score = comuns.size() * 2;
                    if (Objects.equals(j.getSeniority(), ref.getSeniority())) score += 1;
                    if (Objects.equals(j.getWorkplaceType(), ref.getWorkplaceType())) score += 1;
                    return new Candidata(j, score, comuns);
                })
                .filter(c -> c.score() > 0)
                .sorted(Comparator.comparingInt(Candidata::score).reversed()
                        .thenComparing(c -> c.job().getPostedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limite)
                .toList();

        List<Map<String, Object>> vagas = candidatas.stream().map(c -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", c.job().getId());
            v.put("titulo", c.job().getTitle());
            v.put("empresa", c.job().getCompany());
            v.put("status", statusDe(c.job()));
            v.put("url", c.job().getUrl());
            v.put("tagsEmComum", c.tagsComuns());
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagaReferencia", Map.of("id", ref.getId(), "titulo", ref.getTitle(), "empresa", ref.getCompany()));
        m.put("vagas", vagas);
        if (vagas.isEmpty()) {
            m.put("erro", "Não achei nenhuma vaga parecida — essa vaga tem poucas tags salvas pra comparar.");
        }
        return m;
    }

    private Set<String> tagsDe(Job j) {
        if (j.getTags() == null || j.getTags().isBlank()) return Set.of();
        return Arrays.stream(j.getTags().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // Não usa IA — só compara appliedAt/inProgressAt com hoje. Só olha vaga
    // ATIVA (aplicada ou em andamento, nunca recusada): "parada" só faz
    // sentido pra candidatura que ainda tá em jogo, não pra uma que já
    // terminou de um jeito ou de outro. INTERESSADO fica de fora de
    // propósito — não tem timestamp próprio (Job não guarda "interestedAt"),
    // e "interessado há muito tempo" não é bem o mesmo problema de "apliquei
    // e não veio resposta".
    private Object executarVagasParadas(Map<String, Object> args) {
        int diasMinimo = args.get("diasMinimo") instanceof Number n ? Math.max(1, n.intValue()) : 10;
        LocalDateTime limite = LocalDateTime.now().minusDays(diasMinimo);

        record Parada(Job job, LocalDateTime referencia, String status) {}

        List<Parada> paradas = new ArrayList<>();
        for (Job j : jobRepository.findAll()) {
            if (j.isRejected()) continue;
            if (j.isApplied() && !j.isInProgress() && j.getAppliedAt() != null && j.getAppliedAt().isBefore(limite)) {
                paradas.add(new Parada(j, j.getAppliedAt(), "APLICADA"));
            } else if (j.isInProgress() && j.getInProgressAt() != null && j.getInProgressAt().isBefore(limite)) {
                paradas.add(new Parada(j, j.getInProgressAt(), "ANDAMENTO"));
            }
        }
        paradas.sort(Comparator.comparing(Parada::referencia));

        List<Map<String, Object>> vagas = paradas.stream().map(p -> {
            long dias = java.time.temporal.ChronoUnit.DAYS.between(p.referencia(), LocalDateTime.now());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", p.job().getId());
            v.put("titulo", p.job().getTitle());
            v.put("empresa", p.job().getCompany());
            v.put("url", p.job().getUrl());
            v.put("status", p.status());
            v.put("diasParada", dias);
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diasMinimo", diasMinimo);
        m.put("vagas", vagas);
        return m;
    }

    private Object executarFixarVaga(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        Boolean fixar = args.get("fixar") instanceof Boolean b ? b : null;
        if (vagaId == null || fixar == null) {
            return Map.of("erro", "Preciso do id da vaga e se é pra fixar (true) ou desafixar (false).");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        job.setFavorited(fixar);
        jobRepository.save(job);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        m.put("fixada", fixar);
        return m;
    }

    // Mesma lógica de POST /api/jobs/manual — cria vaga nova ou atualiza se
    // já existir uma com a mesma URL. Status padrão APLICADA (fluxo normal:
    // "achei essa vaga fora do Job Radar e já apliquei"), igual o botão
    // "➕ Adicionar vaga" do frontend.
    private Object executarAdicionarVagaManual(Map<String, Object> args) {
        String titulo = args.get("titulo") instanceof String s && !s.isBlank() ? s : null;
        String empresa = args.get("empresa") instanceof String s && !s.isBlank() ? s : null;
        String url = args.get("url") instanceof String s && !s.isBlank() ? s : null;
        if (titulo == null || empresa == null || url == null) {
            return Map.of("erro", "Preciso de título, empresa e link da vaga.");
        }
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : "APLICADA";
        if (!JobStatusService.VALID_STATUSES.contains(status)) {
            return Map.of("erro", "Status inválido (" + JobStatusService.VALID_STATUSES + ").");
        }
        String salario = args.get("salario") instanceof String s ? s : null;
        String modalidade = args.get("modalidade") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String estado = args.get("estado") instanceof String s ? s : null;

        Job job = jobRepository.findByUrl(url).orElseGet(Job::new);
        job.setTitle(titulo);
        job.setCompany(empresa);
        job.setUrl(url);
        job.setSource(job.getSource() == null ? "MANUAL" : job.getSource());
        job.setSeniority(seniorityClassifier.classify(titulo, null));
        job.setSalary(salario);
        job.setWorkplaceType(modalidade);
        job.setState(estado);
        job.setPostedAt(job.getPostedAt() != null ? job.getPostedAt() : LocalDateTime.now());
        job.setFetchedAt(LocalDateTime.now());
        jobStatusService.aplicarEsalvar(job, status);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", job.getId());
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        m.put("status", status);
        return m;
    }

    // Não persiste nada aqui de verdade — quem guarda é o frontend
    // (localStorage, ver useHunterMemory), o backend só devolve o texto pro
    // JarvisPanel saber o que salvar. Ver comentário no parâmetro
    // memoryContext de conversar() sobre por que é assim.
    private Object executarLembrarPreferencia(Map<String, Object> args) {
        String texto = args.get("texto") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (texto == null) {
            return Map.of("erro", "Preciso do texto da preferência a lembrar.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("texto", texto);
        return m;
    }

    // Irreversível — a SYSTEM_INSTRUCTION obriga o modelo a confirmar com
    // perguntarUsuario antes de chamar essa (não dá pra impor isso aqui no
    // backend sem estado de sessão entre chamadas, ver comentário no record
    // PendingQuestion sobre por que o histórico já basta pra tudo mais).
    private Object executarApagarVaga(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode já ter sido apagada.");
        }
        Job job = jobOpt.get();
        String titulo = job.getTitle();
        String empresa = job.getCompany();
        jobRepository.delete(job);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", titulo);
        m.put("empresa", empresa);
        return m;
    }

    // ÚNICA ferramenta de MUDANÇA DE STATUS — as outras que escrevem
    // (fixarVaga, atualizarNotaDeVaga, adicionarVagaManual) mexem em campos
    // diferentes. Ver guidance na SYSTEM_INSTRUCTION e na descrição da
    // ferramenta sobre só chamar com a vaga claramente identificada, nunca
    // "no escuro".
    private Object executarMarcarStatus(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        if (vagaId == null || status == null || !JobStatusService.VALID_STATUSES.contains(status)) {
            return Map.of("erro", "Preciso do id da vaga e um status válido (" + JobStatusService.VALID_STATUSES + ").");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String statusAntes = statusDe(job);
        String tituloAntes = job.getTitle();
        String empresaAntes = job.getCompany();
        jobStatusService.aplicarEsalvar(job, status);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", tituloAntes);
        m.put("empresa", empresaAntes);
        m.put("statusAntes", statusAntes);
        m.put("statusNovo", status);
        return m;
    }

    // Segunda ferramenta que escreve (ver marcarStatusDeVaga) — mesmo campo
    // que o botão "📝 Adicionar nota" de cada card mexe. SUBSTITUI o texto
    // inteiro (é só um campo de texto simples no banco, não uma lista de
    // itens) — a SYSTEM_INSTRUCTION já orienta o modelo a compor o texto
    // final antes de chamar quando o pedido for "adicionar" a uma nota
    // que já existe.
    private Object executarAtualizarNota(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String nota = args.get("nota") instanceof String s ? s : null;
        if (vagaId == null || nota == null) {
            return Map.of("erro", "Preciso do id da vaga e o texto da nota.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String notaAntes = job.getNotes();
        String tituloAntes = job.getTitle();
        String empresaAntes = job.getCompany();
        job.setNotes(nota.isBlank() ? null : nota);
        jobRepository.save(job);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", tituloAntes);
        m.put("empresa", empresaAntes);
        m.put("notaAntes", notaAntes);
        m.put("notaNova", job.getNotes());
        return m;
    }
}

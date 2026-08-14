package br.com.jobradar.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

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
 *
 * <p>Fase 14.3 — esse arquivo já foi de ~1980 linhas (ver Fase 3.6, quando
 * a declaração de ferramentas saiu pra {@link JarvisToolDeclarations}) e
 * voltou a crescer até ~1475 com as ferramentas de leitura da Fase 14.1.
 * Agora só sobra aqui o LOOP de conversa (function-calling com o Gemini,
 * fast-path sem IA) e o DISPATCH — a implementação de cada ferramenta mora
 * em {@link JarvisReadTools} (leitura) ou {@link JarvisWriteTools}
 * (escrita, desde a Fase 3.6). Zero mudança de comportamento, métodos
 * movidos verbatim.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JarvisChatService {

    private final GeminiService geminiService;
    private final JarvisReadTools readTools;
    private final JarvisWriteTools writeTools;

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

            Quando uma pergunta de acompanhamento é ambígua sobre QUAL FONTE de
            dado usar (ex: acabou de rodar verificarEmailsDeVagasLinkedIn sem
            achar nada, e o usuário pede "pega a mais recente" — isso quer dizer
            "a mais recente do email" ou "a mais recente do Job Radar em geral"?),
            NUNCA troque de fonte silenciosamente. Ou peça a confirmação antes
            (ex: "não achei nada no email — quer que eu pegue a vaga mais recente
            do seu feed normal do Job Radar?"), ou, se decidir seguir mesmo assim,
            deixe EXPLÍCITO na resposta qual fonte usou de verdade (ex: "não achei
            vaga nova no email, mas aqui está a mais recente do seu feed:"). O
            usuário não tem como saber de qual lugar um dado veio só olhando o
            resultado — isso já causou confusão real (achou que a vaga tinha sido
            inventada, quando na verdade veio de outra ferramenta sem aviso).

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

            criarLembreteNaAgenda: o backend do Job Radar NUNCA fala direto com
            a Agenda Pessoal (app separado, roda em outra porta) — essa
            ferramenta só MONTA a proposta de lembrete (título, descrição,
            data/hora, vaga relacionada se houver), a interface mostra um card
            com botão "Criar lembrete" que o USUÁRIO clica pra criar de
            verdade (ou conectar a Agenda primeiro, se ainda não conectou).
            Preencha dueAt em ISO 8601 com fuso -03:00 quando o usuário der uma
            data relativa ("amanhã", "sexta que vem") — calcule a partir de
            hoje. Pode chamar direto ao pedido claro tipo "me lembra de dar
            follow-up nessa vaga sexta", não precisa confirmar antes (a
            confirmação de verdade é o clique no botão do card).

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

    // Usado só pelo endpoint de streaming (POST /assistant/chat/stream) —
    // notifica CADA passo real do loop de function-calling assim que
    // acontece, pra virar evento SSE narrando o progresso de verdade em vez
    // da aproximação por palavra-chave que o frontend usava antes (ver
    // TOOL_PHRASES no JarvisPanel.tsx). O caminho síncrono antigo (POST
    // /assistant/chat) não passa listener nenhum — os overloads abaixo usam
    // um no-op, então o comportamento dele fica idêntico a antes.
    public interface ChatProgressListener {
        void onToolCall(String toolName);

        // Checado no INÍCIO de cada rodada do loop de function-calling, antes
        // de chamar o Gemini de novo — dá pra parar ENTRE ferramentas, não no
        // meio de uma chamada HTTP já em voo (essa parte não tem como
        // cancelar sem reescrever o cliente HTTP pra algo com suporte a
        // cancelamento de verdade, fora de escopo aqui). Ainda assim é uma
        // parada real: numa pergunta que dispara 3-4 ferramentas em
        // sequência, cancelar depois da 1ª evita gastar cota nas 2-3
        // seguintes. Default false preserva o comportamento de sempre pra
        // quem não passa listener nenhum (o endpoint síncrono antigo).
        default boolean isCancelled() {
            return false;
        }

        // Pedaço de TEXTO da resposta final assim que chega do Gemini (ver
        // GeminiService.chatStream) — só dispara na rodada que termina em
        // texto (não em rodada de function-calling, que não tem texto
        // incremental de verdade pra narrar). Default no-op preserva o
        // comportamento de sempre pra quem não passa listener nenhum.
        default void onAnswerChunk(String chunk) {
        }
    }

    private static final ChatProgressListener NOOP_LISTENER = toolName -> { };

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile) {
        return conversar(historico, candidateProfile, null, null, NOOP_LISTENER);
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext) {
        return conversar(historico, candidateProfile, feedbackContext, null, NOOP_LISTENER);
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext, String memoryContext) {
        return conversar(historico, candidateProfile, feedbackContext, memoryContext, NOOP_LISTENER);
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext, String memoryContext,
                                  ChatProgressListener listener) {
        return conversar(historico, candidateProfile, feedbackContext, memoryContext, listener, false);
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext, String memoryContext,
                                  ChatProgressListener listener, boolean fastMode) {
        return conversar(historico, candidateProfile, feedbackContext, memoryContext, listener, fastMode, null);
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
    //
    // fastMode: toggle explícito do usuário (⚡ no cabeçalho do chat) pra
    // controlar gasto de cota — desliga includeThoughts (raciocínio custa
    // tokens de saída extras) e pede pro modelo evitar ferramentas caras
    // (compatibilidade, carta) a menos que seja exatamente o pedido.
    //
    // replyStyle: preset de TOM da resposta escolhido pelo usuário (chips
    // Normal/Conciso/Formal no rodapé do chat) — "conciso"/"formal", ou
    // null/qualquer outro valor pro comportamento padrão de sempre. Não
    // mexe em NENHUMA ferramenta chamada, só na instrução de como escrever.
    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile, String feedbackContext, String memoryContext,
                                  ChatProgressListener listener, boolean fastMode, String replyStyle) {
        if (listener == null) listener = NOOP_LISTENER;
        if (historico == null || historico.isEmpty()) {
            return new ChatOutcome(null, null, List.of(), "Mensagem vazia.", false);
        }

        // Fast-path sem IA: um punhado de perguntas triviais e determinísticas
        // (ex: "quantas vagas eu tenho") não precisam de function-calling
        // nenhum — respondidas na hora, sem gastar uma chamada de cota (que
        // hoje é o recurso mais escasso do Hunter, ver KeyPoolStatus). Só
        // dispara em correspondência EXATA da mensagem inteira (normalizada),
        // não substring — uma pergunta com contexto extra ("quantas vagas eu
        // tenho no Itaú") cai pro caminho normal do LLM, como deveria.
        ChatMessage ultimaMensagem = historico.get(historico.size() - 1);
        if ("user".equals(ultimaMensagem.role()) && !ultimaMensagem.temImagem()) {
            ChatOutcome fastPath = tentarFastPath(ultimaMensagem.text(), listener);
            if (fastPath != null) return fastPath;
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
        //
        // Sobre cache implícito do Gemini (que depende de PREFIXO idêntico
        // entre chamadas): checado de propósito — SYSTEM_INSTRUCTION (a parte
        // grande e estável, ~12k caracteres) já vem SEMPRE primeiro, com
        // feedback/memória (o que varia por usuário/sessão) concatenado
        // DEPOIS. Ou seja, o prefixo cacheável já fica intacto do jeito que
        // está — não tinha nada de fato quebrado aqui pra corrigir.
        String systemInstructionComFeedback = feedbackContext != null && !feedbackContext.isBlank()
                ? SYSTEM_INSTRUCTION + "\n\nFeedback que o usuário já deu sobre suas respostas anteriores " +
                        "(curtiu/não curtiu + comentário) — leve em conta pra ajustar tom, formato ou nível " +
                        "de detalhe das próximas respostas:\n" + feedbackContext
                : SYSTEM_INSTRUCTION;
        // Preferências que o usuário pediu EXPLICITAMENTE pra lembrar (não é
        // feedback de estilo, são fatos/regras reais pra aplicar sempre que
        // relevante — ex: filtro implícito de local/modalidade em listarVagas).
        String systemInstructionComMemoria = memoryContext != null && !memoryContext.isBlank()
                ? systemInstructionComFeedback + "\n\nPreferências que o usuário já pediu pra você lembrar entre " +
                        "conversas (aplique sempre que fizer sentido pro pedido atual, sem precisar que ele repita):\n" +
                        memoryContext
                : systemInstructionComFeedback;
        // Modo rápido (⚡, toggle explícito do usuário no cabeçalho do chat) —
        // controle direto de gasto de cota: desliga o raciocínio (includeThoughts
        // custa tokens de saída extras em toda resposta) e pede economia nas
        // ferramentas que gastam IA de verdade.
        String systemInstructionComFastMode = fastMode
                ? systemInstructionComMemoria + "\n\nModo rápido ativado pelo usuário: seja econômico. Evite chamar " +
                        "compatibilidadeComVagasRecentes, compatibilidadeComVagasDoFunil ou gerarCartaDeApresentacao " +
                        "a menos que seja exatamente o que foi pedido, prefira respostas mais curtas e diretas."
                : systemInstructionComMemoria;
        // Preset de tom — chips Normal/Conciso/Formal no rodapé do chat (ver
        // comentário do parâmetro replyStyle acima). "normal"/null não muda
        // nada (comportamento padrão de sempre).
        String systemInstructionFinal;
        if ("conciso".equals(replyStyle)) {
            systemInstructionFinal = systemInstructionComFastMode + "\n\nEstilo de resposta pedido pelo usuário: CONCISO. " +
                    "Vá direto ao ponto, frases curtas, sem rodeios nem contexto extra que não foi pedido.";
        } else if ("formal".equals(replyStyle)) {
            systemInstructionFinal = systemInstructionComFastMode + "\n\nEstilo de resposta pedido pelo usuário: FORMAL. " +
                    "Tom mais profissional/institucional, evite gírias e informalidade, mas continue claro e objetivo.";
        } else {
            systemInstructionFinal = systemInstructionComFastMode;
        }

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            if (listener.isCancelled()) {
                return new ChatOutcome(null, null, toolResults, "Interrompido pelo usuário.", false);
            }
            ChatProgressListener listenerFinal = listener;
            GeminiService.ChatResult resultado = geminiService.chatStream(
                    systemInstructionFinal, contents, tools, !fastMode, listenerFinal::onAnswerChunk);
            if (!resultado.ok()) {
                return new ChatOutcome(null, null, toolResults, resultado.errorMessage(), resultado.rateLimited());
            }
            if (!resultado.isFunctionCall()) {
                return new ChatOutcome(resultado.text(), resultado.thinking(), toolResults, null, false);
            }

            GeminiService.FunctionCallRequest chamada = resultado.functionCall();
            log.info("Jarvis: chamando ferramenta '{}' com args {}", chamada.name(), chamada.args());
            listener.onToolCall(chamada.name());

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

    // ===================== Fast-path sem IA =====================

    // Correspondência exata (não substring) contra a mensagem inteira,
    // normalizada (sem acento, minúscula, sem pontuação final) — cobre só as
    // formas mais comuns de pedir o resumo do funil. Qualquer variação fora
    // dessa lista cai pro LLM normalmente, sem risco de "roubar" uma pergunta
    // mais nuançada que só parece com essas.
    private static final Set<String> FAST_PATH_RESUMO_FUNIL = Set.of(
            "quantas vagas eu tenho", "quantas vagas tenho", "quantas vagas eu apliquei",
            "quantas vagas apliquei", "quantas vagas tem", "quantas vagas eu tenho no total",
            "resumo do funil", "resumo do meu funil", "resumo rapido do funil",
            "qual o resumo do funil", "me da um resumo do funil"
    );

    // Package-private de propósito — testável direto em JarvisChatServiceTest.
    ChatOutcome tentarFastPath(String textoUsuario, ChatProgressListener listener) {
        if (textoUsuario == null || textoUsuario.isBlank()) return null;
        String normalizado = normalizarFastPath(textoUsuario);

        if (FAST_PATH_RESUMO_FUNIL.contains(normalizado)) {
            listener.onToolCall("resumoFunil");
            @SuppressWarnings("unchecked")
            Map<String, Object> resumo = (Map<String, Object>) readTools.executarResumoFunil();
            String reply = String.format(
                    "Você tem %d vagas no total: %d novas, %d vistas, %d com interesse marcado, " +
                            "%d aplicadas, %d em andamento e %d recusadas.",
                    (Long) resumo.get("total"), (Long) resumo.get("novas"), (Long) resumo.get("vistas"),
                    (Long) resumo.get("interessadas"), (Long) resumo.get("aplicadas"),
                    (Long) resumo.get("emAndamento"), (Long) resumo.get("recusadas"));
            List<ToolResultPayload> resultados = List.of(new ToolResultPayload("resumoFunil", resumo));
            return new ChatOutcome(reply, null, resultados, null, false);
        }

        return null;
    }

    private String normalizarFastPath(String texto) {
        String semAcento = java.text.Normalizer.normalize(texto.toLowerCase().trim(), java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
        return semAcento.replaceAll("[?!.]+$", "").trim();
    }

    // ===================== Definição das ferramentas =====================

    // Fase 3.6 — declarações das ferramentas extraídas pra
    // JarvisToolDeclarations (arquivo próprio, ~410 linhas de schema/texto
    // sem lógica nenhuma) — esse arquivo ficou grande demais só com o
    // dispatcher + implementações. Zero mudança de comportamento.
    private List<GeminiService.FunctionDeclaration> buildTools() {
        return JarvisToolDeclarations.build();
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

    // Package-private de propósito — testa o DISPATCH em JarvisChatServiceTest
    // sem precisar montar uma conversa inteira com Gemini mockado.
    Object executarFerramenta(GeminiService.FunctionCallRequest chamada, String candidateProfile, String feedbackContext) {
        return switch (chamada.name()) {
            case "listarVagas" -> readTools.executarListarVagas(chamada.args());
            case "resumoFunil" -> readTools.executarResumoFunil();
            case "compatibilidadeComVagasRecentes" -> readTools.executarCompatibilidade(chamada.args(), candidateProfile, feedbackContext);
            case "compatibilidadeComVagasDoFunil" -> readTools.executarCompatibilidadeFunil(chamada.args(), candidateProfile, feedbackContext);
            case "estimativaSalarialDeVagas" -> readTools.executarEstimativaSalarial(chamada.args());
            case "gerarCartaDeApresentacao" -> readTools.executarGerarCarta(chamada.args(), candidateProfile, feedbackContext);
            case "metricasDeDesempenho" -> readTools.executarMetricasDeDesempenho();
            case "vagasComPrazoProximo" -> readTools.executarVagasComPrazoProximo(chamada.args());
            case "detectarDuplicatas" -> readTools.executarDetectarDuplicatas();
            case "desempenhoPorFonte" -> readTools.executarDesempenhoPorFonte();
            case "historicoDaEmpresa" -> readTools.executarHistoricoDaEmpresa(chamada.args());
            case "oQueFazerAgora" -> readTools.executarOQueFazerAgora(candidateProfile);
            case "compararStackComMercado" -> readTools.executarCompararComMercado(candidateProfile);
            case "detalharVagas" -> readTools.executarDetalharVagas(chamada.args());
            case "vagasParecidas" -> readTools.executarVagasParecidas(chamada.args());
            case "vagasParadas" -> readTools.executarVagasParadas(chamada.args());
            case "fixarVaga" -> writeTools.executarFixarVaga(chamada.args());
            case "adicionarVagaManual" -> writeTools.executarAdicionarVagaManual(chamada.args());
            case "buscarVagasPorSignificado" -> readTools.executarBuscaSemantica(chamada.args());
            case "verificarEmailsDeVagas" -> readTools.executarVerificarEmailsDeVagas(chamada.args());
            case "criarLembreteNaAgenda" -> writeTools.executarCriarLembreteNaAgenda(chamada.args());
            case "apagarVaga" -> writeTools.executarApagarVaga(chamada.args());
            case "lembrarPreferencia" -> writeTools.executarLembrarPreferencia(chamada.args());
            case "marcarStatusDeVaga" -> writeTools.executarMarcarStatus(chamada.args());
            case "atualizarNotaDeVaga" -> writeTools.executarAtualizarNota(chamada.args());
            default -> Map.of("erro", "Ferramenta desconhecida: " + chamada.name());
        };
    }
}

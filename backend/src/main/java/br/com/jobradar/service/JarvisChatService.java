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

    // compatibilidadeComVagasDoFunil analisa DIRETO (sem pré-filtro), porque
    // o grupo já vem pequeno por natureza (é o funil curado do próprio
    // usuário) — mas ainda precisa de um teto rígido: um status como "NOVA"
    // pode ter milhares de vagas, e sem isso um pedido mal-entendido
    // estouraria a cota de IA analisando tudo. Ver bug real reportado: pediu
    // pra analisar 4 vagas da aba Interessado, o modelo usou a ferramenta
    // errada (a de vagas recentes do feed geral) e varreu 3279 vagas.
    private static final int MAX_COMPAT_FUNIL = 15;
    private static final int DEFAULT_COMPAT_FUNIL = 10;

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
            útil, sem enrolação. Se perguntarem seu nome, é Hunter.

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

            Se o usuário perguntar algo sem relação com o Job Radar (vagas,
            candidatura, perfil, salário), explique educadamente que você só ajuda
            com isso.

            Importante sobre o formato da resposta: quando você chama listarVagas
            ou compatibilidadeComVagasRecentes, a interface já mostra cada vaga
            retornada como um card visual (título, empresa, status/score, nota) —
            não repita esse mesmo texto agora, com título e empresa de novo,
            listando de novo cada vaga. Sua resposta em texto deve ser só a análise
            síntese (ex: "2 vagas têm pendência, as outras 8 só aguardam retorno")
            e comentar casos específicos apenas se agregar algo que o card não
            mostra. Escreva como uma mensagem de chat curta — pode usar **negrito**
            e listas com "-", mas não use títulos markdown (#, ##, ###) nem
            separadores "---", isso é formatação de documento, não de chat.
            """;

    public record ChatMessage(String role, String text) {} // role: "user" | "assistant"

    public record ToolResultPayload(String tool, Object data) {}

    public record ChatOutcome(String reply, List<ToolResultPayload> toolResults, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return errorMessage == null;
        }
    }

    public ChatOutcome conversar(List<ChatMessage> historico, String candidateProfile) {
        if (historico == null || historico.isEmpty()) {
            return new ChatOutcome(null, List.of(), "Mensagem vazia.", false);
        }

        List<ChatMessage> recortado = historico.size() > MAX_HISTORY_MESSAGES
                ? historico.subList(historico.size() - MAX_HISTORY_MESSAGES, historico.size())
                : historico;

        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessage m : recortado) {
            contents.add("user".equals(m.role()) ? geminiService.userTurn(m.text()) : geminiService.modelTurn(m.text()));
        }

        List<GeminiService.FunctionDeclaration> tools = buildTools();
        List<ToolResultPayload> toolResults = new ArrayList<>();

        for (int round = 0; round < MAX_TOOL_ROUNDS; round++) {
            GeminiService.ChatResult resultado = geminiService.chat(SYSTEM_INSTRUCTION, contents, tools);
            if (!resultado.ok()) {
                return new ChatOutcome(null, toolResults, resultado.errorMessage(), resultado.rateLimited());
            }
            if (!resultado.isFunctionCall()) {
                return new ChatOutcome(resultado.text(), toolResults, null, false);
            }

            GeminiService.FunctionCallRequest chamada = resultado.functionCall();
            log.info("Jarvis: chamando ferramenta '{}' com args {}", chamada.name(), chamada.args());
            Object dado = executarFerramenta(chamada, candidateProfile);
            toolResults.add(new ToolResultPayload(chamada.name(), dado));

            contents.add(geminiService.buildFunctionCallPart(chamada));
            contents.add(geminiService.buildFunctionResponsePart(chamada.name(), Map.of("result", dado)));
        }

        return new ChatOutcome(
                "Essa pergunta pediu mais passos do que eu consigo resolver de uma vez — tenta reformular de um jeito mais direto?",
                toolResults, null, false
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
                        "busca", Map.of("type", "STRING", "description", "Termo de busca livre em título/empresa/tags, ex: nome de uma empresa específica."),
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
                        compatibilidadeFunilParams)
        );
    }

    private Object executarFerramenta(GeminiService.FunctionCallRequest chamada, String candidateProfile) {
        return switch (chamada.name()) {
            case "listarVagas" -> executarListarVagas(chamada.args());
            case "resumoFunil" -> executarResumoFunil();
            case "compatibilidadeComVagasRecentes" -> executarCompatibilidade(chamada.args(), candidateProfile);
            case "compatibilidadeComVagasDoFunil" -> executarCompatibilidadeFunil(chamada.args(), candidateProfile);
            default -> Map.of("erro", "Ferramenta desconhecida: " + chamada.name());
        };
    }

    // ===================== Implementação das ferramentas =====================

    private Object executarListarVagas(Map<String, Object> args) {
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        Integer dias = args.get("dias") instanceof Number n ? n.intValue() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        int limite = args.get("limite") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;

        LocalDateTime postedAfter = dias != null && dias > 0 ? LocalDateTime.now().minusDays(dias) : null;

        List<Job> filtradas = jobRepository.findAll().stream()
                .filter(j -> statusBate(j, status))
                .filter(j -> postedAfter == null || (j.getPostedAt() != null && j.getPostedAt().isAfter(postedAfter)))
                .filter(j -> busca == null || contemBusca(j, busca))
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

    private Object executarCompatibilidade(Map<String, Object> args, String candidateProfile) {
        int dias = args.get("dias") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        JarvisAssistantService.CompatibilityResult r = jarvisAssistantService.scanCompatibilidade(candidateProfile, dias, null);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", r.available());
        m.put("totalConsiderados", r.totalConsiderados());
        m.put("totalAnalisadosPorIa", r.totalAnalisadosPorIa());
        m.put("hits", r.hits().stream().map(h -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", h.job().getId());
            hit.put("titulo", h.job().getTitle());
            hit.put("empresa", h.job().getCompany());
            hit.put("url", h.job().getUrl());
            hit.put("score", h.score());
            hit.put("resumo", h.resumo());
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
    private Object executarCompatibilidadeFunil(Map<String, Object> args, String candidateProfile) {
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
            MatchScoreService.MatchOutcome outcome = matchScoreService.calcular(j, candidateProfile, null);
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
}

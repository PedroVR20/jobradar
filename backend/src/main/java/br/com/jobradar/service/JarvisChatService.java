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

    // Limite de rounds de function-calling por mensagem — evita loop
    // infinito ou uma mensagem só disparando dezenas de chamadas de ferramenta.
    private static final int MAX_TOOL_ROUNDS = 4;
    // Quantas mensagens do histórico mandar de volta a cada chamada —
    // sem isso o payload (e o custo em tokens) cresceria sem limite numa
    // conversa longa.
    private static final int MAX_HISTORY_MESSAGES = 20;

    private static final String SYSTEM_INSTRUCTION = """
            Você é o Jarvis, assistente do Job Radar — um app pessoal de rastreamento
            de vagas de emprego. Responda sempre em português do Brasil, tom direto e
            útil, sem enrolação.

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

            A ferramenta compatibilidadeComVagasRecentes gasta chamadas reais de IA
            (cota limitada do free tier) — só use quando o usuário pedir de verdade
            uma comparação de compatibilidade/match com o perfil dele, não para
            perguntas simples tipo "quantas vagas eu tenho" ou "quais vagas apliquei"
            (essas usam listarVagas ou resumoFunil, que são gratuitas).

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

        return List.of(
                new GeminiService.FunctionDeclaration("listarVagas",
                        "Lista vagas do usuário, opcionalmente filtradas por status (ex: em andamento, aplicadas), período ou busca por texto. " +
                                "Cada vaga vem com o campo 'notes' — a anotação pessoal que o próprio usuário escreveu naquele card " +
                                "(ex: 'preciso enviar currículo amanhã', 'só esperar retorno', 'fazer teste lógico'). Use esse campo " +
                                "pra responder qualquer pergunta sobre pendência/próximo passo/o que falta em cada candidatura — é a " +
                                "fonte real, não invente etapas genéricas de plataforma quando a nota já diz o que falta (ou que não falta nada).",
                        listarVagasParams),
                new GeminiService.FunctionDeclaration("resumoFunil",
                        "Estatísticas gerais do funil de vagas do usuário (total, novas, aplicadas, em andamento, recusadas). Não usa IA, é gratuito.",
                        semParametros),
                new GeminiService.FunctionDeclaration("compatibilidadeComVagasRecentes",
                        "Compara o perfil/currículo salvo do usuário com vagas publicadas recentemente e avalia compatibilidade real (usa IA de verdade, gasta cota).",
                        compatibilidadeParams)
        );
    }

    private Object executarFerramenta(GeminiService.FunctionCallRequest chamada, String candidateProfile) {
        return switch (chamada.name()) {
            case "listarVagas" -> executarListarVagas(chamada.args());
            case "resumoFunil" -> executarResumoFunil();
            case "compatibilidadeComVagasRecentes" -> executarCompatibilidade(chamada.args(), candidateProfile);
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
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", jobRepository.count());
        m.put("novas", jobRepository.countBySeenFalse());
        m.put("interessadas", jobRepository.countByInterestedTrue());
        m.put("aplicadas", jobRepository.countByAppliedTrue());
        m.put("emAndamento", jobRepository.countByAppliedTrueAndInProgressTrue());
        m.put("recusadas", jobRepository.countByRejectedTrue());
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
}

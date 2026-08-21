package br.com.jobradar.controller;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.service.CoverLetterService;
import br.com.jobradar.service.GeminiService;
import br.com.jobradar.service.InterviewQuestionsService;
import br.com.jobradar.service.JarvisAssistantService;
import br.com.jobradar.service.JarvisChatService;
import br.com.jobradar.service.LearningPlanService;
import br.com.jobradar.service.MatchScoreService;
import br.com.jobradar.service.SalaryEstimateService;
import br.com.jobradar.service.SalaryPredictionService;
import br.com.jobradar.service.SeniorityClassifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fase 5.1 — parte "IA/assistente Hunter" que morava dentro de JobController
 * (1420 linhas antes do split): tudo que chama o Gemini, seja por vaga
 * (carta de apresentação, estimativa de salário, compatibilidade, plano de
 * aprendizado, perguntas de entrevista) ou o chat livre do Hunter em si.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class AssistantController {

    private final JobRepository jobRepository;
    private final GeminiService geminiService;
    private final CoverLetterService coverLetterService;
    private final SalaryEstimateService salaryEstimateService;
    private final SalaryPredictionService salaryPredictionService;
    private final SeniorityClassifier seniorityClassifier;
    private final MatchScoreService matchScoreService;
    private final LearningPlanService learningPlanService;
    private final InterviewQuestionsService interviewQuestionsService;
    private final JarvisAssistantService jarvisAssistantService;
    private final JarvisChatService jarvisChatService;

    // feedbackContext: histórico de avaliações (👍/👎 + comentário) que o
    // usuário deu em gerações anteriores desse mesmo recurso — mantido só no
    // localStorage do frontend (ver useAiFeedback), chega aqui formatado como
    // texto pronto e nunca é persistido no backend, igual ao perfil do candidato.
    public record CoverLetterRequest(String extraContext, String feedbackContext) {}

    /**
     * Gera uma carta de apresentação personalizada pra vaga via Gemini —
     * usa os campos que o Job Radar já tem (não a descrição completa, que
     * não é armazenada) mais qualquer contexto extra que o usuário quiser
     * colar no corpo da requisição, mais o histórico de feedback que o
     * usuário deu em cartas anteriores (se houver). 503 se a IA não estiver
     * configurada (sem GEMINI_API_KEY), 429 se o free tier estourou (por
     * minuto ou por dia — a mensagem diz qual), 502 pra qualquer outra falha
     * do Gemini.
     * POST /api/jobs/{id}/cover-letter  Body (opcional): { "extraContext": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/cover-letter")
    public ResponseEntity<Map<String, Object>> gerarCartaApresentacao(
            @PathVariable Long id, @RequestBody(required = false) CoverLetterRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String extraContext = req != null ? req.extraContext() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            GeminiService.GeminiResult resultado = coverLetterService.gerar(job, extraContext, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            return ResponseEntity.ok(Map.<String, Object>of("coverLetter", resultado.text()));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Faixa salarial estimada — dado real do banco, nunca um chute de IA.
     * Combina duas fontes, nenhuma depende do Gemini estar configurado:
     * {@code predicted} vem de um modelo (regressão Ridge) treinado offline
     * sobre as vagas com salário do banco, sempre disponível quando o
     * modelo carregou (ver SalaryPredictionService — erro médio ~43%,
     * exposto em {@code modelInfo} pra não esconder a incerteza);
     * {@code similarJobs} é a mediana das vagas parecidas (mesma senioridade
     * + tag em comum), disponível só com amostra mínima (>=3).
     * GET /api/jobs/{id}/salary-estimate
     */
    @GetMapping("/{id}/salary-estimate")
    public ResponseEntity<Map<String, Object>> estimarSalario(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            Map<String, Object> body = new HashMap<>();
            boolean anyAvailable = false;

            Optional<Long> predicted = salaryPredictionService.predict(
                    job.getSeniority(), tagList(job.getTags()), job.getWorkplaceType(), job.getState());
            if (predicted.isPresent()) {
                body.put("predicted", predicted.get());
                SalaryPredictionService.ModelInfo info = salaryPredictionService.getModelInfo();
                body.put("modelInfo", Map.of(
                        "nSamples", info.nSamples(), "r2", info.r2(), "maePercent", info.maePercent()));
                anyAvailable = true;
            }

            Optional<SalaryEstimateService.SalaryEstimate> estimativa = salaryEstimateService.estimate(job);
            if (estimativa.isPresent()) {
                SalaryEstimateService.SalaryEstimate e = estimativa.get();
                Map<String, Object> similar = new HashMap<>();
                similar.put("sampleSize", e.sampleSize());
                similar.put("min", e.min());
                similar.put("max", e.max());
                similar.put("median", e.median());
                body.put("similarJobs", similar);
                anyAvailable = true;
            }

            body.put("available", anyAvailable);
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    private List<String> tagList(String tags) {
        return tags == null || tags.isBlank() ? List.of() : Arrays.asList(tags.split(","));
    }

    public record CandidateProfileRequest(String candidateProfile, String feedbackContext) {}

    /**
     * Estimativa personalizada: usa a senioridade/stack extraídas do
     * currículo/perfil salvo pelo candidato (nunca persistido — vem no
     * corpo do request) em vez dos dados da vaga, mantendo modalidade e
     * estado da vaga (isso não muda com quem se candidata). Sempre 200 —
     * available=false só se o modelo não tiver carregado ou o perfil vier
     * vazio, nunca erro.
     * POST /api/jobs/{id}/salary-estimate/personalized  Body: { "candidateProfile": "..." }
     */
    @PostMapping("/{id}/salary-estimate/personalized")
    public ResponseEntity<Map<String, Object>> estimarSalarioPersonalizado(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            Map<String, Object> body = new HashMap<>();
            if (perfil == null || perfil.isBlank() || !salaryPredictionService.isLoaded()) {
                body.put("available", false);
                return ResponseEntity.ok(body);
            }

            Set<String> stackDoCurriculo = salaryPredictionService.extractTagsFromText(perfil);
            String senioridadeInferida = seniorityClassifier.classify(perfil, String.join(",", stackDoCurriculo));

            Optional<Long> predicted = salaryPredictionService.predict(
                    senioridadeInferida, stackDoCurriculo, job.getWorkplaceType(), job.getState());
            if (predicted.isEmpty()) {
                body.put("available", false);
                return ResponseEntity.ok(body);
            }
            body.put("available", true);
            body.put("predicted", predicted.get());
            body.put("inferredSeniority", senioridadeInferida);
            body.put("inferredStack", stackDoCurriculo);
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Compatibilidade entre o perfil/currículo do candidato (enviado pelo
     * frontend — nunca persistido no backend) e a vaga, considerando também
     * o histórico de feedback do usuário sobre análises anteriores, se
     * houver. 503 sem IA configurada, 429 em rate limit, 502 pra outras falhas.
     * POST /api/jobs/{id}/match-score  Body: { "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/match-score")
    public ResponseEntity<Map<String, Object>> calcularCompatibilidade(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            MatchScoreService.MatchOutcome resultado = matchScoreService.calcular(job, perfil, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            MatchScoreService.MatchResult r = resultado.result();
            Map<String, Object> body = new HashMap<>();
            body.put("score", r.score());
            body.put("pontosFortes", r.pontosFortes());
            body.put("pontosFaltando", r.pontosFaltando());
            body.put("resumo", r.resumo());
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    public record LearningPlanRequest(String gap, String candidateProfile, String feedbackContext) {}

    /**
     * "Contramedida" pra um ponto a desenvolver específico apontado pela
     * análise de compatibilidade (match-score) — plano de ação prático pra
     * fechar aquela lacuna, considerando o perfil atual do candidato como
     * ponto de partida. Mesma regra de privacidade das outras rotas de IA:
     * perfil nunca é persistido no backend, só chega junto do request.
     * POST /api/jobs/{id}/learning-plan  Body: { "gap": "...", "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/learning-plan")
    public ResponseEntity<Map<String, Object>> gerarPlanoDeAprendizado(
            @PathVariable Long id, @RequestBody(required = false) LearningPlanRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        if (req == null || req.gap() == null || req.gap().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe qual ponto a desenvolver quer um plano."));
        }
        return jobRepository.findById(id).map(job -> {
            LearningPlanService.PlanOutcome resultado = learningPlanService.gerar(job, req.gap(), req.candidateProfile(), req.feedbackContext());
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            LearningPlanService.LearningPlan p = resultado.plan();
            Map<String, Object> body = new HashMap<>();
            body.put("resumo", p.resumo());
            body.put("tempoEstimado", p.tempoEstimado());
            body.put("passos", p.passos());
            return ResponseEntity.ok(body);
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Perguntas prováveis de entrevista pra vaga, opcionalmente ajustadas ao
     * perfil do candidato (mesma regra de privacidade do match-score — só
     * chega no backend se o frontend mandar nesse request específico).
     * POST /api/jobs/{id}/interview-questions  Body opcional: { "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/{id}/interview-questions")
    public ResponseEntity<Map<String, Object>> gerarPerguntasEntrevista(
            @PathVariable Long id, @RequestBody(required = false) CandidateProfileRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        return jobRepository.findById(id).map(job -> {
            String perfil = req != null ? req.candidateProfile() : null;
            String feedback = req != null ? req.feedbackContext() : null;
            InterviewQuestionsService.QuestionsOutcome resultado = interviewQuestionsService.gerar(job, perfil, feedback);
            if (!resultado.ok()) {
                return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                        .body(Map.<String, Object>of("error", resultado.errorMessage()));
            }
            return ResponseEntity.ok(Map.<String, Object>of("questions", resultado.questions()));
        }).orElse(ResponseEntity.notFound().build());
    }

    public record CompatibilityScanRequest(String candidateProfile, Integer days, String feedbackContext) {}

    /**
     * Ação "🤖 Jarvis": compatibilidade do perfil salvo com vagas recentes.
     * Pré-filtra por sobreposição de tags/senioridade (sem IA, sempre roda)
     * e só chama o Gemini de verdade nas top vagas do pré-filtro — ver
     * JarvisAssistantService pro porquê. 200 sempre, com available=false
     * quando não há perfil, e errorMessage preenchido se a IA falhar em
     * todas as tentativas (não depende do Gemini estar habilitado pra
     * responder "sem dados" — só falha graciosamente se estiver desligado).
     * POST /api/jobs/assistant/compatibility-scan
     * Body: { "candidateProfile": "...", "days": 1, "feedbackContext": "..." }
     */
    @PostMapping("/assistant/compatibility-scan")
    public Map<String, Object> assistantCompatibilityScan(@RequestBody(required = false) CompatibilityScanRequest req) {
        String perfil = req != null ? req.candidateProfile() : null;
        int dias = req != null && req.days() != null ? req.days() : 1;
        String feedback = req != null ? req.feedbackContext() : null;

        JarvisAssistantService.CompatibilityResult resultado = jarvisAssistantService.scanCompatibilidade(perfil, dias, feedback);

        Map<String, Object> body = new HashMap<>();
        body.put("available", resultado.available());
        body.put("totalConsiderados", resultado.totalConsiderados());
        body.put("totalAnalisadosPorIa", resultado.totalAnalisadosPorIa());
        body.put("errorMessage", resultado.errorMessage());
        body.put("hits", resultado.hits().stream().map(h -> {
            Map<String, Object> hit = new HashMap<>();
            Map<String, Object> jobDto = new HashMap<>();
            jobDto.put("id", h.job().getId());
            jobDto.put("title", h.job().getTitle());
            jobDto.put("company", h.job().getCompany());
            jobDto.put("url", h.job().getUrl());
            jobDto.put("source", h.job().getSource());
            hit.put("job", jobDto);
            hit.put("score", h.score());
            hit.put("pontosFortes", h.pontosFortes());
            hit.put("pontosFaltando", h.pontosFaltando());
            hit.put("resumo", h.resumo());
            return hit;
        }).toList());
        return body;
    }

    // imageMimeType/imageBase64: só preenchido na mensagem mais recente,
    // quando o usuário anexa um print no chat (ver JarvisChatService.
    // ChatMessage e GeminiService.userTurnWithImage). base64Data vem sem o
    // prefixo "data:image/png;base64," — o frontend já manda só o miolo.
    public record ChatMessageDto(String role, String text, String imageMimeType, String imageBase64) {}

    // feedbackContext: 👍/👎 salvos pelo usuário nos cards de compatibilidade/
    // plano de ação (useAiFeedback no frontend) — mesmo texto que já
    // alimenta match-score e learning-plan, agora também chega no chat.
    // replyStyle: preset de tom escolhido nos chips do rodapé do chat
    // ("conciso"/"formal"), null = padrão. Ver comentário do parâmetro
    // homônimo em JarvisChatService.conversar.
    public record ChatRequest(List<ChatMessageDto> history, String candidateProfile, String feedbackContext, String memoryContext,
                               Boolean fastMode, String replyStyle) {}

    /**
     * Chat livre do Jarvis — diferente do compatibility-scan (ação fixa),
     * aqui o Gemini decide sozinho quais ferramentas chamar (listar vagas,
     * resumo do funil, compatibilidade) a partir da mensagem em linguagem
     * natural, via function-calling de verdade (ver JarvisChatService).
     * 503 sem IA configurada, 429 em rate limit, 502 pra outras falhas.
     * POST /api/jobs/assistant/chat
     * Body: { "history": [{"role":"user","text":"...","imageMimeType":null,"imageBase64":null}, ...], "candidateProfile": "...", "feedbackContext": "..." }
     */
    @PostMapping("/assistant/chat")
    public ResponseEntity<Map<String, Object>> assistantChat(@RequestBody(required = false) ChatRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503)
                    .body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        List<JarvisChatService.ChatMessage> historico = req != null && req.history() != null
                ? req.history().stream()
                        .map(m -> new JarvisChatService.ChatMessage(m.role(), m.text(), m.imageMimeType(), m.imageBase64()))
                        .toList()
                : List.of();
        String perfil = req != null ? req.candidateProfile() : null;
        String feedbackContext = req != null ? req.feedbackContext() : null;
        String memoryContext = req != null ? req.memoryContext() : null;

        JarvisChatService.ChatOutcome resultado = jarvisChatService.conversar(historico, perfil, feedbackContext, memoryContext);
        if (!resultado.ok()) {
            return ResponseEntity.status(resultado.rateLimited() ? 429 : 502)
                    .body(Map.<String, Object>of("error", resultado.errorMessage()));
        }
        Map<String, Object> body = new HashMap<>();
        body.put("reply", resultado.reply());
        body.put("thinking", resultado.thinking());
        body.put("toolResults", resultado.toolResults());
        body.put("pendingQuestion", resultado.pendingQuestion());
        return ResponseEntity.ok(body);
    }

    // Só pra esse endpoint de streaming — o resto do controller usa o
    // request thread normal (Tomcat) sem problema, mas SseEmitter precisa
    // devolver a conexão pro chamador IMEDIATAMENTE e continuar mandando
    // eventos de uma thread separada por trás, senão a conexão HTTP nunca
    // fica "aberta" de verdade pro navegador começar a ler o stream.
    private final ExecutorService sseExecutor = Executors.newCachedThreadPool();

    /**
     * Mesmo chat livre de /assistant/chat, só que narrando CADA passo real
     * (chamada de ferramenta) em tempo real via Server-Sent Events, em vez
     * de devolver tudo de uma vez só no final. O endpoint antigo continua
     * existindo do jeito que sempre foi — esse aqui é aditivo, pro frontend
     * poder cair de volta nele se precisar.
     *
     * Eventos emitidos:
     *   tool_call  → {"tool": "listarVagas"}          (antes de cada ferramenta rodar)
     *   final      → {reply, thinking, toolResults, pendingQuestion}  (mesmo shape do endpoint síncrono)
     *   error      → {"error": "...", "rateLimited": bool}
     *
     * POST /api/jobs/assistant/chat/stream
     */
    @PostMapping(value = "/assistant/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter assistantChatStream(@RequestBody(required = false) ChatRequest req) {
        SseEmitter emitter = new SseEmitter(120_000L);

        if (!geminiService.isEnabled()) {
            sendSseEvent(emitter, "error", Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
            emitter.complete();
            return emitter;
        }

        List<JarvisChatService.ChatMessage> historico = req != null && req.history() != null
                ? req.history().stream()
                        .map(m -> new JarvisChatService.ChatMessage(m.role(), m.text(), m.imageMimeType(), m.imageBase64()))
                        .toList()
                : List.of();
        String perfil = req != null ? req.candidateProfile() : null;
        String feedbackContext = req != null ? req.feedbackContext() : null;
        String memoryContext = req != null ? req.memoryContext() : null;
        boolean fastMode = req != null && Boolean.TRUE.equals(req.fastMode());
        String replyStyle = req != null ? req.replyStyle() : null;

        // "Parar" (botão no frontend, ver AbortController em handleSend):
        // fechar a conexão dispara onCompletion/onError aqui — o mais cedo
        // que o loop de function-calling em conversar() percebe isso é no
        // INÍCIO da próxima rodada (ver ChatProgressListener.isCancelled),
        // não no meio de uma chamada ao Gemini já em voo. Ainda evita gastar
        // as próximas ferramentas de uma pergunta que dispara várias.
        java.util.concurrent.atomic.AtomicBoolean cancelado = new java.util.concurrent.atomic.AtomicBoolean(false);
        emitter.onCompletion(() -> cancelado.set(true));
        emitter.onTimeout(() -> cancelado.set(true));
        emitter.onError(e -> cancelado.set(true));

        sseExecutor.execute(() -> {
            try {
                JarvisChatService.ChatOutcome resultado = jarvisChatService.conversar(
                        historico, perfil, feedbackContext, memoryContext,
                        new JarvisChatService.ChatProgressListener() {
                            @Override
                            public void onToolCall(String toolName) {
                                sendSseEvent(emitter, "tool_call", Map.of("tool", toolName));
                            }

                            @Override
                            public boolean isCancelled() {
                                return cancelado.get();
                            }

                            @Override
                            public void onAnswerChunk(String chunk) {
                                sendSseEvent(emitter, "answer_chunk", Map.of("text", chunk));
                            }
                        }, fastMode, replyStyle);

                if (cancelado.get()) {
                    // Já não tem mais ninguém ouvindo do outro lado — não
                    // tenta mandar nem "final" nem "error", só encerra.
                    return;
                }
                if (!resultado.ok()) {
                    sendSseEvent(emitter, "error", Map.of(
                            "error", resultado.errorMessage(),
                            "rateLimited", resultado.rateLimited()));
                } else {
                    Map<String, Object> finalBody = new HashMap<>();
                    finalBody.put("reply", resultado.reply());
                    finalBody.put("thinking", resultado.thinking());
                    finalBody.put("toolResults", resultado.toolResults());
                    finalBody.put("pendingQuestion", resultado.pendingQuestion());
                    sendSseEvent(emitter, "final", finalBody);
                }
                emitter.complete();
            } catch (Exception e) {
                log.warn("Erro inesperado no chat em streaming: {}", e.getMessage());
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    private void sendSseEvent(SseEmitter emitter, String name, Object data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            // Cliente desconectou (fechou a aba, trocou de conversa no meio) —
            // não tem pra quem mandar o resto, só ignora e segue.
        }
    }
}

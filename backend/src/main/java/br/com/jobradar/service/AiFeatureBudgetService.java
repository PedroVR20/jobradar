package br.com.jobradar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Fase 9.8 — teto de chamadas de IA generativa POR FUNCIONALIDADE, não só
 * por key (que é o que o rodízio de {@link GeminiService} já controla).
 * Motivo: 29 keys em rodízio protegem contra estourar a cota do Google, mas
 * não protegem contra UMA funcionalidade específica (ex: verificação de
 * duplicata, que roda em laço sobre vários grupos) consumir
 * desproporcionalmente a cota compartilhada num único dia — o mesmo tipo de
 * problema que o bug real do {@code /duplicates} (Fase 11.1, até 20
 * chamadas síncronas dentro de UM request) já mostrou que pode acontecer.
 *
 * <p>Contador em memória, reset por data (mesmo padrão do
 * {@code KeySlot.resetIfNewDay} em GeminiService) — sem Redis/banco, app
 * pessoal de um usuário só não precisa que o contador sobreviva a um
 * restart do backend.</p>
 *
 * <p>Deliberadamente NÃO cobre o chat livre do Hunter (function-calling,
 * JarvisChatService.conversar) — esse é o uso "normal" do app, orçamento
 * por chamada ali seria travar a experiência principal. Cobre só as
 * ferramentas de IA GENERATIVA sob demanda: carta, compatibilidade
 * (match-score), plano de aprendizado, perguntas de entrevista, verificação
 * de duplicata, resumo semanal — e o mesmo teto vale tanto pra quando o
 * usuário clica o botão quanto pra quando o Hunter chama a mesma ferramenta
 * pelo chat (ver executarGerarCarta/executarCompatibilidadeFunil em
 * JarvisReadTools — chamam os mesmos services, herdam o mesmo teto de
 * graça).</p>
 */
@Service
@Slf4j
public class AiFeatureBudgetService {

    public static final String COVER_LETTER = "cover-letter";
    public static final String MATCH_SCORE = "match-score";
    public static final String LEARNING_PLAN = "learning-plan";
    public static final String INTERVIEW_QUESTIONS = "interview-questions";
    public static final String DUPLICATE_VERIFY = "duplicate-verify";
    public static final String WEEKLY_DIGEST = "weekly-digest";
    // Fase 9.1 — 1 unidade por CHAMADA (cobre até AiTriageService.MAX_LOTE
    // vagas de uma vez), não por vaga — teto bem menor que os outros de
    // propósito, já reflete isso.
    public static final String BATCH_TRIAGE = "batch-triage";

    // Valores generosos de propósito — o objetivo aqui não é economizar
    // (as 29 keys já dão bastante folga no free tier), é ter um TETO
    // qualquer contra uso descontrolado/em laço de uma única funcionalidade,
    // com aviso claro em vez de só "gastar cota silenciosamente até
    // esbarrar em 429 sem ninguém entender por quê" (que foi exatamente o
    // sintoma real que motivou esse item, ver Fase 11.1/11.2).
    @Value("${hunter.ai-budget.cover-letter:150}")
    private int limiteCoverLetter;
    @Value("${hunter.ai-budget.match-score:150}")
    private int limiteMatchScore;
    @Value("${hunter.ai-budget.learning-plan:100}")
    private int limiteLearningPlan;
    @Value("${hunter.ai-budget.interview-questions:100}")
    private int limiteInterviewQuestions;
    @Value("${hunter.ai-budget.duplicate-verify:200}")
    private int limiteDuplicateVerify;
    @Value("${hunter.ai-budget.weekly-digest:10}")
    private int limiteWeeklyDigest;
    @Value("${hunter.ai-budget.batch-triage:30}")
    private int limiteBatchTriage;

    private Map<String, Integer> limites;

    private final Map<String, AtomicInteger> contadores = new ConcurrentHashMap<>();
    private final Map<String, LocalDate> ultimoReset = new ConcurrentHashMap<>();

    private Map<String, Integer> limites() {
        // Montado sob demanda (não em @PostConstruct) pra não depender de
        // ordem de inicialização do Spring com os campos @Value — barato o
        // bastante (6 entradas) pra não precisar cachear isso.
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put(COVER_LETTER, limiteCoverLetter);
        m.put(MATCH_SCORE, limiteMatchScore);
        m.put(LEARNING_PLAN, limiteLearningPlan);
        m.put(INTERVIEW_QUESTIONS, limiteInterviewQuestions);
        m.put(DUPLICATE_VERIFY, limiteDuplicateVerify);
        m.put(WEEKLY_DIGEST, limiteWeeklyDigest);
        m.put(BATCH_TRIAGE, limiteBatchTriage);
        return m;
    }

    /**
     * @return true se ainda há orçamento hoje pra essa funcionalidade (e já
     *         CONSOME uma unidade — chame só imediatamente antes da
     *         chamada real ao Gemini, não antes de validações que podem
     *         abortar sem gastar nada); false se o teto diário já foi
     *         atingido.
     */
    public synchronized boolean permitir(String feature) {
        int limite = limites().getOrDefault(feature, Integer.MAX_VALUE);
        LocalDate hoje = LocalDate.now();
        if (!hoje.equals(ultimoReset.get(feature))) {
            contadores.put(feature, new AtomicInteger(0));
            ultimoReset.put(feature, hoje);
        }
        AtomicInteger contador = contadores.computeIfAbsent(feature, k -> new AtomicInteger(0));
        if (contador.get() >= limite) {
            log.warn("Orçamento diário de IA atingido pra '{}': {}/{}", feature, contador.get(), limite);
            return false;
        }
        contador.incrementAndGet();
        return true;
    }

    public String mensagemLimiteAtingido(String feature) {
        int limite = limites().getOrDefault(feature, 0);
        return "Orçamento diário de " + limite + " chamadas de IA pra essa funcionalidade foi atingido — "
                + "libera de novo à meia-noite. Ajustável em application.yml (hunter.ai-budget." + feature + ").";
    }

    public record FeatureStatus(String feature, int usadoHoje, int limite) {}

    /** Usado pelo painel de Configurações — visibilidade do orçamento, sem esperar esbarrar nele pra descobrir. */
    public List<FeatureStatus> status() {
        LocalDate hoje = LocalDate.now();
        return limites().entrySet().stream()
                .map(e -> {
                    boolean valido = hoje.equals(ultimoReset.get(e.getKey()));
                    int usado = valido ? contadores.getOrDefault(e.getKey(), new AtomicInteger(0)).get() : 0;
                    return new FeatureStatus(e.getKey(), usado, e.getValue());
                })
                .toList();
    }
}

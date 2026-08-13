package br.com.jobradar.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Reimplementação em Java puro do treino que antes só existia em
 * scripts/train_salary_model.py (Ridge regression + validação cruzada pra
 * escolher a regularização) — permite retreinar sob demanda a partir do
 * app (ver endpoint {@code POST /api/jobs/admin/retrain-salary-model}), com
 * o modelo novo entrando em uso imediatamente via
 * {@link SalaryPredictionService#reload}, sem precisar de runtime Python em
 * produção nem reconstruir o container Docker. O script Python continua
 * existindo como referência/alternativa manual, mas deixa de ser o único
 * jeito de retreinar.
 *
 * <p>Port fiel da lógica de feature engineering (allowlist de tags, bucket
 * dos 8 estados mais frequentes) e do algoritmo (RidgeCV: 30 valores de
 * alpha em log-espaço [-2, 3], escolhendo o que maximiza R² médio numa
 * validação cruzada — mesmo critério que RidgeCV usa por padrão quando cv é
 * um inteiro, já que Ridge.score() é R²). Diferente da ideia original de
 * "guardar 20% pra teste": com um catálogo pequeno (algumas centenas de
 * vagas com salário), um único split de teste é ruidoso demais pra confiar
 * (visto na prática — retreino real oscilando 33%→69% de erro sem o modelo
 * ter piorado de verdade). Em vez disso, a métrica reportada vem de
 * validação cruzada OUT-OF-FOLD sobre o catálogo INTEIRO (ver
 * {@link #foldIndicesEstavel}), e o modelo final é treinado com 100% do
 * dado disponível.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SalaryModelTrainerService {

    private static final long SALARY_FLOOR = 300;
    private static final long SALARY_CEIL = 60000;
    // Era 3 — uma tag que aparece só 3 vezes em centenas de linhas (bem
    // menos de 1% dos dados) ainda ganhava sua própria coluna one-hot, com
    // coeficiente livre pra se ajustar só a essas 3 amostras. Testado na
    // prática (subiu de 3 pra 8): não foi a causa principal da instabilidade
    // de métrica entre retreinos — só ~42 features pra ~485 linhas já era
    // uma proporção saudável mesmo com MIN_TAG_FREQ=3. A causa de verdade
    // era o tamanho do conjunto de teste (ver comentário grande no início de
    // treinar()); mantido em 8 mesmo assim, é mais conservador sem custar
    // nada.
    private static final int MIN_TAG_FREQ = 8;
    private static final int TOP_STATES_COUNT = 8;
    private static final int CV_FOLDS = 5;
    private static final int MIN_ROWS_PARA_TREINAR = 30;

    private static final List<String> SENIORITY_VOCAB = List.of("ESTAGIO", "JUNIOR", "PLENO", "SENIOR", "NAO_INFORMADO");
    private static final List<String> WORKPLACE_VOCAB = List.of("REMOTO", "HIBRIDO", "PRESENCIAL", "DESCONHECIDO");

    // Mesmo allowlist do script Python — restringe "tags" a vocabulário de
    // tecnologia/cargo conhecido, descartando ruído geográfico (algumas
    // fontes misturam cidade/estado nas tags) e frases genéricas de
    // requisito que também apareceram misturadas.
    private static final Set<String> TECH_ALLOWLIST = Set.of(
            "python", "java", "javascript", "typescript", "csharp", "c#", "c++", ".net",
            "aws", "azure", "gcp", "cloud", "docker", "kubernetes", "devops",
            "postgres", "postgresql", "mysql", "sql", "oracle", "mongodb",
            "power bi", "business intelligence", "analytics", "dados", "data science",
            "machine learning", "inteligencia artificial", "inteligência artificial",
            "react", "angular", "vue", "node", "php", "spring", "django", "flask",
            "kotlin", "swift", "ruby", "go", "rust", "html", "css",
            "sap", "salesforce", "servicenow", "itil", "erp", "crm",
            "redes", "infraestrutura", "suporte", "telecomunicacoes",
            "seguranca da informacao", "cybersecurity", "qa", "testes", "automacao", "rpa",
            "full stack", "backend", "frontend", "mobile", "software", "tecnologia",
            "sistemas", "programador", "desenvolvedor", "analista", "engenheiro",
            "especialista", "arquiteto", "coordenador"
    );

    private final SalaryEstimateService salaryEstimateService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record TrainResult(com.fasterxml.jackson.databind.JsonNode modelJson, int nSamples, double r2, double maeBrl, double maePercent) {}

    public TrainResult treinar() {
        List<SalaryEstimateService.TrainingRow> raw = salaryEstimateService.exportTrainingData();
        log.info("Retreino: {} linhas brutas exportadas", raw.size());

        List<SalaryEstimateService.TrainingRow> rows = raw.stream()
                .filter(r -> r.salaryMonthly() >= SALARY_FLOOR && r.salaryMonthly() <= SALARY_CEIL)
                .toList();
        log.info("Retreino: {} linhas após filtro de outliers [{}, {}]", rows.size(), SALARY_FLOOR, SALARY_CEIL);
        if (rows.size() < MIN_ROWS_PARA_TREINAR) {
            throw new IllegalStateException("Poucas vagas com salário pra treinar (" + rows.size()
                    + ") — precisa de pelo menos " + MIN_ROWS_PARA_TREINAR + ".");
        }

        List<String> topStates = topStates(rows);
        List<String> stateVocab = new ArrayList<>(topStates);
        stateVocab.add("outros");
        stateVocab.add("desconhecido");

        List<String> tagVocab = tagVocab(rows);

        List<String> featureNames = new ArrayList<>();
        for (String s : SENIORITY_VOCAB) featureNames.add("seniority=" + s);
        for (String w : WORKPLACE_VOCAB) featureNames.add("workplace=" + w);
        for (String st : stateVocab) featureNames.add("state=" + st);
        for (String t : tagVocab) featureNames.add("tag=" + t);
        Map<String, Integer> featureIndex = new HashMap<>();
        for (int i = 0; i < featureNames.size(); i++) featureIndex.put(featureNames.get(i), i);
        log.info("Retreino: {} features totais ({} tags no vocabulário)", featureNames.size(), tagVocab.size());

        int p = featureNames.size();
        int n = rows.size();
        double[][] X = new double[n][p];
        double[] yRaw = new double[n];
        for (int i = 0; i < n; i++) {
            SalaryEstimateService.TrainingRow r = rows.get(i);
            buildFeatureVector(r, X[i], featureIndex, stateVocab, tagVocab);
            yRaw[i] = r.salaryMonthly();
        }
        double[] y = new double[n];
        for (int i = 0; i < n; i++) y[i] = Math.log1p(yRaw[i]);

        // --- avaliação: validação cruzada 5-fold OUT-OF-FOLD, não mais um único split 80/20 ---
        // ANTES (v1): embaralhava um array de tamanho n com seed fixa — não
        // era ESTÁVEL entre retreinos (n muda a cada vaga nova, mesma seed
        // sobre array de tamanho diferente = permutação totalmente
        // diferente). ANTES (v2, correção anterior): trocado por split
        // treino/teste determinístico por hash do id da vaga — resolvia a
        // instabilidade, mas ainda tinha um problema de fundo: com só
        // ~500 vagas, o conjunto de teste (20%) é ~100 linhas — pequeno
        // demais pra uma métrica confiável, ela balança bastante só pela
        // sorte de QUAIS vagas calharam no teste dessa vez (visto na
        // prática: retreino real oscilando de erro 33%→69% sem o modelo
        // ter piorado de verdade).
        //
        // AGORA: cada vaga cai numa de 5 "dobras" por hash determinístico do
        // seu id (ver foldIndicesEstavel) — mesma ideia de estabilidade de
        // antes, mas agora usada pra VALIDAÇÃO CRUZADA de verdade. Cada
        // linha é prevista exatamente UMA vez, na rodada em que a dobra
        // dela ficou de fora do treino ("out-of-fold") — ou seja, a métrica
        // reportada usa as ~500 linhas INTEIRAS pra avaliar (não só ~100),
        // muito mais estável. E como nenhuma linha nunca é avaliada pelo
        // mesmo ajuste que a treinou, não tem vazamento de dado mesmo
        // treinando o modelo final com 100% do catálogo (ver mais abaixo).
        int[][] folds = foldIndicesEstavel(rows, CV_FOLDS);

        double bestAlpha = escolherMelhorAlpha(X, y, folds);
        log.info("Retreino: melhor alpha (regularização) = {}", bestAlpha);

        double[] oofPredLog = crossValPredictions(X, y, bestAlpha, folds);
        double r2 = r2Score(y, oofPredLog);
        double[] oofPredBrl = new double[oofPredLog.length];
        for (int i = 0; i < oofPredLog.length; i++) oofPredBrl[i] = Math.expm1(oofPredLog[i]);
        double maeBrl = mae(yRaw, oofPredBrl);
        double maePercent = maePercent(yRaw, oofPredBrl);

        log.info("Retreino: R²(log, CV out-of-fold, {} dobras)={} MAE=R${} erro%={}",
                CV_FOLDS, round(r2, 3), round(maeBrl, 0), round(maePercent, 1));

        // Modelo de produção treinado com TODO o catálogo disponível —
        // diferente de antes (só 80%, guardando 20% só pra medir). Como a
        // métrica reportada já vem inteira de validação cruzada (nunca
        // avalia uma linha com o modelo que a treinou), não tem mais motivo
        // pra deixar uma fatia do catálogo de fora do modelo que entra em
        // produção — principalmente com um catálogo pequeno, onde cada vaga
        // a mais ajuda de verdade.
        RidgeFit finalFit = fitRidge(X, y, bestAlpha);

        ObjectNode model = mapper.createObjectNode();
        model.put("trainedAt", LocalDate.now().toString());
        model.put("nSamples", n);
        model.put("salaryFloor", SALARY_FLOOR);
        model.put("salaryCeil", SALARY_CEIL);
        model.put("logTarget", true);
        model.put("intercept", finalFit.intercept());
        ArrayNode fn = model.putArray("featureNames");
        featureNames.forEach(fn::add);
        ArrayNode coefs = model.putArray("coefficients");
        for (double c : finalFit.weights()) coefs.add(c);
        ArrayNode senVocabNode = model.putArray("seniorityVocab");
        SENIORITY_VOCAB.forEach(senVocabNode::add);
        ArrayNode wpVocabNode = model.putArray("workplaceVocab");
        WORKPLACE_VOCAB.forEach(wpVocabNode::add);
        ArrayNode stVocabNode = model.putArray("stateVocab");
        stateVocab.forEach(stVocabNode::add);
        ArrayNode tagVocabNode = model.putArray("tagVocab");
        tagVocab.forEach(tagVocabNode::add);
        ObjectNode metrics = model.putObject("metrics");
        metrics.put("r2LogScale", round(r2, 4));
        metrics.put("maeBrl", round(maeBrl, 2));
        metrics.put("maePercent", round(maePercent, 1));
        // Não é mais um split fixo — é validação cruzada out-of-fold sobre
        // as n linhas inteiras (ver comentário no início do treino).
        metrics.put("avaliacao", "cv-out-of-fold-" + CV_FOLDS + "-dobras");
        metrics.put("cvSamples", n);

        return new TrainResult(model, n, round(r2, 4), round(maeBrl, 2), round(maePercent, 1));
    }

    // ===================== feature engineering =====================

    private List<String> topStates(List<SalaryEstimateService.TrainingRow> rows) {
        Map<String, Long> counts = rows.stream()
                .filter(r -> r.state() != null && !r.state().isBlank())
                .collect(Collectors.groupingBy(r -> norm(r.state()), Collectors.counting()));
        return counts.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(TOP_STATES_COUNT)
                .map(Map.Entry::getKey)
                .toList();
    }

    private List<String> tagVocab(List<SalaryEstimateService.TrainingRow> rows) {
        Map<String, Long> tagCounts = new LinkedHashMap<>();
        for (SalaryEstimateService.TrainingRow r : rows) {
            for (String t : cleanTags(r.tags())) tagCounts.merge(t, 1L, Long::sum);
        }
        return tagCounts.entrySet().stream()
                .filter(e -> e.getValue() >= MIN_TAG_FREQ)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    private String norm(String s) {
        return s.trim().toLowerCase();
    }

    private List<String> cleanTags(List<String> tags) {
        if (tags == null) return List.of();
        TreeSet<String> set = new TreeSet<>();
        for (String t : tags) {
            String n = norm(t);
            if (TECH_ALLOWLIST.contains(n)) set.add(n);
        }
        return new ArrayList<>(set);
    }

    private void buildFeatureVector(SalaryEstimateService.TrainingRow r, double[] vec,
                                     Map<String, Integer> featureIndex, List<String> stateVocab, List<String> tagVocab) {
        String sen = SENIORITY_VOCAB.contains(r.seniority()) ? r.seniority() : "NAO_INFORMADO";
        setFeature(vec, featureIndex, "seniority=" + sen);

        String wp = r.workplaceType() != null && WORKPLACE_VOCAB.contains(r.workplaceType()) ? r.workplaceType() : "DESCONHECIDO";
        setFeature(vec, featureIndex, "workplace=" + wp);

        String stateBucket;
        if (r.state() == null || r.state().isBlank()) {
            stateBucket = "desconhecido";
        } else {
            String n = norm(r.state());
            stateBucket = stateVocab.contains(n) ? n : "outros";
        }
        setFeature(vec, featureIndex, "state=" + stateBucket);

        for (String t : cleanTags(r.tags())) {
            if (tagVocab.contains(t)) setFeature(vec, featureIndex, "tag=" + t);
        }
    }

    private void setFeature(double[] vec, Map<String, Integer> featureIndex, String name) {
        Integer idx = featureIndex.get(name);
        if (idx != null) vec[idx] = 1.0;
    }

    // ===================== regularização (RidgeCV) =====================

    private double escolherMelhorAlpha(double[][] X, double[] y, int[][] folds) {
        double[] alphas = logspace(-2, 3, 30);
        double bestAlpha = alphas[0];
        double bestScore = Double.NEGATIVE_INFINITY;
        for (double alpha : alphas) {
            double scoreSum = 0;
            int dobrasValidas = 0;
            for (int[] foldTest : folds) {
                if (foldTest.length == 0) continue; // salvaguarda, ver foldIndicesEstavel
                int[] foldTrain = complement(foldTest, X.length);
                RidgeFit fit = fitRidge(select(X, foldTrain), select(y, foldTrain), alpha);
                double[] pred = predictAll(fit, select(X, foldTest));
                scoreSum += r2Score(select(y, foldTest), pred);
                dobrasValidas++;
            }
            double avgScore = dobrasValidas > 0 ? scoreSum / dobrasValidas : Double.NEGATIVE_INFINITY;
            if (avgScore > bestScore) {
                bestScore = avgScore;
                bestAlpha = alpha;
            }
        }
        return bestAlpha;
    }

    // Previsão OUT-OF-FOLD: pra cada dobra, treina só com o resto e prevê a
    // dobra que ficou de fora — cada linha do dataset acaba prevista
    // exatamente uma vez, sempre por um ajuste que NUNCA a viu. É assim que
    // dá pra reportar uma métrica sobre o dataset inteiro (não só uma fatia
    // de teste) sem vazamento de dado.
    private double[] crossValPredictions(double[][] X, double[] y, double alpha, int[][] folds) {
        double[] oof = new double[y.length];
        for (int[] foldTest : folds) {
            if (foldTest.length == 0) continue;
            int[] foldTrain = complement(foldTest, X.length);
            RidgeFit fit = fitRidge(select(X, foldTrain), select(y, foldTrain), alpha);
            double[] pred = predictAll(fit, select(X, foldTest));
            for (int i = 0; i < foldTest.length; i++) oof[foldTest[i]] = pred[i];
        }
        return oof;
    }

    // Fibonacci hashing (multiplicar por uma constante ímpar grande e olhar
    // os bits resultantes) — cada vaga cai numa das CV_FOLDS dobras de forma
    // determinística e sem estado nenhum pra guardar: o id de uma vaga
    // sempre cai na mesma dobra, hoje ou daqui a um ano, não importa quantas
    // outras vagas existam ou em que ordem vieram do banco.
    private static final long FOLD_HASH_MULT = 2654435761L;

    private int[][] foldIndicesEstavel(List<SalaryEstimateService.TrainingRow> rows, int k) {
        List<List<Integer>> baldes = new ArrayList<>();
        for (int f = 0; f < k; f++) baldes.add(new ArrayList<>());
        for (int i = 0; i < rows.size(); i++) {
            long jobId = rows.get(i).jobId() != null ? rows.get(i).jobId() : i;
            int fold = (int) Math.floorMod(jobId * FOLD_HASH_MULT, (long) k);
            baldes.get(fold).add(i);
        }
        int[][] out = new int[k][];
        for (int f = 0; f < k; f++) out[f] = toIntArray(baldes.get(f));
        return out;
    }

    // ===================== ridge regression =====================

    private record RidgeFit(double intercept, double[] weights) {}

    private RidgeFit fitRidge(double[][] X, double[] y, double alpha) {
        int n = X.length;
        int p = X[0].length;

        double[] xMean = new double[p];
        for (double[] row : X) for (int j = 0; j < p; j++) xMean[j] += row[j];
        for (int j = 0; j < p; j++) xMean[j] /= n;

        double yMean = 0;
        for (double v : y) yMean += v;
        yMean /= n;

        double[][] xc = new double[n][p];
        for (int i = 0; i < n; i++) for (int j = 0; j < p; j++) xc[i][j] = X[i][j] - xMean[j];
        double[] yc = new double[n];
        for (int i = 0; i < n; i++) yc[i] = y[i] - yMean;

        // (Xcᵀ Xc + alpha·I) w = Xcᵀ yc
        double[][] xtx = new double[p][p];
        for (int a = 0; a < p; a++) {
            for (int b = a; b < p; b++) {
                double sum = 0;
                for (int i = 0; i < n; i++) sum += xc[i][a] * xc[i][b];
                xtx[a][b] = sum;
                xtx[b][a] = sum;
            }
            xtx[a][a] += alpha;
        }
        double[] xty = new double[p];
        for (int a = 0; a < p; a++) {
            double sum = 0;
            for (int i = 0; i < n; i++) sum += xc[i][a] * yc[i];
            xty[a] = sum;
        }

        double[] w = choleskySolve(xtx, xty);

        double intercept = yMean;
        for (int j = 0; j < p; j++) intercept -= xMean[j] * w[j];

        return new RidgeFit(intercept, w);
    }

    private double[] predictAll(RidgeFit fit, double[][] X) {
        double[] out = new double[X.length];
        for (int i = 0; i < X.length; i++) {
            double pred = fit.intercept();
            double[] row = X[i];
            for (int j = 0; j < fit.weights().length; j++) pred += row[j] * fit.weights()[j];
            out[i] = pred;
        }
        return out;
    }

    // Decomposição de Cholesky (A = L·Lᵀ) pra resolver Ax=b — A é simétrica
    // positiva definida aqui porque alpha > 0 sempre soma um termo positivo
    // na diagonal de XᵀX (que é positiva semi-definida por construção).
    private double[] choleskySolve(double[][] a, double[] b) {
        int p = a.length;
        double[][] l = new double[p][p];
        for (int i = 0; i < p; i++) {
            for (int j = 0; j <= i; j++) {
                double sum = a[i][j];
                for (int k = 0; k < j; k++) sum -= l[i][k] * l[j][k];
                if (i == j) {
                    l[i][j] = Math.sqrt(Math.max(sum, 1e-12));
                } else {
                    l[i][j] = sum / l[j][j];
                }
            }
        }
        double[] y = new double[p];
        for (int i = 0; i < p; i++) {
            double sum = b[i];
            for (int k = 0; k < i; k++) sum -= l[i][k] * y[k];
            y[i] = sum / l[i][i];
        }
        double[] x = new double[p];
        for (int i = p - 1; i >= 0; i--) {
            double sum = y[i];
            for (int k = i + 1; k < p; k++) sum -= l[k][i] * x[k];
            x[i] = sum / l[i][i];
        }
        return x;
    }

    // ===================== utilitários numéricos =====================

    private int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private double[][] select(double[][] a, int[] idx) {
        double[][] out = new double[idx.length][];
        for (int i = 0; i < idx.length; i++) out[i] = a[idx[i]];
        return out;
    }

    private double[] select(double[] a, int[] idx) {
        double[] out = new double[idx.length];
        for (int i = 0; i < idx.length; i++) out[i] = a[idx[i]];
        return out;
    }

    private int[] complement(int[] subset, int n) {
        Set<Integer> excluded = new HashSet<>();
        for (int v : subset) excluded.add(v);
        int[] out = new int[n - subset.length];
        int k = 0;
        for (int i = 0; i < n; i++) if (!excluded.contains(i)) out[k++] = i;
        return out;
    }

    private double[] logspace(double startExp, double endExp, int count) {
        double[] out = new double[count];
        for (int i = 0; i < count; i++) {
            double t = startExp + (endExp - startExp) * i / (count - 1);
            out[i] = Math.pow(10, t);
        }
        return out;
    }

    private double r2Score(double[] actual, double[] predicted) {
        double mean = Arrays.stream(actual).average().orElse(0);
        double ssRes = 0, ssTot = 0;
        for (int i = 0; i < actual.length; i++) {
            ssRes += Math.pow(actual[i] - predicted[i], 2);
            ssTot += Math.pow(actual[i] - mean, 2);
        }
        if (ssTot == 0) return 0;
        return 1 - ssRes / ssTot;
    }

    private double mae(double[] actual, double[] predicted) {
        double sum = 0;
        for (int i = 0; i < actual.length; i++) sum += Math.abs(actual[i] - predicted[i]);
        return sum / actual.length;
    }

    private double maePercent(double[] actual, double[] predicted) {
        double sum = 0;
        for (int i = 0; i < actual.length; i++) sum += Math.abs(predicted[i] - actual[i]) / actual[i];
        return (sum / actual.length) * 100;
    }

    private double round(double v, int decimals) {
        double factor = Math.pow(10, decimals);
        return Math.round(v * factor) / factor;
    }
}

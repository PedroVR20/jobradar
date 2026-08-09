package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.*;

/**
 * Inferência do modelo de salário treinado offline (ver scripts/train_salary_model.py,
 * regressão Ridge em log(salário) sobre senioridade/modalidade/estado/tags).
 * O JSON exportado do treino (coeficientes + vocabulário de features) é
 * carregado uma vez na inicialização, de {@code resources/salary_model.json}
 * — nenhum runtime Python roda em produção, é só um produto escalar em Java.
 *
 * <p>Avaliado contra a abordagem anterior (mediana de vagas com senioridade +
 * 1 tag em comum): o modelo treinado cobre 100% dos casos (a mediana só
 * cobria 68% — "dados insuficientes" no resto) com erro médio de ~43%
 * (a mediana errava ~51% nos casos que cobria). Uma melhoria real, mas erro
 * de 43% ainda é grande — por isso a UI mostra essa margem explicitamente,
 * não esconde a incerteza.</p>
 */
@Service
@Slf4j
public class SalaryPredictionService {

    private boolean loaded = false;
    private double intercept;
    private double[] coefficients;
    private final Map<String, Integer> featureIndex = new HashMap<>();
    private List<String> seniorityVocab = List.of();
    private List<String> workplaceVocab = List.of();
    private List<String> stateVocab = List.of();
    private Set<String> tagVocab = Set.of();
    private ModelInfo modelInfo;

    public record ModelInfo(int nSamples, double r2, double maePercent) {}

    @PostConstruct
    void loadModel() {
        try (InputStream is = getClass().getResourceAsStream("/salary_model.json")) {
            if (is == null) {
                log.warn("=== salary_model.json não encontrado — estimativa por modelo treinado ficará indisponível (só a estimativa por vagas parecidas continua funcionando) ===");
                return;
            }
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(is);

            intercept = root.path("intercept").asDouble();
            List<String> featureNames = toStringList(root.path("featureNames"));
            for (int i = 0; i < featureNames.size(); i++) featureIndex.put(featureNames.get(i), i);

            JsonNode coefsNode = root.path("coefficients");
            coefficients = new double[coefsNode.size()];
            for (int i = 0; i < coefsNode.size(); i++) coefficients[i] = coefsNode.get(i).asDouble();

            seniorityVocab = toStringList(root.path("seniorityVocab"));
            workplaceVocab = toStringList(root.path("workplaceVocab"));
            stateVocab = toStringList(root.path("stateVocab"));
            tagVocab = new HashSet<>(toStringList(root.path("tagVocab")));

            JsonNode m = root.path("metrics");
            modelInfo = new ModelInfo(root.path("nSamples").asInt(), m.path("r2LogScale").asDouble(), m.path("maePercent").asDouble());

            loaded = true;
            log.info("=== Modelo de salário carregado: {} amostras de treino, R² {}, erro médio {}% ===",
                    modelInfo.nSamples(), modelInfo.r2(), modelInfo.maePercent());
        } catch (Exception e) {
            log.warn("Não foi possível carregar o modelo de salário treinado: {}", e.getMessage());
        }
    }

    private List<String> toStringList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        arr.forEach(n -> out.add(n.asText()));
        return out;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public ModelInfo getModelInfo() {
        return modelInfo;
    }

    /**
     * Prevê o salário mensal em reais pra uma combinação de senioridade,
     * tags, modalidade e estado — sempre devolve algo quando o modelo está
     * carregado (diferente da estimativa por vagas parecidas, que exige
     * amostra mínima). Categorias fora do vocabulário de treino caem na
     * categoria "desconhecido"/"outros" correspondente, igual foi feito no
     * treino em Python.
     */
    public Optional<Long> predict(String seniority, Collection<String> tags, String workplaceType, String state) {
        if (!loaded) return Optional.empty();

        double[] x = new double[coefficients.length];

        String sen = seniorityVocab.contains(seniority) ? seniority : "NAO_INFORMADO";
        setFeature(x, "seniority=" + sen);

        String wp = (workplaceType != null && workplaceVocab.contains(workplaceType)) ? workplaceType : "DESCONHECIDO";
        setFeature(x, "workplace=" + wp);

        String normState = state == null || state.isBlank() ? null : state.trim().toLowerCase();
        String stateBucket;
        if (normState == null) {
            stateBucket = "desconhecido";
        } else if (stateVocab.contains(normState)) {
            stateBucket = normState;
        } else {
            stateBucket = "outros";
        }
        setFeature(x, "state=" + stateBucket);

        if (tags != null) {
            for (String t : tags) {
                String norm = t == null ? "" : t.trim().toLowerCase();
                if (tagVocab.contains(norm)) setFeature(x, "tag=" + norm);
            }
        }

        double logPred = intercept;
        for (int i = 0; i < x.length; i++) logPred += x[i] * coefficients[i];
        double brl = Math.expm1(logPred);
        if (!Double.isFinite(brl) || brl <= 0) return Optional.empty();
        return Optional.of(Math.round(brl));
    }

    private void setFeature(double[] x, String featureName) {
        Integer idx = featureIndex.get(featureName);
        if (idx != null) x[idx] = 1.0;
        // categoria fora do vocabulário de treino: fica tudo zero nessa
        // dimensão, o intercepto + as demais features ainda dão uma previsão razoável
    }

    // Extrai tags técnicas conhecidas de um texto livre (currículo/perfil do
    // candidato) via correspondência simples de substring contra o
    // vocabulário do modelo — não usa IA, é só varredura de palavras-chave.
    public Set<String> extractTagsFromText(String text) {
        if (text == null || text.isBlank()) return Set.of();
        String lower = text.toLowerCase();
        Set<String> found = new HashSet<>();
        for (String tag : tagVocab) {
            if (lower.contains(tag)) found.add(tag);
        }
        return found;
    }
}

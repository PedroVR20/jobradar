package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    // Onde o modelo retreinado pelo endpoint /admin/retrain-salary-model é
    // persistido — fora do jar, num volume montado (ver docker-compose.yml),
    // pra sobreviver a "docker compose up" sem precisar reconstruir a imagem.
    // Caminho relativo funciona tanto no container (WORKDIR /app) quanto
    // rodando local (mvn spring-boot:run a partir de backend/).
    @Value("${salary.model.external-path:data/salary_model.json}")
    private String externalModelPath;

    private boolean loaded = false;
    private double intercept;
    private double[] coefficients;
    private final Map<String, Integer> featureIndex = new HashMap<>();
    private List<String> seniorityVocab = List.of();
    private List<String> workplaceVocab = List.of();
    private List<String> stateVocab = List.of();
    private Set<String> tagVocab = Set.of();
    private ModelInfo modelInfo;
    private final ObjectMapper mapper = new ObjectMapper();

    public record ModelInfo(int nSamples, double r2, double maeBrl, double maePercent) {}

    @PostConstruct
    void loadModel() {
        // O arquivo externo (resultado do último retreino, se já rodou
        // algum) tem prioridade sobre o modelo "de fábrica" empacotado no
        // jar — só cai pro empacotado se ainda não existe um retreino salvo.
        Path external = Path.of(externalModelPath);
        if (Files.isReadable(external)) {
            try (InputStream is = Files.newInputStream(external)) {
                applyModel(mapper.readTree(is));
                log.info("=== Modelo de salário carregado do retreino salvo em {}: {} amostras, R² {}, erro médio {}% ===",
                        external.toAbsolutePath(), modelInfo.nSamples(), modelInfo.r2(), modelInfo.maePercent());
                return;
            } catch (Exception e) {
                log.warn("Não consegui ler o modelo externo em {} ({}), caindo pro modelo empacotado no jar.",
                        external.toAbsolutePath(), e.getMessage());
            }
        }

        try (InputStream is = getClass().getResourceAsStream("/salary_model.json")) {
            if (is == null) {
                log.warn("=== salary_model.json não encontrado — estimativa por modelo treinado ficará indisponível (só a estimativa por vagas parecidas continua funcionando) ===");
                return;
            }
            applyModel(mapper.readTree(is));
            log.info("=== Modelo de salário carregado (empacotado): {} amostras de treino, R² {}, erro médio {}% ===",
                    modelInfo.nSamples(), modelInfo.r2(), modelInfo.maePercent());
        } catch (Exception e) {
            log.warn("Não foi possível carregar o modelo de salário treinado: {}", e.getMessage());
        }
    }

    /**
     * Troca o modelo em uso imediatamente (chamado pelo endpoint de
     * retreino, depois de {@link SalaryModelTrainerService#treinar()}) e
     * persiste no caminho externo, pra sobreviver ao próximo restart do
     * container sem precisar reconstruir a imagem Docker.
     */
    public synchronized void reload(JsonNode novoModelo) {
        applyModel(novoModelo);
        try {
            Path external = Path.of(externalModelPath);
            if (external.getParent() != null) Files.createDirectories(external.getParent());
            Files.writeString(external, novoModelo.toPrettyString());
            log.info("Modelo de salário retreinado salvo em {}", external.toAbsolutePath());
        } catch (IOException e) {
            log.warn("Modelo retreinado está em uso, mas não consegui persistir em {} — um restart vai voltar pro modelo anterior. Erro: {}",
                    externalModelPath, e.getMessage());
        }
    }

    private void applyModel(JsonNode root) {
        double newIntercept = root.path("intercept").asDouble();
        List<String> featureNames = toStringList(root.path("featureNames"));
        Map<String, Integer> newFeatureIndex = new HashMap<>();
        for (int i = 0; i < featureNames.size(); i++) newFeatureIndex.put(featureNames.get(i), i);

        JsonNode coefsNode = root.path("coefficients");
        double[] newCoefficients = new double[coefsNode.size()];
        for (int i = 0; i < coefsNode.size(); i++) newCoefficients[i] = coefsNode.get(i).asDouble();

        List<String> newSeniorityVocab = toStringList(root.path("seniorityVocab"));
        List<String> newWorkplaceVocab = toStringList(root.path("workplaceVocab"));
        List<String> newStateVocab = toStringList(root.path("stateVocab"));
        Set<String> newTagVocab = new HashSet<>(toStringList(root.path("tagVocab")));

        JsonNode m = root.path("metrics");
        ModelInfo newModelInfo = new ModelInfo(root.path("nSamples").asInt(), m.path("r2LogScale").asDouble(),
                m.path("maeBrl").asDouble(), m.path("maePercent").asDouble());

        // Só troca o estado depois de tudo parseado com sucesso — evita
        // deixar o serviço num estado parcialmente atualizado se o JSON
        // vier corrompido/incompleto.
        this.intercept = newIntercept;
        this.featureIndex.clear();
        this.featureIndex.putAll(newFeatureIndex);
        this.coefficients = newCoefficients;
        this.seniorityVocab = newSeniorityVocab;
        this.workplaceVocab = newWorkplaceVocab;
        this.stateVocab = newStateVocab;
        this.tagVocab = newTagVocab;
        this.modelInfo = newModelInfo;
        this.loaded = true;
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

package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase 3.1 — ranking pessoal aprendido do próprio histórico de engajamento,
 * SEM IA (nenhuma chamada ao Gemini, roda mesmo com a IA fora do ar — como
 * está agora, ver 403 do pool de keys). Mesma filosofia local/estatística
 * do {@link SalaryModelTrainerService}: um Naive Bayes ingênuo (Laplace
 * smoothing) sobre features categóricas já existentes na vaga.
 *
 * <p><b>Por que não usar {@code useAiFeedback} (👍/👎)?</b> Aquele feedback
 * é sobre a QUALIDADE do texto gerado pela IA (carta, perguntas de
 * entrevista) — não diz nada sobre que TIPO de vaga o usuário prefere. O
 * sinal real de preferência já existe no próprio {@link Job}:
 * {@code interested}/{@code applied}/{@code favorited} (positivo — o
 * usuário escolheu ativamente) e {@code rejected} sem nunca ter aplicado
 * (negativo — descartou por conta própria, diferente de recusa da EMPRESA
 * depois de aplicado, que não é preferência do usuário, é decisão de
 * terceiro).</p>
 *
 * <p>Treina de novo a cada chamada (varredura única sobre a lista já
 * carregada, não é caro) — chamado com pouca frequência (uma vez por
 * listagem ordenada por "pessoal"), sem precisar de infraestrutura de
 * cache/retreino separada como o modelo de salário.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PersonalRankingService {

    private final JobRepository jobRepository;

    // Abaixo disso não treina — poucochas amostras dão log-odds instáveis
    // (uma vaga isolada decidindo o peso de uma tag inteira). Preferível
    // devolver "sem dado suficiente" a fingir uma preferência aprendida.
    private static final int MINIMO_POSITIVAS = 5;
    private static final int MINIMO_NEGATIVAS = 5;

    public record Modelo(
            boolean disponivel,
            String motivoIndisponivel,
            int totalPositivas,
            int totalNegativas,
            Map<String, Double> pesos // "campo:valor" -> log-odds
    ) {}

    public Modelo treinar() {
        List<Job> todas = jobRepository.findAll();

        List<Job> positivas = new ArrayList<>();
        List<Job> negativas = new ArrayList<>();
        for (Job j : todas) {
            boolean gostou = j.isInterested() || j.isApplied() || Boolean.TRUE.equals(j.getFavorited());
            boolean descartouSemAplicar = j.isRejected() && !j.isApplied();
            if (gostou) positivas.add(j);
            else if (descartouSemAplicar) negativas.add(j);
        }

        if (positivas.size() < MINIMO_POSITIVAS || negativas.size() < MINIMO_NEGATIVAS) {
            return new Modelo(false, String.format(
                    "Ainda faltam sinais pra aprender sua preferência (tem %d interesse/aplicada/favoritada e %d recusada sem aplicar — precisa de pelo menos %d de cada).",
                    positivas.size(), negativas.size(), MINIMO_POSITIVAS),
                    positivas.size(), negativas.size(), Map.of());
        }

        Map<String, Integer> contPositivas = contarFeatures(positivas, false);
        // Fase 8.7 — recusa com motivo estruturado só conta features da
        // dimensão certa (ver featuresParaRecusa) em vez de penalizar a vaga
        // inteira. Sem motivo (recusa antiga, ou usuário pulou o passo),
        // comportamento é o de sempre — 100% retrocompatível.
        Map<String, Integer> contNegativas = contarFeatures(negativas, true);

        Map<String, Double> pesos = new HashMap<>();
        for (String feature : java.util.stream.Stream.concat(contPositivas.keySet().stream(), contNegativas.keySet().stream()).distinct().toList()) {
            int p = contPositivas.getOrDefault(feature, 0);
            int n = contNegativas.getOrDefault(feature, 0);
            // Laplace smoothing (+1 nos dois lados) — evita log(0) e evita que
            // uma feature vista uma única vez vire um peso absoluto extremo.
            double logOdds = Math.log((p + 1.0) / (positivas.size() + 2.0))
                    - Math.log((n + 1.0) / (negativas.size() + 2.0));
            pesos.put(feature, logOdds);
        }

        return new Modelo(true, null, positivas.size(), negativas.size(), pesos);
    }

    /** Score 0-100. Sem modelo treinado (dado insuficiente) devolve 50 (neutro) pra não afetar a ordenação. */
    public int pontuar(Job job, Modelo modelo) {
        if (!modelo.disponivel()) return 50;
        double soma = 0;
        for (String feature : featuresDe(job)) {
            soma += modelo.pesos().getOrDefault(feature, 0.0);
        }
        // sigmoide pra comprimir a soma de log-odds (sem limite teórico) num 0-100 estável
        double sigmoide = 1.0 / (1.0 + Math.exp(-soma));
        return (int) Math.round(sigmoide * 100);
    }

    private Map<String, Integer> contarFeatures(List<Job> jobs, boolean isNegativa) {
        Map<String, Integer> cont = new HashMap<>();
        for (Job j : jobs) {
            for (String f : isNegativa ? featuresParaRecusa(j) : featuresDe(j)) {
                cont.merge(f, 1, Integer::sum);
            }
        }
        return cont;
    }

    // Fase 8.7 — recusa com motivo estruturado (ver Job.rejectedReason)
    // filtra pra features da dimensão certa: recusar por SALARIO ou EMPRESA
    // não diz nada sobre a stack/senioridade/modalidade serem ruins, então
    // não deveriam puxar o log-odds delas pra baixo. Sem motivo informado —
    // recusa antiga (feature nova) ou usuário pulou o passo — mantém o
    // comportamento de sempre (penaliza a vaga inteira).
    private List<String> featuresParaRecusa(Job j) {
        List<String> todas = featuresDe(j);
        String motivo = j.getRejectedReason();
        if (motivo == null) return todas;
        return switch (motivo) {
            case "SALARIO", "EMPRESA" -> List.of();
            case "LOCALIDADE" -> todas.stream()
                    .filter(f -> f.startsWith("state:") || f.startsWith("workplaceType:")).toList();
            case "SENIORIDADE" -> todas.stream().filter(f -> f.startsWith("seniority:")).toList();
            case "STACK" -> todas.stream().filter(f -> f.startsWith("tag:")).toList();
            default -> todas; // OUTRO ou valor desconhecido
        };
    }

    private List<String> featuresDe(Job j) {
        List<String> features = new ArrayList<>();
        if (j.getSeniority() != null) features.add("seniority:" + j.getSeniority());
        if (j.getWorkplaceType() != null) features.add("workplaceType:" + j.getWorkplaceType());
        if (j.getSource() != null) features.add("source:" + j.getSource());
        if (j.getState() != null) features.add("state:" + j.getState());
        features.add("temSalario:" + (j.getSalary() != null));
        if (j.getTags() != null) {
            for (String tag : j.getTags().split(",")) {
                String t = tag.trim().toLowerCase();
                if (!t.isBlank()) features.add("tag:" + t);
            }
        }
        return features;
    }
}

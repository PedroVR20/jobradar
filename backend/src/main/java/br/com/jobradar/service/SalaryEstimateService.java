package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Estimativa de faixa salarial baseada em dados REAIS já cadastrados no
 * banco — nunca um chute da IA. Salário é decisão de negociação de verdade,
 * então preferimos "sem dados suficientes" a um número plausível-mas-errado
 * inventado por um modelo de linguagem.
 *
 * Considera vagas em reais (R$, a maioria — Gupy, QueroVagasTech) e também
 * em euros/dólares (Arbeitnow, Remotive, WWR), convertidas pra BRL via
 * {@link ExchangeRateService} — antes só R$ entrava, o que deixava de fora
 * boa parte das vagas internacionais com salário informado só por causa da
 * moeda, mesmo tendo dado real disponível.
 */
@Service
@RequiredArgsConstructor
public class SalaryEstimateService {

    private final JobRepository jobRepository;
    private final ExchangeRateService exchangeRateService;

    // Amostra mínima pra mostrar algo — abaixo disso a "estimativa" seria só
    // ruído (ex: 1 vaga isolada não representa "o mercado").
    private static final int MIN_SAMPLE_SIZE = 3;

    private static final Pattern BRL_NUMBER = Pattern.compile("R\\$\\s*([\\d.,]+)");
    private static final Pattern EUR_NUMBER = Pattern.compile("€\\s*([\\d.,]+)\\s*([kK])?");
    private static final Pattern USD_NUMBER = Pattern.compile("\\$\\s*([\\d.,]+)\\s*([kK])?");

    // Valores sem marcador de período explícito (nem "/year" nem "/month")
    // abaixo desse limiar são ambíguos demais (podia ser mensal alto OU
    // anual de vaga júnior) — melhor descartar do que arriscar um rótulo
    // errado no treino/estimativa.
    private static final double LIMIAR_AMBIGUO_ANUAL = 15000;
    private static final double HORAS_SEMANAIS_PADRAO = 40;
    private static final double SEMANAS_POR_MES = 52.0 / 12.0;

    public record SalaryEstimate(int sampleSize, long min, long max, long median, String formatted) {}

    public Optional<SalaryEstimate> estimate(Job job) {
        if (job.getSeniority() == null) return Optional.empty();
        Set<String> jobTags = tagSet(job.getTags());
        if (jobTags.isEmpty()) return Optional.empty();

        List<Long> valores = jobRepository.findAll().stream()
                .filter(j -> !Objects.equals(j.getId(), job.getId()))
                .filter(j -> job.getSeniority().equals(j.getSeniority()))
                .filter(j -> !Collections.disjoint(tagSet(j.getTags()), jobTags))
                .map(j -> parseMonthlySalaryBRL(j.getSalary()))
                .filter(Objects::nonNull)
                .sorted()
                .toList();

        if (valores.size() < MIN_SAMPLE_SIZE) return Optional.empty();

        long min = valores.get(0);
        long max = valores.get(valores.size() - 1);
        long median = median(valores);
        String formatted = String.format(
                "R$ %,d – R$ %,d (mediana R$ %,d, com base em %d vaga%s parecida%s)",
                min, max, median, valores.size(), valores.size() == 1 ? "" : "s", valores.size() == 1 ? "" : "s"
        );
        return Optional.of(new SalaryEstimate(valores.size(), min, max, median, formatted));
    }

    // Tags "ruído" que algumas fontes colocam junto das técnicas de verdade
    // (nome da própria fonte, modalidade, formato de trabalho) — sem
    // filtrar, uma vaga SENIOR de QA da Nerdin batia com QUALQUER outra vaga
    // SENIOR da Nerdin só por causa da tag "nerdin" em comum, gerando faixas
    // salariais sem nexo (visto em teste real: R$ 440k–1.650k/mês).
    private static final Set<String> NOISE_TAGS = Set.of(
            "nerdin", "querovagastech", "remote", "remoto", "hibrido", "híbrido", "presencial"
    );

    private Set<String> tagSet(String tags) {
        if (tags == null || tags.isBlank()) return Set.of();
        Set<String> out = new HashSet<>();
        for (String t : tags.toLowerCase().split(",")) {
            String trimmed = t.trim();
            if (!trimmed.isBlank() && !NOISE_TAGS.contains(trimmed)) out.add(trimmed);
        }
        return out;
    }

    // Ponto de entrada único: detecta a moeda pelo símbolo e devolve o valor
    // mensal já convertido pra reais. R$ é tratado literalmente (sem
    // conversão); € e $ passam pela cotação do dia.
    private Long parseMonthlySalaryBRL(String raw) {
        if (raw == null || raw.isBlank()) return null;
        if (raw.contains("R$")) {
            return parseMonthlyBRL(raw);
        }
        if (raw.contains("€")) {
            Double mensalEur = parseMonthlyForeign(raw, EUR_NUMBER);
            return mensalEur == null ? null : Math.round(mensalEur * exchangeRateService.getEurToBrl());
        }
        if (raw.contains("$")) {
            Double mensalUsd = parseMonthlyForeign(raw, USD_NUMBER);
            return mensalUsd == null ? null : Math.round(mensalUsd * exchangeRateService.getUsdToBrl());
        }
        return null;
    }

    // Extrai um valor mensal em reais de um texto livre tipo "R$ 5,500/mês"
    // (vírgula = milhar, formato QueroVagasTech), "R$ 4.400,00" (formato BR
    // tradicional: ponto = milhar, vírgula = decimal, formato Gupy) ou
    // "R$ 2,000 – 2,500/mês". Descarta qualquer coisa que pareça ser outro
    // período (hora/ano) — comparar salário/hora com mensal sem normalizar
    // geraria número sem sentido.
    private Long parseMonthlyBRL(String raw) {
        String lower = raw.toLowerCase();
        if (lower.contains("hora") || lower.contains("/h") || lower.contains("ano") || lower.contains("anual") || lower.contains("/year")) {
            return null;
        }

        Matcher m = BRL_NUMBER.matcher(raw);
        List<Long> nums = new ArrayList<>();
        while (m.find()) {
            Long val = parseAmount(m.group(1));
            if (val != null && val >= 100) nums.add(val); // descarta ruído tipo um "R$ 5" solto no texto
        }
        if (nums.isEmpty()) return null;
        // faixa ("2,000 – 2,500") vira a média dos dois extremos
        return Math.round(nums.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    // Extrai um valor MENSAL na moeda original (€ ou $) de textos tipo
    // "€85,000-120,000/year", "€45.000" (sem marcador — formato europeu
    // comum pra salário anual), "$18 - $22/hr", "$20k -$35k". Sem marcador
    // de período explícito, usa uma heurística de magnitude (ver
    // LIMIAR_AMBIGUO_ANUAL) — abaixo do limiar, descarta por ser ambíguo
    // demais (não dá pra saber se é mensal alto ou anual júnior).
    private Double parseMonthlyForeign(String raw, Pattern numberPattern) {
        String lower = raw.toLowerCase();
        boolean porHora = lower.contains("/hr") || lower.contains("/hour") || lower.contains(" hora");
        boolean explicitoMensal = lower.contains("/month") || lower.contains("/mo") || lower.contains("/mês") || lower.contains("mensal");
        boolean explicitoAnual = lower.contains("/year") || lower.contains("/yr") || lower.contains("/ano") || lower.contains("anual") || lower.contains("annual");

        Matcher m = numberPattern.matcher(raw);
        List<Double> nums = new ArrayList<>();
        while (m.find()) {
            Long base = parseAmount(m.group(1));
            if (base == null) continue;
            double valor = base;
            if (m.group(2) != null) valor *= 1000; // sufixo "k" ("$20k")
            if (valor >= 1) nums.add(valor);
        }
        if (nums.isEmpty()) return null;
        double media = nums.stream().mapToDouble(Double::doubleValue).average().orElse(0);

        if (porHora) {
            return media * HORAS_SEMANAIS_PADRAO * SEMANAS_POR_MES;
        }
        if (explicitoMensal) {
            return media;
        }
        if (explicitoAnual) {
            return media / 12.0;
        }
        // sem marcador: só arrisca se o valor for alto o bastante pra ser
        // inequivocamente anual (ninguém ganha €50.000/mês numa vaga comum)
        if (media >= LIMIAR_AMBIGUO_ANUAL) {
            return media / 12.0;
        }
        return null;
    }

    // Distingue separador decimal de separador de milhar sem assumir um
    // formato fixo: se o ÚLTIMO separador (vírgula ou ponto) for seguido de
    // exatamente 2 dígitos, é decimal (centavos, descartados — não importam
    // pra comparar faixa salarial); qualquer outro separador antes dele é
    // milhar. Sem isso, "4.400,00" virava 440000 em vez de 4400.
    private Long parseAmount(String numStr) {
        int lastComma = numStr.lastIndexOf(',');
        int lastDot = numStr.lastIndexOf('.');
        int lastSep = Math.max(lastComma, lastDot);

        String digitsOnly;
        if (lastSep == -1) {
            digitsOnly = numStr;
        } else {
            String afterSep = numStr.substring(lastSep + 1);
            boolean isDecimal = afterSep.length() == 2;
            digitsOnly = isDecimal
                    ? numStr.substring(0, lastSep).replaceAll("[.,]", "")
                    : numStr.replaceAll("[.,]", "");
        }
        try {
            return digitsOnly.isBlank() ? null : Long.parseLong(digitsOnly);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private long median(List<Long> sorted) {
        int n = sorted.size();
        if (n % 2 == 1) return sorted.get(n / 2);
        return Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
    }

    // Uma linha limpa (já com salário parseado — e convertido pra BRL quando
    // veio em €/$ — e tags filtradas de ruído) pra treinar um modelo real
    // fora do backend — ver /admin/salary-training-data. jobId vai junto pra
    // dar pra montar um split treino/teste ESTÁVEL entre retreinos (ver
    // SalaryModelTrainerService) — sem isso, comparar métricas de "antes" e
    // "depois" de um retreino não é justo (conjuntos de teste diferentes).
    public record TrainingRow(Long jobId, String seniority, List<String> tags, String workplaceType, String state, long salaryMonthly) {}

    public List<TrainingRow> exportTrainingData() {
        return jobRepository.findAll().stream()
                .map(j -> {
                    if (j.getSeniority() == null) return null;
                    Long salary = parseMonthlySalaryBRL(j.getSalary());
                    if (salary == null) return null;
                    List<String> tags = new ArrayList<>(tagSet(j.getTags()));
                    if (tags.isEmpty()) return null;
                    return new TrainingRow(j.getId(), j.getSeniority(), tags, j.getWorkplaceType(), j.getState(), salary);
                })
                .filter(Objects::nonNull)
                .toList();
    }
}

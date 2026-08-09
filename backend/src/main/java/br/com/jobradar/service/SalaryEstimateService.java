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
 * Só considera vagas com salário em reais (R$) e mensal — misturar com
 * salários em outras moedas sem conversão de câmbio real distorceria a
 * comparação, e a maioria das vagas com salário informado no banco (Gupy,
 * QueroVagasTech) já é BRL mensal mesmo.
 */
@Service
@RequiredArgsConstructor
public class SalaryEstimateService {

    private final JobRepository jobRepository;

    // Amostra mínima pra mostrar algo — abaixo disso a "estimativa" seria só
    // ruído (ex: 1 vaga isolada não representa "o mercado").
    private static final int MIN_SAMPLE_SIZE = 3;

    private static final Pattern BRL_NUMBER = Pattern.compile("R\\$\\s*([\\d.,]+)");

    public record SalaryEstimate(int sampleSize, long min, long max, long median, String formatted) {}

    public Optional<SalaryEstimate> estimate(Job job) {
        if (job.getSeniority() == null) return Optional.empty();
        Set<String> jobTags = tagSet(job.getTags());
        if (jobTags.isEmpty()) return Optional.empty();

        List<Long> valores = jobRepository.findAll().stream()
                .filter(j -> !Objects.equals(j.getId(), job.getId()))
                .filter(j -> job.getSeniority().equals(j.getSeniority()))
                .filter(j -> !Collections.disjoint(tagSet(j.getTags()), jobTags))
                .map(j -> parseMonthlyBRL(j.getSalary()))
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

    // Extrai um valor mensal em reais de um texto livre tipo "R$ 5,500/mês"
    // (vírgula = milhar, formato QueroVagasTech), "R$ 4.400,00" (formato BR
    // tradicional: ponto = milhar, vírgula = decimal, formato Gupy) ou
    // "R$ 2,000 – 2,500/mês". Descarta qualquer coisa que pareça ser outro
    // período (hora/ano) — comparar salário/hora com mensal sem normalizar
    // geraria número sem sentido.
    private Long parseMonthlyBRL(String raw) {
        if (raw == null || !raw.contains("R$")) return null;
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
}

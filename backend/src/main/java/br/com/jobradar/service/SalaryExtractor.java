package br.com.jobradar.service;

import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Tenta extrair uma menção de salário de um texto livre (descrição da vaga).
 * Fontes como Arbeitnow e Gupy não têm campo estruturado de salário — quando
 * o valor é divulgado, ele aparece embutido no texto da descrição.
 *
 * Não basta procurar "R$" ou "€" soltos no texto: descrições mencionam
 * faturamento da empresa, valores de benefícios, preços de produto etc.
 * Por isso a busca é ancorada em palavras-chave de remuneração (salário,
 * Gehalt, compensation...) e só olha por um valor monetário perto delas.
 */
@Component
public class SalaryExtractor {

    private static final Pattern KEYWORD_PATTERN = Pattern.compile(
            "(?i)sal[aá]rio|remunera[cç][aã]o|faixa salarial|pretens[aã]o salarial|" +
            "bolsa[- ]aux[ií]lio|aux[ií]lio financeiro|" +
            "salary|compensation|pay range|base pay|OTE|" +
            "Gehalt|Verg[uü]tung|Lohn"
    );

    // Cobre R$, €, $ / USD / EUR, com milhar em . ou , opcional "k" e
    // opcionalmente uma faixa ("-", "–", "a", "até", "bis", "to").
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:R\\$|US\\$|EUR|USD|€|\\$)\\s?\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?\\s?[kK]?" +
            "(?:\\s?(?:-|–|a|até|bis|to)\\s?(?:R\\$|US\\$|EUR|USD|€|\\$)?\\s?\\d{1,3}(?:[.,]\\d{3})*(?:[.,]\\d{1,2})?\\s?[kK]?)?" +
            "(?:\\s?/?\\s?(?:m[êe]s|mensal|mensais|ano|anual|hora|month|monatlich|Jahr|year|yr))?"
    );

    private static final Pattern TAG_PATTERN = Pattern.compile("<[^>]+>");

    // janela de texto ao redor da palavra-chave onde o valor é procurado
    private static final int WINDOW_BEFORE = 15;
    private static final int WINDOW_AFTER = 100;

    /**
     * @param html descrição da vaga, pode conter tags HTML e entidades (&nbsp; etc)
     * @return trecho com a menção de salário encontrada, ou null se não achar
     */
    public String extract(String html) {
        if (html == null || html.isBlank()) return null;

        String text = TAG_PATTERN.matcher(html).replaceAll(" ")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replaceAll("\\s+", " ");

        Matcher keywordMatcher = KEYWORD_PATTERN.matcher(text);
        while (keywordMatcher.find()) {
            int from = Math.max(0, keywordMatcher.start() - WINDOW_BEFORE);
            int to = Math.min(text.length(), keywordMatcher.end() + WINDOW_AFTER);
            String window = text.substring(from, to);

            Matcher amountMatcher = AMOUNT_PATTERN.matcher(window);
            if (amountMatcher.find()) {
                String found = amountMatcher.group().trim();
                if (isPlausible(found)) {
                    return found.length() > 60 ? found.substring(0, 60).trim() : found;
                }
            }
        }
        return null;
    }

    // descarta valores implausivelmente baixos pra serem um salário mensal/anual
    // (ex: "R$ 29,90" é quase sempre um benefício ou preço, não remuneração)
    private boolean isPlausible(String amount) {
        String digitsOnly = amount.replaceAll("[^0-9]", "");
        if (digitsOnly.length() < 3) return false;
        boolean hasK = amount.toLowerCase().contains("k");
        // "R$ 29,90" -> dígitos "2990", mas o valor inteiro antes da vírgula é só 29
        String integerPart = amount.replaceAll("(?i)[^0-9,.kK-]", "")
                .split("[,.](?=\\d{1,2}\\D*$)")[0]
                .replaceAll("[^0-9]", "");
        double value = integerPart.isEmpty() ? 0 : Double.parseDouble(integerPart);
        if (hasK) value *= 1000;
        return value >= 100;
    }
}

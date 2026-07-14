package br.com.jobradar.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Classifica a senioridade de uma vaga a partir do título e das tags.
 * Cobre termos em inglês, português, alemão e holandês (fontes europeias).
 *
 * Ordem de verificação: ESTAGIO → SENIOR → JUNIOR → PLENO.
 * SENIOR vem antes de JUNIOR para que "Senior Associate" não caia em JUNIOR.
 */
@Component
public class SeniorityClassifier {

    public static final String ESTAGIO = "ESTAGIO";
    public static final String JUNIOR = "JUNIOR";
    public static final String PLENO = "PLENO";
    public static final String SENIOR = "SENIOR";
    public static final String NAO_INFORMADO = "NAO_INFORMADO";

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    private static final Pattern ESTAGIO_PATTERN = Pattern.compile(
            "\\b(intern|internship|est[aá]gio|estagi[aá]ri[oa]|trainee|working\\s+student|werkstudent|praktikum|praktikant|stagiaire|co-?op)\\b",
            FLAGS);

    private static final Pattern SENIOR_PATTERN = Pattern.compile(
            "\\b(senior|s[eê]nior|sr\\.?|staff|principal|lead|head\\s+of|architect)\\b",
            FLAGS);

    private static final Pattern JUNIOR_PATTERN = Pattern.compile(
            "\\b(junior|j[uú]nior|jr\\.?|entry[-\\s]?level|graduate|early\\s+career|associate)\\b",
            FLAGS);

    private static final Pattern PLENO_PATTERN = Pattern.compile(
            "\\b(pleno|mid[-\\s]?level|intermediate|medior|middle|mid[-\\s]?weight)\\b",
            FLAGS);

    /**
     * @param title título da vaga (obrigatório)
     * @param tags  tags separadas por vírgula (pode ser null)
     * @return uma das constantes ESTAGIO, JUNIOR, PLENO, SENIOR ou NAO_INFORMADO
     */
    public String classify(String title, String tags) {
        String text = (title == null ? "" : title) + " " + (tags == null ? "" : tags.replace(",", " "));

        if (ESTAGIO_PATTERN.matcher(text).find()) return ESTAGIO;
        if (SENIOR_PATTERN.matcher(text).find())  return SENIOR;
        if (JUNIOR_PATTERN.matcher(text).find())  return JUNIOR;
        if (PLENO_PATTERN.matcher(text).find())   return PLENO;
        return NAO_INFORMADO;
    }
}

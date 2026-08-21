package br.com.jobradar.service;

import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Fase 7.1 — classifica se uma vaga é de tecnologia/TI a partir do título e
 * tags. Medido antes de escrever: 2.029 vagas no banco não batem com
 * nenhum termo de tecnologia (551 da QueroVagasTech, 472 da Arbeitnow, 324
 * da Greenhouse — fontes que não filtram por área na origem, diferente da
 * Gupy que já busca por termo). Exemplos reais encontrados: contabilidade,
 * engenharia civil, consultoria SAP em alemão.
 *
 * <p>Por inclusão, não exclusão: uma vaga só é RELEVANTE se o título+tags
 * bater com pelo menos um termo de tecnologia conhecido — a lista é ampla
 * de propósito (cobre função, tecnologia, e adjacências como produto/dados/
 * QA) pra minimizar falso negativo (vaga de tech marcada como fora de área
 * por acaso). Mesmo estilo do {@link SeniorityClassifier}: regex
 * determinístico, sem IA — precisa rodar em milhares de vagas no fetch
 * periódico sem gastar cota nem tempo de rede.</p>
 */
@Component
public class RelevanceClassifier {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;

    // Termos de função/cargo (não dependem de tecnologia específica).
    private static final Pattern FUNCAO_PATTERN = Pattern.compile(
            "\\b(desenvolvedor(a)?|programador(a)?|developer|engineer|engenheiro(a)?\\s+de\\s+software|" +
                    "software\\s+engineer|analista\\s+de\\s+sistemas|analista\\s+de\\s+ti|suporte\\s+ti|" +
                    "tech\\s+lead|scrum\\s+master|product\\s+owner|product\\s+manager|" +
                    "arquitet[oa]\\s+de\\s+software|solutions?\\s+architect|" +
                    "cientista\\s+de\\s+dados|data\\s+scientist|data\\s+engineer|analista\\s+de\\s+dados|" +
                    "engenheiro(a)?\\s+de\\s+dados|dba\\b|administrador(a)?\\s+de\\s+banco\\s+de\\s+dados|" +
                    "ux\\s*/?\\s*ui|ui\\s*/?\\s*ux|designer\\s+de\\s+produto|product\\s+designer|" +
                    "qa\\s+(engineer|analyst|tester)?|analista\\s+de\\s+qualidade|" +
                    "sre\\b|site\\s+reliability|" +
                    "estagi[aá]ri[oa]\\s+de\\s+(ti|tecnologia|dados|software|sistemas)|" +
                    "trainee\\s+(de\\s+)?(ti|tecnologia)|" +
                    "network\\s+engineer|security\\s+engineer|analista\\s+de\\s+seguran[cç]a|" +
                    "cloud\\s+engineer|infra(estrutura)?\\s+de\\s+ti|it\\s+support)\\b",
            FLAGS);

    // Tecnologias/stacks/plataformas específicas.
    private static final Pattern TECH_PATTERN = Pattern.compile(
            "\\b(java|python|javascript|typescript|golang|\\bgo\\b|rust|c\\+\\+|c#|\\.net|php|ruby|kotlin|swift|" +
                    "react|angular|vue|node\\.?js|django|flask|spring(\\s*boot)?|laravel|" +
                    "frontend|front-end|backend|back-end|fullstack|full-stack|" +
                    "devops|sysadmin|kubernetes|docker|terraform|ansible|aws|azure|gcp|" +
                    "sql\\b|nosql|postgres(ql)?|mysql|mongodb|redis|" +
                    "machine\\s+learning|deep\\s+learning|inteligência\\s+artificial|artificial\\s+intelligence|\\bml\\b|\\bai\\b|" +
                    "big\\s*data|etl\\b|data\\s+warehouse|power\\s*bi|tableau|" +
                    "mobile\\b|android|\\bios\\b|flutter|react\\s+native|" +
                    "cybersecurity|seguran[çc]a\\s+da\\s+informa[çc][ãa]o|pentest|" +
                    "salesforce|sap\\b|erp\\b|crm\\b|" +
                    "blockchain|web3|api\\b|microservices?|microsservi[çc]os?)\\b",
            FLAGS);

    public boolean isRelevante(String title, String tags) {
        String text = (title == null ? "" : title) + " " + (tags == null ? "" : tags.replace(",", " "));
        return FUNCAO_PATTERN.matcher(text).find() || TECH_PATTERN.matcher(text).find();
    }
}

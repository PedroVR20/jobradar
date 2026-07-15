package br.com.jobradar.service;

import java.text.Normalizer;
import java.util.List;

/**
 * Filtra vagas não relacionadas a tecnologia.
 *
 * Gupy já usa termos de busca específicos de TI, mas alguns resultados
 * tangenciais escapam. A Eureca publica qualquer tipo de estágio/trainee —
 * operações, administrativo, comercial etc. — e sem esse filtro tudo entraria
 * no banco. O critério é simples: se o título da vaga não tiver nenhuma
 * palavra-chave de tecnologia, ela é descartada.
 */
public class TechJobFilter {

    // Keywords com espaço inicial são checadas com word-boundary:
    // " python" casa "Estágio Python" mas não "Micropython".
    // Keywords sem espaço inicial são suficientemente específicas.
    private static final List<String> TECH_KEYWORDS = List.of(
        // desenvolvimento / engenharia
        "desenvolvedor", "programador", "developer",
        "engenheiro de software", "software engineer",
        "frontend", "backend", "back-end", "front-end",
        "fullstack", "full-stack", "full stack",
        " mobile", "flutter", "react native", "ios developer", "android",
        // dados / IA / ML
        "analista de dados", "cientista de dados",
        "data analyst", "data scientist",
        "engenheiro de dados", "data engineer",
        "machine learning", "inteligencia artificial",
        "ciencia de dados", "business intelligence",
        "analytics", "analise de dados", " dados",
        // infra / DevOps / cloud / redes
        "devops", "sre", "cloud engineer", "cloud computing",
        "infraestrutura de ti", "administrador de redes",
        "redes e sistemas", "infraestrutura e redes",
        // QA / testes
        "quality assurance", "tester", "testador",
        "automacao de testes", "analista de qualidade de software",
        " qa",
        // suporte / sistemas
        "suporte ti", "suporte tecnico em ti", "analista de sistemas",
        "analista de ti", "tecnico em ti", "tecnico de ti",
        "administrador de sistemas",
        // liderança / produto técnico
        "arquiteto de software", "tech lead", "lider tecnico",
        "scrum master", "product owner",
        "product manager", "gerente de produto",
        // design / UX (tech-adjacent)
        "ux designer", "ui designer", "product designer", "ux/ui",
        "design de produto",
        // segurança da informação
        "ciberseguranca", "seguranca da informacao", "cybersecurity",
        "analista de seguranca",
        // banco de dados
        " dba", "banco de dados", "administrador de banco",
        // área de TI em geral
        "tecnologia da informacao", "sistemas de informacao",
        "estagio ti", "trainee tecnologia", "trainee ti",
        "estagio tecnologia", "estagio em tecnologia",
        "transformacao digital",
        // linguagens no título  ("Estágio Python", "Trainee Java" etc.)
        " python", " java", " javascript", " typescript",
        " kotlin", " swift", " golang", " rust",
        " php", ".net", "c#", " sql"
    );

    /**
     * Retorna true se o título indicar uma vaga de tecnologia/TI.
     * Usa normalização NFD para ignorar acentos.
     */
    public static boolean isTechJob(String title) {
        if (title == null || title.isBlank()) return false;
        // prefixo de espaço habilita word-boundary para keywords com espaço inicial
        String normalized = " " + normalize(title);
        return TECH_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private static String normalize(String text) {
        return Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "");
    }
}

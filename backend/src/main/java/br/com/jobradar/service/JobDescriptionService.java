package br.com.jobradar.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Service;

/**
 * Busca o texto real da descrição de uma vaga na própria página dela (best
 * effort) — usado por CoverLetterService, MatchScoreService e
 * InterviewQuestionsService pra dar contexto de verdade à IA, em vez de só
 * título/tags/senioridade (o Job Radar não guarda a descrição completa no
 * banco). Nunca lança exceção: sites que renderizam via JS (SPA) ou
 * bloqueiam scraping simplesmente devolvem null, e quem chama cai de volta
 * pros campos que já tem — igual já acontecia antes dessa melhoria existir.
 */
@Service
@Slf4j
public class JobDescriptionService {

    private static final int TIMEOUT_MS = 8000;
    private static final int MAX_CHARS = 6000; // limite pra não estourar o prompt da IA

    // Seletores comuns de "corpo da vaga" tentados antes de cair pro body
    // inteiro — cobre bastante coisa sem precisar de config por fonte.
    private static final String[] CONTENT_SELECTORS = {
            "main", "article", "[role=main]",
            ".job-description", "#job-description", ".description",
            ".vaga-descricao", ".job-detail", ".job-content"
    };

    public String fetchDescription(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            Document doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (compatible; JobRadarBot/1.0; +https://github.com/PedroVR20/jobradar)")
                    .timeout(TIMEOUT_MS)
                    .get();

            doc.select("script, style, nav, footer, header, noscript, svg, form, iframe").remove();

            String text = mainContentText(doc);
            if (text == null || text.isBlank()) return null;

            text = text.replaceAll("[ \\t]+", " ").replaceAll("\\n{3,}", "\n\n").trim();
            if (text.length() < 100) return null; // provavelmente uma SPA vazia, não vale a pena
            if (text.length() > MAX_CHARS) text = text.substring(0, MAX_CHARS) + "...";
            return text;
        } catch (Exception e) {
            log.warn("Não foi possível buscar descrição de {}: {}", url, e.getMessage());
            return null;
        }
    }

    private String mainContentText(Document doc) {
        for (String sel : CONTENT_SELECTORS) {
            Element el = doc.selectFirst(sel);
            if (el != null && el.text().length() > 200) return el.text();
        }
        return doc.body() != null ? doc.body().text() : null;
    }
}

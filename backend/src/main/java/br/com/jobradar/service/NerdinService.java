package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Busca vagas de TI do Nerdin (nerdin.com.br/vagas.php) via scraping da
 * listagem paginada — o site não tem API pública. As classes CSS dos cards
 * (.vaga-card, .vaga-titulo, .vaga-empresa-nome etc.) são as mesmas que o
 * próprio JS do site usa pra favoritar/interagir, então tendem a ser
 * relativamente estáveis, mas ainda é HTML — pode quebrar se o Nerdin
 * redesenhar a página (por isso fica isolado aqui, sem afetar as outras
 * fontes se parar de funcionar). robots.txt permite ("Allow: /", sem
 * disallow pra /vagas.php nem /vaga_emprego/).
 *
 * Cada vaga individual tem um JSON-LD (schema.org JobPosting) bem completo
 * na página de detalhe — inclusive prazo de inscrição (validThrough) — mas
 * isso exigiria uma requisição HTTP extra por vaga (~700 no catálogo
 * inteiro), então por ora só a listagem é usada: dá título, empresa,
 * salário, modalidade, data de publicação e tags sem esse custo.
 */
@Service
@Slf4j
public class NerdinService implements JobSource {

    @Override
    public String nome() {
        return "Nerdin";
    }

    private static final String BASE_URL = "https://www.nerdin.com.br";
    private static final String LIST_URL = BASE_URL + "/vagas.php?pagina=";
    private static final int MAX_PAGES = 45; // catálogo tinha ~700 vagas (35 páginas) — folga pro site crescer
    private static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");

    // Fase 2.8 — quantas vezes tenta de novo uma página que falhou por erro
    // transitório (timeout de rede, 503 momentâneo) antes de desistir dela e
    // seguir pras próximas. Scraping de site de terceiro tem mais chance de
    // hiccup de rede do que uma API — sem retry, uma falha pontual numa
    // página no meio da paginação cortava o resto do catálogo fora à toa.
    private static final int MAX_TENTATIVAS_POR_PAGINA = 3;

    @Override
    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        for (int page = 1; page <= MAX_PAGES; page++) {
            Document doc = buscarPaginaComRetry(page);
            if (doc == null) {
                // Página falhou em todas as tentativas — não é fim de
                // paginação (isso é cards.isEmpty(), não erro de rede), então
                // não faz sentido parar o catálogo inteiro por causa de uma
                // página específica. Segue tentando as próximas.
                continue;
            }

            Elements cards = doc.select(".vaga-card");
            if (cards.isEmpty()) {
                // BUG REAL corrigido (Fase 2.8): 0 cards na página 1 quase
                // certamente significa que o Nerdin mudou o HTML e o
                // seletor ".vaga-card" não bate mais — bem diferente de 0
                // cards na página 30+ (fim normal da paginação). Antes os
                // dois casos eram idênticos (break silencioso), então uma
                // quebra de seletor virava "Nerdin: 0 vagas buscadas" sem
                // nenhum sinal de que era um erro e não um dia sem vaga
                // nova. Loga como ERROR só no caso suspeito, pra aparecer
                // destacado nos logs em vez de se perder como INFO normal.
                if (page == 1) {
                    log.error("=== Nerdin: 0 vagas na página 1 — possível quebra do seletor CSS " +
                            "'.vaga-card' (o site pode ter mudado o layout da listagem) ===");
                }
                break; // fim da paginação (ou quebra de seletor — já logado acima)
            }

            for (Element card : cards) {
                try {
                    Job job = parseCard(card);
                    if (job != null) jobs.add(job);
                } catch (Exception e) {
                    log.warn("Erro ao parsear vaga do Nerdin: {}", e.getMessage());
                }
            }
        }
        log.info("Nerdin: {} vagas buscadas", jobs.size());
        return jobs;
    }

    /** Busca uma página da listagem, tentando de novo em caso de erro transitório. Devolve null se todas as tentativas falharem. */
    private Document buscarPaginaComRetry(int page) {
        for (int tentativa = 1; tentativa <= MAX_TENTATIVAS_POR_PAGINA; tentativa++) {
            try {
                return Jsoup.connect(LIST_URL + page)
                        .userAgent("Mozilla/5.0 (compatible; JobRadarBot/1.0; uso pessoal)")
                        .timeout(15000)
                        .get();
            } catch (Exception e) {
                boolean ultimaTentativa = tentativa == MAX_TENTATIVAS_POR_PAGINA;
                if (ultimaTentativa) {
                    log.warn("Nerdin: página {} falhou após {} tentativas: {}", page, MAX_TENTATIVAS_POR_PAGINA, e.getMessage());
                } else {
                    try {
                        Thread.sleep(1000L * tentativa); // backoff simples: 1s, depois 2s
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
            }
        }
        return null;
    }

    private Job parseCard(Element card) {
        String href = card.attr("data-href");
        if (href == null || href.isBlank()) return null;
        String url = href.startsWith("http") ? href : BASE_URL + "/" + href;

        String title = extractTitle(card);
        if (title == null || title.isBlank()) return null;

        // O Nerdin não parece filtrar a listagem só pra vagas ativas — algumas
        // datadas de anos atrás (2019, 2023) aparecem misturadas com as de
        // hoje, provavelmente reaproveitadas/reativadas pela empresa. Sem uma
        // forma confiável de saber se ainda estão abertas, ignora qualquer
        // vaga "publicada" há mais de 90 dias em vez de arriscar mostrar
        // vaga morta como se fosse oportunidade nova.
        LocalDateTime postedAt = parsePostedAt(card);
        if (postedAt != null && postedAt.isBefore(LocalDateTime.now().minusDays(90))) {
            return null;
        }

        String company = textOrNull(card, ".vaga-empresa-nome");
        String location = textOrNull(card, ".vaga-local-linha");
        String salary = textOrNull(card, ".vaga-salario-destaque");
        String workplaceType = translateWorkplace(location);

        // BUG REAL corrigido (Fase 1.2): antes o texto de local só era salvo
        // (como cidade crua, sem separar estado) quando workplaceType vinha
        // null — ou seja, TODA vaga marcada REMOTO/HIBRIDO/PRESENCIAL
        // perdia a localização, mesmo tendo ela disponível (~380 das 841
        // vagas do Nerdin no banco). O local vem no formato "Cidade • UF"
        // (ex: "Rio de Janeiro • RJ") — agora sempre tenta separar os dois,
        // independente da modalidade detectada.
        String cidade = null;
        String estado = null;
        if (location != null) {
            String[] partes = location.split("•");
            if (partes.length == 2) {
                cidade = partes[0].trim();
                String nomeEstado = EstadosBrasileiros.nomeCompleto(partes[1].trim());
                estado = nomeEstado != null ? nomeEstado : null;
            } else {
                // Não bate o formato "Cidade • UF" (ex: só "Home Office") —
                // guarda o texto cru como cidade em vez de perder de vez.
                cidade = location;
            }
        }

        StringJoiner tagsJoiner = new StringJoiner(",");
        tagsJoiner.add("nerdin");
        for (Element tag : card.select(".vaga-hashtags a")) {
            String t = tag.text().replace("#", "").trim();
            if (!t.isBlank()) tagsJoiner.add(t.toLowerCase());
        }

        return Job.builder()
                .title(title)
                .company(company != null ? company : "Empresa não informada")
                .url(url)
                .source("NERDIN")
                .workplaceType(workplaceType)
                .city(cidade)
                .state(estado)
                .tags(tagsJoiner.toString())
                .salary(salary)
                .postedAt(postedAt != null ? postedAt : LocalDateTime.now())
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    // Remove o badge "Nova"/ícone de urgente que fica dentro do próprio <h3>,
    // sem correr o risco de um replace ingênuo cortar um título que contenha
    // literalmente a palavra "Nova" (ex: "Desenvolvedor .NET" não, mas
    // "Analista de Nova Geração" cortaria errado com string replace).
    private String extractTitle(Element card) {
        Element titleEl = card.selectFirst(".vaga-titulo");
        if (titleEl == null) return null;
        Element clone = titleEl.clone();
        clone.select(".vaga-nova-badge").remove();
        return clone.text().trim().replaceAll("\\s+", " ");
    }

    private String textOrNull(Element card, String selector) {
        Element el = card.selectFirst(selector);
        if (el == null) return null;
        String text = el.text().trim().replaceAll("\\s+", " ");
        return text.isBlank() ? null : text;
    }

    private String translateWorkplace(String location) {
        if (location == null) return null;
        String norm = location.toLowerCase();
        if (norm.contains("home office") || norm.contains("remoto")) return "REMOTO";
        if (norm.contains("híbrido") || norm.contains("hibrido")) return "HIBRIDO";
        if (norm.contains("presencial")) return "PRESENCIAL";
        return null;
    }

    private LocalDateTime parsePostedAt(Element card) {
        Element timeEl = card.selectFirst(".vaga-meta-extra time[datetime]");
        if (timeEl == null) return null;
        try {
            OffsetDateTime odt = OffsetDateTime.parse(timeEl.attr("datetime"));
            return odt.atZoneSameInstant(BRASILIA).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}

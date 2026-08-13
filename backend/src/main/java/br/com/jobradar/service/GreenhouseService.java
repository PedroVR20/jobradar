package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Busca vagas de tecnologia via a Greenhouse Job Board API
 * (boards-api.greenhouse.io) — pública, sem autenticação, usada por milhares
 * de empresas pra expor a própria listagem de vagas embutida no site. Cada
 * empresa tem um "board token" fixo (ex: {@code stone} pra
 * boards.greenhouse.io/stone) — não existe busca/descoberta global, então a
 * lista abaixo é curada manualmente, com cada token confirmado ao vivo
 * (curl) antes de entrar aqui (Fase 2.1).
 *
 * A API devolve vagas de todos os escritórios da empresa, não só Brasil —
 * cada empresa da lista tem presença real no Brasil (confirmado na inspeção
 * ao vivo), mas escritórios internacionais aparecem misturados no mesmo
 * board. Como o endpoint não tem filtro por país, a filtragem é feita aqui
 * excluindo localizações que batem com países/cidades claramente
 * estrangeiros — mais seguro que tentar validar positivamente "é Brasil",
 * já que o formato do campo location varia muito entre empresas (algumas
 * escrevem só a cidade, sem estado nem país).
 */
@Service
@Slf4j
public class GreenhouseService implements JobSource {

    @Override
    public String nome() {
        return "Greenhouse";
    }

    private static final String JOBS_URL = "https://boards-api.greenhouse.io/v1/boards/{token}/jobs";

    // token do board -> nome amigável da empresa. A API não devolve o nome da
    // empresa no endpoint de listagem, só no token da URL.
    private static final Map<String, String> BOARDS = new LinkedHashMap<>();
    static {
        BOARDS.put("quintoandar", "QuintoAndar");
        BOARDS.put("wildlifestudios", "Wildlife Studios");
        BOARDS.put("ebanx", "EBANX");
        BOARDS.put("gympass", "Gympass (Wellhub)");
        BOARDS.put("vtex", "VTEX");
        BOARDS.put("stone", "Stone");
        BOARDS.put("rdstation", "RD Station");
    }

    // Localizações claramente fora do Brasil, pra excluir do board de
    // empresas que têm escritórios em vários países. Feito por exclusão (não
    // por confirmação positiva de "Brasil") porque o formato do campo varia
    // demais entre empresas — algumas vagas em Curitiba, por exemplo, vêm só
    // como "Curitiba" ou "Curitiba | On-site", sem país nenhum.
    private static final Pattern LOCALIZACAO_ESTRANGEIRA = Pattern.compile(
            "mexico|méxico|argentina|colombia|colômbia|chile|peru|peru|uruguay|uruguai|" +
                    "singapore|singapura|london|londres|united kingdom|\\buk\\b|germany|alemanha|" +
                    "berlin|cologne|colônia|munich|munique|france|frança|paris|romania|romênia|" +
                    "bucharest|bucareste|spain|espanha|barcelona|madrid|portugal|lisbon|lisboa|" +
                    "italy|itália|milan|new york|manhattan|\\busa\\b|united states|miami|" +
                    "san francisco|seattle|austin|poland|polônia|netherlands|holanda|amsterdam|" +
                    "canada|canadá|toronto|india|índia|china|japan|japão|tokyo",
            Pattern.CASE_INSENSITIVE);

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    @Override
    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        int excluidas = 0;

        for (Map.Entry<String, String> board : BOARDS.entrySet()) {
            String token = board.getKey();
            String empresa = board.getValue();
            try {
                String url = JOBS_URL.replace("{token}", token);
                String responseBody = restTemplate.getForObject(url, String.class);
                JsonNode root = mapper.readTree(responseBody);
                JsonNode jobsNode = root.path("jobs");
                if (!jobsNode.isArray()) continue;

                int antesDesteBoard = jobs.size();
                for (JsonNode j : jobsNode) {
                    String location = j.path("location").path("name").asText(null);
                    if (location != null && LOCALIZACAO_ESTRANGEIRA.matcher(location).find()) {
                        excluidas++;
                        continue;
                    }
                    jobs.add(paraJob(j, empresa, location));
                }
                log.info("Greenhouse ({}): {} vagas (após filtro geográfico)", empresa, jobs.size() - antesDesteBoard);
            } catch (Exception e) {
                log.warn("Erro ao buscar board '{}' ({}) na Greenhouse: {}", token, empresa, e.getMessage());
            }
        }

        log.info("Greenhouse: {} vagas no total de {} empresas ({} excluídas por localização estrangeira)",
                jobs.size(), BOARDS.size(), excluidas);
        return jobs;
    }

    private Job paraJob(JsonNode j, String empresa, String location) {
        String title = j.path("title").asText("Vaga sem título");
        String url = j.path("absolute_url").asText(null);
        LocalDateTime postedAt = parseUpdatedAt(j.path("updated_at").asText(null));

        String workplaceType = null;
        if (location != null) {
            String norm = location.toLowerCase();
            if (norm.contains("remote") || norm.contains("remoto")) workplaceType = "REMOTO";
            else if (norm.contains("hybrid") || norm.contains("híbrido") || norm.contains("hibrido")) workplaceType = "HIBRIDO";
        }

        return Job.builder()
                .title(title)
                .company(empresa)
                .url(url)
                .source("GREENHOUSE")
                .workplaceType(workplaceType)
                // Não dá pra separar cidade/estado de forma confiável — o
                // formato varia demais entre empresas (ver comentário da
                // classe). Guarda o texto cru em vez de fabricar um estado.
                .city(location)
                .tags("greenhouse")
                .postedAt(postedAt != null ? postedAt : LocalDateTime.now())
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    private LocalDateTime parseUpdatedAt(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            return OffsetDateTime.parse(raw).toLocalDateTime();
        } catch (Exception e) {
            return null;
        }
    }
}

package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@Slf4j
public class ArbeitnowService {

    // API pública de vagas europeias com filtro de remote
    private static final String API_URL = "https://arbeitnow.com/api/job-board-api";

    private final SalaryExtractor salaryExtractor;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    // Limita a paginação pra não sobrecarregar a API pública gratuita
    private static final int MAX_PAGES = 5;

    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        try {
            String nextUrl = API_URL;
            int page = 0;
            while (nextUrl != null && page < MAX_PAGES) {
                String response = restTemplate.getForObject(nextUrl, String.class);
                JsonNode root = mapper.readTree(response);
                JsonNode data = root.get("data");
                if (data == null || !data.isArray() || data.isEmpty()) break;

                for (JsonNode node : data) {
                    try {
                        // Filtra só vagas remotas
                        boolean isRemote = node.has("remote") && node.get("remote").asBoolean();
                        if (!isRemote) continue;

                        StringJoiner tagsJoiner = new StringJoiner(",");
                        JsonNode tagsNode = node.get("tags");
                        if (tagsNode != null && tagsNode.isArray()) {
                            for (JsonNode tag : tagsNode) {
                                tagsJoiner.add(tag.asText().toLowerCase().trim());
                            }
                        }

                        // created_at pode ser timestamp unix ou string ISO
                        LocalDateTime postedAt = parseCreatedAt(node.get("created_at"));

                        String jobUrl = node.has("url") ? node.get("url").asText()
                                : "https://arbeitnow.com/jobs/" + node.get("slug").asText();

                        // Arbeitnow não tem campo estruturado de salário; quando
                        // divulgado, aparece embutido no texto da descrição.
                        String description = node.has("description") ? node.get("description").asText("") : "";
                        String salary = salaryExtractor.extract(description);

                        Job job = Job.builder()
                                .title(node.get("title").asText())
                                .company(node.get("company_name").asText())
                                .url(jobUrl)
                                .source("ARBEITNOW")
                                .workplaceType("REMOTO")
                                .salary(salary)
                                .tags(tagsJoiner.toString())
                                .postedAt(postedAt)
                                .fetchedAt(LocalDateTime.now())
                                .build();

                        jobs.add(job);
                    } catch (Exception e) {
                        log.warn("Erro ao parsear job do Arbeitnow: {}", e.getMessage());
                    }
                }

                nextUrl = nextPageUrl(root);
                page++;
            }
            log.info("Arbeitnow: {} vagas remotas buscadas ({} páginas)", jobs.size(), page);
        } catch (Exception e) {
            log.error("Erro ao buscar do Arbeitnow: {}", e.getMessage());
        }
        return jobs;
    }

    private String nextPageUrl(JsonNode root) {
        JsonNode links = root.get("links");
        if (links == null) return null;
        JsonNode next = links.get("next");
        if (next == null || next.isNull() || next.asText().isBlank()) return null;
        return next.asText();
    }

    private LocalDateTime parseCreatedAt(JsonNode node) {
        if (node == null) return LocalDateTime.now();
        try {
            if (node.isNumber()) {
                return LocalDateTime.ofInstant(
                        Instant.ofEpochSecond(node.asLong()),
                        ZoneId.systemDefault());
            } else {
                return LocalDateTime.parse(node.asText().replace(" ", "T"));
            }
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}

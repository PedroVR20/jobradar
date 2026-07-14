package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

@Service
@Slf4j
public class RemotiveService {

    private static final String API_URL =
            "https://remotive.com/api/remote-jobs?category=software-dev&limit=300";

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        try {
            String response = restTemplate.getForObject(API_URL, String.class);
            JsonNode root = mapper.readTree(response);
            JsonNode jobsNode = root.get("jobs");

            if (jobsNode == null || !jobsNode.isArray()) return jobs;

            for (JsonNode node : jobsNode) {
                try {
                    StringJoiner tagsJoiner = new StringJoiner(",");
                    JsonNode tagsNode = node.get("tags");
                    if (tagsNode != null && tagsNode.isArray()) {
                        for (JsonNode tag : tagsNode) {
                            tagsJoiner.add(tag.asText().toLowerCase().trim());
                        }
                    }

                    String salary = node.has("salary") ? node.get("salary").asText("") : "";

                    Job job = Job.builder()
                            .title(node.get("title").asText())
                            .company(node.get("company_name").asText())
                            .url(node.get("url").asText())
                            .source("REMOTIVE")
                            .workplaceType("REMOTO")
                            .salary(salary.isBlank() ? null : salary)
                            .tags(tagsJoiner.toString())
                            .postedAt(parseDate(node.get("publication_date").asText()))
                            .fetchedAt(LocalDateTime.now())
                            .build();

                    jobs.add(job);
                } catch (Exception e) {
                    log.warn("Erro ao parsear job do Remotive: {}", e.getMessage());
                }
            }
            log.info("Remotive: {} vagas buscadas", jobs.size());
        } catch (Exception e) {
            log.error("Erro ao buscar do Remotive: {}", e.getMessage());
        }
        return jobs;
    }

    private LocalDateTime parseDate(String raw) {
        try {
            // Formato: "2024-01-15 10:30:00" ou "2024-01-15T10:30:00"
            return LocalDateTime.parse(raw.replace(" ", "T"),
                    DateTimeFormatter.ISO_LOCAL_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}

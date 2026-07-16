package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Busca vagas do agregador querovagastech.com.br, filtrando as fontes que já
 * temos (Gupy) para evitar duplicatas. Fontes aproveitadas: InfoJobs, Stone,
 * Solides, Thoughtworks, Totvs, RedHat, AccentureWorkday e Manual.
 * A API é a mesma que o site usa internamente — sem autenticação, sem
 * documentação oficial.
 */
@Service
@Slf4j
public class QuerovagastechService {

    private static final String API_URL = "https://www.querovagastech.com.br/api/jobs";
    private static final int PAGE_SIZE = 100;

    // Fontes já cobertas pelo nosso GupyService — pulamos para não duplicar
    private static final Set<String> FONTES_IGNORADAS = Set.of(
            "GupyPortalTecnologia", "GupyExample", "Fixtures"
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    private static final Map<String, String> UF_PARA_ESTADO = Map.ofEntries(
            Map.entry("AC", "Acre"), Map.entry("AL", "Alagoas"), Map.entry("AP", "Amapá"),
            Map.entry("AM", "Amazonas"), Map.entry("BA", "Bahia"), Map.entry("CE", "Ceará"),
            Map.entry("DF", "Distrito Federal"), Map.entry("ES", "Espírito Santo"), Map.entry("GO", "Goiás"),
            Map.entry("MA", "Maranhão"), Map.entry("MT", "Mato Grosso"), Map.entry("MS", "Mato Grosso do Sul"),
            Map.entry("MG", "Minas Gerais"), Map.entry("PA", "Pará"), Map.entry("PB", "Paraíba"),
            Map.entry("PR", "Paraná"), Map.entry("PE", "Pernambuco"), Map.entry("PI", "Piauí"),
            Map.entry("RJ", "Rio de Janeiro"), Map.entry("RN", "Rio Grande do Norte"), Map.entry("RS", "Rio Grande do Sul"),
            Map.entry("RO", "Rondônia"), Map.entry("RR", "Roraima"), Map.entry("SC", "Santa Catarina"),
            Map.entry("SP", "São Paulo"), Map.entry("SE", "Sergipe"), Map.entry("TO", "Tocantins")
    );

    public List<Job> fetchJobs() {
        List<Job> jobs = new ArrayList<>();
        try {
            int page = 1;
            int total = Integer.MAX_VALUE;

            while ((long) (page - 1) * PAGE_SIZE < total) {
                String url = UriComponentsBuilder.fromHttpUrl(API_URL)
                        .queryParam("page", page)
                        .queryParam("pageSize", PAGE_SIZE)
                        .queryParam("sort", "postedAt:desc")
                        .toUriString();

                String response = restTemplate.getForObject(url, String.class);
                JsonNode root = mapper.readTree(response);
                total = root.has("total") ? root.get("total").asInt() : 0;

                JsonNode items = root.get("items");
                if (items == null || !items.isArray() || items.isEmpty()) break;

                for (JsonNode node : items) {
                    try {
                        String sourceName = node.has("sourceName") ? node.get("sourceName").asText() : "";
                        if (FONTES_IGNORADAS.contains(sourceName)) continue;

                        jobs.add(parseJob(node));
                    } catch (Exception e) {
                        log.warn("Erro ao parsear vaga do QuerovagasTech: {}", e.getMessage());
                    }
                }
                page++;
            }
            log.info("QuerovagasTech: {} vagas buscadas (excluindo fontes Gupy)", jobs.size());
        } catch (Exception e) {
            log.error("Erro ao buscar do QuerovagasTech: {}", e.getMessage());
        }
        return jobs;
    }

    private Job parseJob(JsonNode node) {
        String applyUrl = node.has("applyUrl") ? node.get("applyUrl").asText() : null;
        String title = node.has("title") ? node.get("title").asText() : "Sem título";
        String company = node.has("company") ? node.get("company").asText() : "Empresa não informada";

        String workMode = node.has("workMode") ? node.get("workMode").asText() : "";
        String workplaceType = translateWorkMode(workMode);

        String[] locationParts = parseLocation(node.has("location") ? node.get("location").asText() : "");
        String city = locationParts[0];
        String state = locationParts[1];

        List<String> tagList = new ArrayList<>();
        if (node.has("tags") && node.get("tags").isArray()) {
            for (JsonNode t : node.get("tags")) {
                tagList.add(t.asText().toLowerCase());
            }
        }
        if (!workplaceType.isEmpty()) tagList.add(workplaceType.toLowerCase());
        if (city != null) tagList.add(city.toLowerCase());

        String salary = parseSalary(node.get("salary"));
        String seniority = translateSeniority(node.has("seniority") ? node.get("seniority").asText() : "");

        return Job.builder()
                .title(title)
                .company(company)
                .url(applyUrl)
                .source("QUEROVAGASTECH")
                .workplaceType(workplaceType.isEmpty() ? null : workplaceType)
                .state(state)
                .city(city)
                .tags(String.join(",", tagList))
                .salary(salary)
                .seniority(seniority)
                .postedAt(parsePostedAt(node.get("postedAt")))
                .fetchedAt(LocalDateTime.now())
                .build();
    }

    private String translateWorkMode(String workMode) {
        return switch (workMode) {
            case "Remote" -> "REMOTO";
            case "Hybrid" -> "HIBRIDO";
            case "Onsite" -> "PRESENCIAL";
            default -> "";
        };
    }

    private String translateSeniority(String seniority) {
        return switch (seniority) {
            case "Intern" -> "ESTAGIO";
            case "Junior" -> "JUNIOR";
            case "Mid" -> "PLENO";
            case "Senior", "Lead" -> "SENIOR";
            default -> null; // deixa o SeniorityClassifier inferir pelo título
        };
    }

    // Suporta dois formatos: "City - UF" (InfoJobs) e "City, State, BR" (Gupy)
    private String[] parseLocation(String location) {
        if (location == null || location.isBlank() || location.length() <= 3) {
            return new String[]{null, null};
        }
        // Formato: "São Paulo - SP"
        if (location.contains(" - ")) {
            String[] parts = location.split(" - ");
            if (parts.length == 2 && parts[1].trim().length() == 2) {
                String city = parts[0].trim();
                String state = UF_PARA_ESTADO.get(parts[1].trim().toUpperCase());
                return new String[]{city, state};
            }
        }
        // Formato: "City, State, BR"
        if (location.contains(", ")) {
            String[] parts = location.split(", ");
            if (parts.length >= 2) {
                String city = parts[0].trim();
                String statePart = parts[1].trim();
                // se o estado for a UF de 2 letras, converte; senão usa direto
                String state = UF_PARA_ESTADO.getOrDefault(statePart.toUpperCase(), statePart);
                return new String[]{city, state};
            }
        }
        return new String[]{null, null};
    }

    private String parseSalary(JsonNode salaryNode) {
        if (salaryNode == null || salaryNode.isNull()) return null;
        try {
            int min = salaryNode.has("min") ? salaryNode.get("min").asInt() : 0;
            int max = salaryNode.has("max") ? salaryNode.get("max").asInt() : 0;
            String currency = salaryNode.has("currency") ? salaryNode.get("currency").asText() : "BRL";
            String period = salaryNode.has("period") ? salaryNode.get("period").asText() : "month";
            String suffix = "month".equals(period) ? "/mês" : "/ano";
            String prefix = "BRL".equals(currency) ? "R$ " : currency + " ";

            if (min <= 0 && max <= 0) return null;
            if (min == max || max <= 0) return prefix + String.format("%,.0f", (double) (min > 0 ? min : max)) + suffix;
            return prefix + String.format("%,.0f", (double) min) + " – " + String.format("%,.0f", (double) max) + suffix;
        } catch (Exception e) {
            return null;
        }
    }

    private LocalDateTime parsePostedAt(JsonNode node) {
        if (node == null || node.isNull()) return LocalDateTime.now();
        try {
            return OffsetDateTime.parse(node.asText(), DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .toLocalDateTime();
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }
}

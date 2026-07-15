package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Busca vagas de estágio/trainee no portal público da Eureca
 * (https://app.eureca.me/oportunidades), plataforma usada por empresas como
 * Bradesco, Stellantis, Natura, Sephora, Equinor e SLC Agrícola. A API é a
 * mesma que o próprio site usa para listar vagas — sem autenticação, mas
 * também sem documentação oficial (descoberta por engenharia reversa do
 * tráfego do site), então pode quebrar se a Eureca mudar o front-end.
 */
@Service
@Slf4j
public class EurecaService {

    private static final String API_URL = "https://candidate-api.eureca.me/v2/opportunities";
    private static final int PAGE_SIZE = 50; // máximo aceito pela API

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
            while ((page - 1) * PAGE_SIZE < total) {
                String url = UriComponentsBuilder.fromHttpUrl(API_URL)
                        .queryParam("pageSize", PAGE_SIZE)
                        .queryParam("page", page)
                        .toUriString();

                String response = restTemplate.getForObject(url, String.class);
                JsonNode root = mapper.readTree(response);
                total = root.has("total") ? root.get("total").asInt() : 0;

                JsonNode items = root.get("items");
                if (items == null || !items.isArray()) break;

                for (JsonNode node : items) {
                    try {
                        jobs.add(parseJob(node));
                    } catch (Exception e) {
                        log.warn("Erro ao parsear vaga da Eureca: {}", e.getMessage());
                    }
                }

                if (items.isEmpty()) break;
                page++;
            }
            log.info("Eureca: {} vagas buscadas", jobs.size());
        } catch (Exception e) {
            log.error("Erro ao buscar da Eureca: {}", e.getMessage());
        }
        return jobs;
    }

    private Job parseJob(JsonNode node) {
        String id = node.get("id").asText();
        String workModel = node.has("workModel") && !node.get("workModel").isNull()
                ? node.get("workModel").asText() : "";
        String workplaceType = translateWorkModel(workModel);

        String stateAcronym = node.has("stateAcronym") && !node.get("stateAcronym").isNull()
                ? node.get("stateAcronym").asText() : "";
        String state = UF_PARA_ESTADO.getOrDefault(stateAcronym, null);
        String city = node.has("cityName") && !node.get("cityName").isNull()
                ? node.get("cityName").asText() : null;

        StringJoiner tagsJoiner = new StringJoiner(",");
        tagsJoiner.add("estagio");
        if (!workplaceType.isEmpty()) tagsJoiner.add(workplaceType.toLowerCase());
        if (city != null) tagsJoiner.add(city.toLowerCase());
        if (state != null) tagsJoiner.add(state.toLowerCase());

        String logoUrl = node.has("companyLogoUrl") && !node.get("companyLogoUrl").isNull()
                ? node.get("companyLogoUrl").asText().trim() : null;
        boolean pcd = node.has("isPcd") && node.get("isPcd").asBoolean();

        return Job.builder()
                .title(node.get("name").asText())
                .company(node.has("companyName") && !node.get("companyName").isNull()
                        ? node.get("companyName").asText() : "Empresa não informada")
                .url("https://app.eureca.me/vagas/" + id)
                .source("EURECA")
                .workplaceType(workplaceType.isEmpty() ? null : workplaceType)
                .state(state)
                .city(city)
                .tags(tagsJoiner.toString())
                .postedAt(parseDateTime(node.get("publishedAt")))
                .expiresAt(parseDate(node.get("endApplying")))
                .fetchedAt(LocalDateTime.now())
                .companyLogoUrl(logoUrl)
                .pcd(pcd ? Boolean.TRUE : null)
                .build();
    }

    private String translateWorkModel(String workModel) {
        return switch (workModel.toLowerCase()) {
            case "remoto" -> "REMOTO";
            case "hibrido" -> "HIBRIDO";
            case "presencial" -> "PRESENCIAL";
            default -> "";
        };
    }

    private LocalDateTime parseDateTime(JsonNode node) {
        if (node == null || node.isNull()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(node.asText().replace("Z", ""),
                    DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private LocalDate parseDate(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            return LocalDate.parse(node.asText().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}

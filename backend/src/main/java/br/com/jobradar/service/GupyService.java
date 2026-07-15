package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Busca vagas de tecnologia no portal público de vagas da Gupy
 * (https://portal.gupy.io/job-search), o ATS usado por milhares de empresas
 * brasileiras — incluindo grandes nomes como Itaú, Stone, Localiza, Ambev,
 * Vivo, entre outras. A API é a mesma que o próprio portal público usa para
 * renderizar os resultados de busca, sem necessidade de autenticação.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GupyService {

    private static final String API_URL = "https://employability-portal.gupy.io/api/v1/jobs";
    private static final String JOB_DETAIL_URL = "https://employability-portal.gupy.io/api/v1/jobs/{id}";
    private static final Pattern JOB_ID_IN_URL = Pattern.compile("\"jobId\":(\\d+)");

    private final SalaryExtractor salaryExtractor;

    // Termos cobrindo o espectro de vagas de tecnologia + estágio, para dar
    // uma boa cobertura de volume sem varrer o catálogo inteiro da Gupy.
    private static final List<String> SEARCH_TERMS = List.of(
            "desenvolvedor", "programador", "engenheiro de software",
            "java", "python", "javascript", "frontend", "backend", "fullstack",
            "devops", "dados", "estagio tecnologia", "estagio ti", "trainee tecnologia",
            "analista de sistemas", "suporte ti", "qa software", "product manager",
            "ux ui designer", "scrum master", "arquiteto de software", "cloud engineer",
            "machine learning", "sql"
    );

    // A busca nacional (sem state) limita a 100 resultados por termo — vagas
    // de um estado específico podem ficar de fora do corte por relevância.
    // Repetir os termos principais com state=Rio de Janeiro garante boa
    // cobertura da região sem depender do ranking nacional.
    private static final String FOCO_ESTADO = "Rio de Janeiro";
    private static final List<String> TERMOS_FOCO_ESTADO = List.of(
            "desenvolvedor", "programador", "estagio tecnologia", "estagio ti",
            "trainee tecnologia", "dados", "java", "python", "frontend", "backend",
            "analista de sistemas", "suporte ti"
    );

    private static final int LIMIT_PER_TERM = 100;

    private final ObjectMapper mapper = new ObjectMapper();
    private final RestTemplate restTemplate = new RestTemplate();

    public List<Job> fetchJobs() {
        // dedup por id da vaga: o mesmo anúncio aparece em múltiplos termos de busca
        Map<Long, Job> byId = new LinkedHashMap<>();

        for (String term : SEARCH_TERMS) {
            try {
                fetchByTerm(term, null, byId);
            } catch (Exception e) {
                log.warn("Erro ao buscar '{}' na Gupy: {}", term, e.getMessage());
            }
        }

        int antesDoFoco = byId.size();
        for (String term : TERMOS_FOCO_ESTADO) {
            try {
                fetchByTerm(term, FOCO_ESTADO, byId);
            } catch (Exception e) {
                log.warn("Erro ao buscar '{}' (state={}) na Gupy: {}", term, FOCO_ESTADO, e.getMessage());
            }
        }

        log.info("Gupy: {} vagas únicas buscadas ({} extras via foco em {})",
                byId.size(), byId.size() - antesDoFoco, FOCO_ESTADO);
        return byId.values().stream().toList();
    }

    private void fetchByTerm(String term, String stateFilter, Map<Long, Job> byId) throws Exception {
        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(API_URL)
                .queryParam("jobName", term)
                .queryParam("limit", LIMIT_PER_TERM)
                .queryParam("offset", 0);
        if (stateFilter != null && !stateFilter.isBlank()) {
            builder.queryParam("state", stateFilter);
        }
        String url = builder.toUriString();

        String response = restTemplate.getForObject(url, String.class);
        JsonNode root = mapper.readTree(response);
        JsonNode data = root.get("data");
        if (data == null || !data.isArray()) return;

        for (JsonNode node : data) {
            try {
                long jobId = node.get("id").asLong();
                if (byId.containsKey(jobId)) continue;

                String rawWorkplace = node.has("workplaceType") && !node.get("workplaceType").isNull()
                        ? node.get("workplaceType").asText() : "";
                String city = node.has("city") ? node.get("city").asText("") : "";
                String state = node.has("state") ? node.get("state").asText("") : "";
                String workplaceType = translateWorkplace(rawWorkplace);

                StringJoiner tagsJoiner = new StringJoiner(",");
                tagsJoiner.add(term.replace(" ", "-"));
                if (!workplaceType.isEmpty()) tagsJoiner.add(workplaceType.toLowerCase());
                if (!city.isBlank()) tagsJoiner.add(city.toLowerCase());
                if (!state.isBlank()) tagsJoiner.add(state.toLowerCase());

                String logoUrl = node.has("careerPageLogo") && !node.get("careerPageLogo").isNull()
                        ? node.get("careerPageLogo").asText().trim() : null;
                boolean pcd = node.has("isAffirmativeAction") && node.get("isAffirmativeAction").asBoolean();

                Job job = Job.builder()
                        .title(node.get("name").asText())
                        .company(node.has("careerPageName") ? node.get("careerPageName").asText() : "Empresa não informada")
                        .url(node.get("jobUrl").asText())
                        .source("GUPY")
                        .workplaceType(workplaceType.isEmpty() ? null : workplaceType)
                        .state(state.isBlank() ? null : state)
                        .city(city.isBlank() ? null : city)
                        .tags(tagsJoiner.toString())
                        .postedAt(parsePublishedDate(node.get("publishedDate")))
                        .expiresAt(parseDeadline(node.get("applicationDeadline")))
                        .fetchedAt(LocalDateTime.now())
                        .companyLogoUrl(logoUrl)
                        .pcd(pcd ? Boolean.TRUE : null)
                        .build();

                if (TechJobFilter.isTechJob(job.getTitle())) {
                    byId.put(jobId, job);
                }
            } catch (Exception e) {
                log.warn("Erro ao parsear job da Gupy: {}", e.getMessage());
            }
        }
    }

    /**
     * Busca a descrição completa da vaga (endpoint de detalhe) e tenta achar
     * uma menção de salário nela. A busca por termos não traz a descrição,
     * então isso exige uma requisição extra por vaga — só deve ser chamado
     * para um número limitado de vagas por ciclo (ex: as recém-inseridas),
     * nunca para o catálogo inteiro.
     *
     * @param jobUrl URL da vaga como salva em Job.url (contém o ID codificado em base64)
     * @return menção de salário encontrada, ou null
     */
    public String fetchSalaryHint(String jobUrl) {
        Long jobId = extractJobId(jobUrl);
        if (jobId == null) return null;

        try {
            String response = restTemplate.getForObject(JOB_DETAIL_URL, String.class, jobId);
            JsonNode root = mapper.readTree(response);
            JsonNode descNode = root.get("description");
            if (descNode == null || descNode.isNull()) return null;
            return salaryExtractor.extract(descNode.asText());
        } catch (Exception e) {
            log.debug("Não foi possível buscar detalhe da vaga Gupy {}: {}", jobId, e.getMessage());
            return null;
        }
    }

    // A URL da vaga traz um segmento base64 tipo ".../job/eyJqb2JJZCI6MTE2..."
    // que decodifica pra {"jobId":123,"source":"gupy_portal"}.
    private Long extractJobId(String jobUrl) {
        try {
            int start = jobUrl.indexOf("/job/") + 5;
            int end = jobUrl.indexOf('?', start);
            if (start < 5) return null;
            String encoded = end > 0 ? jobUrl.substring(start, end) : jobUrl.substring(start);
            String decoded = new String(Base64.getDecoder().decode(encoded), StandardCharsets.UTF_8);
            Matcher m = JOB_ID_IN_URL.matcher(decoded);
            return m.find() ? Long.parseLong(m.group(1)) : null;
        } catch (Exception e) {
            return null;
        }
    }

    private String translateWorkplace(String rawWorkplaceType) {
        if (rawWorkplaceType == null || rawWorkplaceType.isBlank()) return "";
        return switch (rawWorkplaceType.toLowerCase()) {
            case "remote" -> "REMOTO";
            case "hybrid" -> "HIBRIDO";
            case "on-site", "onsite" -> "PRESENCIAL";
            default -> "";
        };
    }

    private LocalDateTime parsePublishedDate(JsonNode node) {
        if (node == null || node.isNull()) return LocalDateTime.now();
        try {
            return LocalDateTime.parse(node.asText().replace("Z", ""),
                    DateTimeFormatter.ISO_DATE_TIME);
        } catch (Exception e) {
            return LocalDateTime.now();
        }
    }

    private LocalDate parseDeadline(JsonNode node) {
        if (node == null || node.isNull() || node.asText().isBlank()) return null;
        try {
            return LocalDate.parse(node.asText().substring(0, 10));
        } catch (Exception e) {
            return null;
        }
    }
}

package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Classificação de senioridade e stack técnica via IA (Gemini) — usada como
 * segunda opinião só quando o {@link SeniorityClassifier} baseado em regex
 * não consegue decidir (NAO_INFORMADO), tipicamente título ambíguo ou em
 * idioma que os padrões não cobrem. Também extrai a stack técnica citada no
 * título pra completar as tags. Chamada só nos casos ambíguos (não em toda
 * vaga nova) pra respeitar o limite de requisições do free tier do Gemini.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiClassifierService {

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Resultado(String seniority, List<String> stackTags) {}

    /**
     * @return null se a IA estiver desativada ou a chamada falhar de
     *         qualquer forma — nesse caso o chamador deve manter o
     *         resultado do classificador por regex (NAO_INFORMADO).
     */
    public Resultado classificar(String title, String company, String tags) {
        if (!geminiService.isEnabled()) return null;

        String prompt = """
                Classifique esta vaga de emprego de tecnologia.

                Título: %s
                Empresa: %s
                Tags já conhecidas: %s

                Responda APENAS com um JSON válido, sem markdown e sem texto extra, no formato exato:
                {"seniority": "ESTAGIO|JUNIOR|PLENO|SENIOR|NAO_INFORMADO", "stack": ["tag1", "tag2"]}

                Regras:
                - seniority: use NAO_INFORMADO se o título realmente não permitir saber o nível
                - stack: no máximo 5 tecnologias/linguagens/frameworks citados no título ou nas tags, em minúsculas, sem inventar o que não está mencionado
                """.formatted(title, company == null ? "" : company, tags == null ? "" : tags);

        String response = geminiService.generateText(prompt);
        if (response == null) return null;

        try {
            JsonNode node = mapper.readTree(extractJson(response));
            String seniority = node.path("seniority").asText(null);
            if (seniority == null || !isValidSeniority(seniority)) return null;

            List<String> stack = new ArrayList<>();
            node.path("stack").forEach(t -> {
                String v = t.asText().toLowerCase().trim();
                if (!v.isBlank()) stack.add(v);
            });

            return new Resultado(seniority, stack);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini ao classificar vaga: {}", e.getMessage());
            return null;
        }
    }

    private boolean isValidSeniority(String s) {
        return switch (s) {
            case SeniorityClassifier.ESTAGIO, SeniorityClassifier.JUNIOR, SeniorityClassifier.PLENO,
                 SeniorityClassifier.SENIOR, SeniorityClassifier.NAO_INFORMADO -> true;
            default -> false;
        };
    }

    // O Gemini às vezes envolve o JSON em ```json ... ``` mesmo pedindo pra não fazer isso.
    private String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }
}

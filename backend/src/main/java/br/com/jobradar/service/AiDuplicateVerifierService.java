package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Segunda opinião via IA sobre grupos de possíveis duplicatas já filtrados
 * pela similaridade de palavras (Jaccard, em JobController.getDuplicates) —
 * o algoritmo por palavras não distingue "vagas de times/produtos diferentes
 * com título parecido" de fato duplicadas (ex: "Desenvolvedor Fullstack
 * Pleno — Squad Dados" vs "— Squad Labs"), a IA ajuda a descartar esses
 * falsos positivos. Só roda em cima do que o Jaccard já filtrou (não em
 * todas as vagas), com um limite de grupos verificados por chamada em
 * JobController pra respeitar o free tier do Gemini.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiDuplicateVerifierService {

    private final GeminiService geminiService;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * @param company nome da empresa (contexto pro prompt)
     * @param titles  títulos do grupo candidato (2 ou mais)
     * @return {@code true} se a IA confirma que descrevem a mesma vaga (ou
     *         se a IA estiver desativada/a chamada falhar — nesse caso
     *         mantém o veredito do Jaccard sem alterar, já que não dá pra
     *         verificar); {@code false} se a IA identificar que são vagas
     *         genuinamente diferentes
     */
    public boolean confirmar(String company, List<String> titles) {
        if (!geminiService.isEnabled()) return true;

        String listaTitulos = String.join("\n", titles.stream().map(t -> "- " + t).toList());
        String prompt = """
                Estes títulos de vaga foram publicados pela mesma empresa (%s) e têm
                palavras parecidas. Eles descrevem a MESMA posição/oportunidade (só
                republicada ou vinda de fontes diferentes), ou são vagas GENUINAMENTE
                DIFERENTES (times, produtos, tecnologias ou senioridades distintas)?

                Títulos:
                %s

                Responda APENAS com um JSON válido, sem markdown e sem texto extra:
                {"mesma_vaga": true|false}
                """.formatted(company, listaTitulos);

        String response = geminiService.generateText(prompt);
        if (response == null) return true; // sem resposta da IA, mantém o veredito do Jaccard

        try {
            JsonNode node = mapper.readTree(extractJson(response));
            return node.path("mesma_vaga").asBoolean(true);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini ao verificar duplicata: {}", e.getMessage());
            return true;
        }
    }

    private String extractJson(String text) {
        String trimmed = text.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceAll("^```(json)?", "").replaceAll("```$", "").trim();
        }
        return trimmed;
    }
}

package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Compara o perfil/currículo do candidato (enviado pelo frontend a partir do
 * que o usuário salvou em Configurações — nunca persistido aqui) com os
 * requisitos reais da vaga (descrição buscada via JobDescriptionService +
 * campos já conhecidos) e pede uma avaliação honesta de compatibilidade.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class MatchScoreService {

    private final GeminiService geminiService;
    private final JobDescriptionService jobDescriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record MatchResult(int score, List<String> pontosFortes, List<String> pontosFaltando, String resumo) {}

    public record MatchOutcome(MatchResult result, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return result != null;
        }
    }

    public MatchOutcome calcular(Job job, String perfilCandidato, String feedbackContext) {
        if (perfilCandidato == null || perfilCandidato.isBlank()) {
            return new MatchOutcome(null,
                    "Salve seu perfil/currículo em ⚙️ Configurações primeiro (ou cole na hora), pra IA ter o que comparar.",
                    false);
        }

        StringBuilder contexto = new StringBuilder();
        contexto.append("Vaga: ").append(job.getTitle()).append(" @ ").append(job.getCompany()).append("\n");
        if (job.getSeniority() != null) contexto.append("Nível: ").append(job.getSeniority()).append("\n");
        if (job.getTags() != null && !job.getTags().isBlank()) {
            contexto.append("Tecnologias/tags: ").append(job.getTags().replace(",", ", ")).append("\n");
        }
        String descricao = jobDescriptionService.fetchDescription(job.getUrl());
        if (descricao != null) {
            contexto.append("Descrição completa da vaga:\n").append(descricao).append("\n");
        }

        String prompt = """
                Compare o PERFIL DO CANDIDATO com os REQUISITOS DA VAGA abaixo e avalie
                a compatibilidade real entre os dois. Seja honesto e criterioso — não
                infle a nota só pra soar positivo, e não invente que o candidato tem
                algo que não está descrito no perfil dele.

                %s

                Perfil do candidato:
                %s
                %s

                Devolva APENAS um JSON válido, sem markdown e sem texto fora do JSON,
                no formato exato:
                {"score": 0-100, "pontosFortes": ["...", "..."], "pontosFaltando": ["...", "..."], "resumo": "1-2 frases"}
                """.formatted(contexto, perfilCandidato, GeminiService.feedbackSection(feedbackContext));

        GeminiService.GeminiResult resultado = geminiService.generate(prompt);
        if (!resultado.ok()) {
            return new MatchOutcome(null, resultado.errorMessage(), resultado.rateLimited());
        }

        try {
            JsonNode node = mapper.readTree(GeminiService.stripMarkdownFences(resultado.text()));
            int score = Math.max(0, Math.min(100, node.path("score").asInt(0)));
            MatchResult result = new MatchResult(
                    score,
                    toList(node.path("pontosFortes")),
                    toList(node.path("pontosFaltando")),
                    node.path("resumo").asText("")
            );
            return new MatchOutcome(result, null, false);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini pro match score: {}", e.getMessage());
            return new MatchOutcome(null, "A IA devolveu uma resposta inesperada. Tente de novo.", false);
        }
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}

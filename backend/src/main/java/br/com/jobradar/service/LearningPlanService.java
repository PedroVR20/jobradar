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
 * "Contramedida" pra um ponto a desenvolver identificado pelo
 * {@link MatchScoreService} — o usuário vê "falta experiência com Node.js"
 * na análise de compatibilidade e pede um plano prático de como fechar essa
 * lacuna específica, considerando o que ele já sabe (não sugere do zero algo
 * que o perfil já mostra base).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class LearningPlanService {

    private final GeminiService geminiService;
    private final JobDescriptionService jobDescriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record LearningPlan(String resumo, String tempoEstimado, List<String> passos) {}

    public record PlanOutcome(LearningPlan plan, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return plan != null;
        }
    }

    public PlanOutcome gerar(Job job, String gap, String perfilCandidato, String feedbackContext) {
        if (gap == null || gap.isBlank()) {
            return new PlanOutcome(null, "Ponto a desenvolver não informado.", false);
        }

        StringBuilder contexto = new StringBuilder();
        contexto.append("Vaga: ").append(job.getTitle()).append(" @ ").append(job.getCompany()).append("\n");
        if (job.getSeniority() != null) contexto.append("Nível: ").append(job.getSeniority()).append("\n");
        String descricao = jobDescriptionService.fetchDescription(job.getUrl());
        if (descricao != null) {
            contexto.append("Descrição completa da vaga:\n").append(descricao).append("\n");
        }

        String perfilBloco = (perfilCandidato == null || perfilCandidato.isBlank())
                ? "(usuário não informou perfil/currículo — sugira um plano genérico pra alguém buscando essa vaga)"
                : perfilCandidato;

        String prompt = """
                O usuário está se candidatando à vaga abaixo e uma análise de compatibilidade
                identificou o seguinte PONTO A DESENVOLVER (uma lacuna real entre o perfil dele
                e os requisitos da vaga):

                "%s"

                %s

                Perfil atual do candidato (use como ponto de partida — não sugira do zero algo
                que ele já mostra ter base, e não estenda o plano além dessa lacuna específica):
                %s
                %s

                Sugira um plano de ação prático e realista pra fechar especificamente essa
                lacuna. Os passos precisam ser concretos e acionáveis (não genérico tipo
                "estude mais") — cite recursos reais quando fizer sentido (nome de curso/
                documentação oficial conhecida, tipo de projeto prático pra construir,
                certificação relevante), e considere o tempo realista pra alguém com o perfil
                dele, não pra começar do absoluto zero se ele já tem base relacionada.

                Devolva APENAS um JSON válido, sem markdown e sem texto fora do JSON, no
                formato exato:
                {"resumo": "1 frase com a estratégia geral", "tempoEstimado": "ex: 2-4 semanas", "passos": ["passo 1 concreto", "passo 2", "passo 3"]}
                """.formatted(gap, contexto, perfilBloco, GeminiService.feedbackSection(feedbackContext));

        GeminiService.GeminiResult resultado = geminiService.generate(prompt);
        if (!resultado.ok()) {
            return new PlanOutcome(null, resultado.errorMessage(), resultado.rateLimited());
        }

        try {
            JsonNode node = mapper.readTree(GeminiService.stripMarkdownFences(resultado.text()));
            LearningPlan plan = new LearningPlan(
                    node.path("resumo").asText(""),
                    node.path("tempoEstimado").asText(""),
                    toList(node.path("passos"))
            );
            return new PlanOutcome(plan, null, false);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini pro plano de aprendizado: {}", e.getMessage());
            return new PlanOutcome(null, "A IA devolveu uma resposta inesperada. Tente de novo.", false);
        }
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> out.add(n.asText()));
        return out;
    }
}

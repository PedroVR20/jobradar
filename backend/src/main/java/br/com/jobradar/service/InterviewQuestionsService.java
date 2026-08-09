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
 * Gera perguntas prováveis de entrevista pra uma vaga, usando a descrição
 * real (via JobDescriptionService) e, se disponível, o perfil do candidato
 * (mesmo texto salvo em Configurações, enviado pelo frontend) pra ajustar o
 * foco — sem perfil, gera perguntas genéricas pra vaga/stack mesmo assim.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewQuestionsService {

    private final GeminiService geminiService;
    private final JobDescriptionService jobDescriptionService;
    private final ObjectMapper mapper = new ObjectMapper();

    public record QuestionsOutcome(List<String> questions, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return questions != null;
        }
    }

    public QuestionsOutcome gerar(Job job, String perfilCandidato) {
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
        boolean temPerfil = perfilCandidato != null && !perfilCandidato.isBlank();
        if (temPerfil) {
            contexto.append("Perfil do candidato:\n").append(perfilCandidato).append("\n");
        }

        String prompt = """
                Com base na vaga abaixo%s, liste de 6 a 8 perguntas prováveis que o
                candidato pode receber numa entrevista pra essa posição — misture
                técnicas (específicas da stack/senioridade) e comportamentais. Evite
                perguntas genéricas demais tipo "fale sobre você"; prefira perguntas
                que um entrevistador dessa vaga específica realmente faria.

                %s

                Devolva APENAS um JSON válido, sem markdown e sem texto fora do JSON,
                no formato exato: {"perguntas": ["...", "..."]}
                """.formatted(temPerfil ? " e no perfil do candidato" : "", contexto);

        GeminiService.GeminiResult resultado = geminiService.generate(prompt);
        if (!resultado.ok()) {
            return new QuestionsOutcome(null, resultado.errorMessage(), resultado.rateLimited());
        }

        try {
            JsonNode node = mapper.readTree(GeminiService.stripMarkdownFences(resultado.text()));
            List<String> perguntas = new ArrayList<>();
            node.path("perguntas").forEach(n -> perguntas.add(n.asText()));
            if (perguntas.isEmpty()) {
                return new QuestionsOutcome(null, "A IA não devolveu perguntas dessa vez. Tente de novo.", false);
            }
            return new QuestionsOutcome(perguntas, null, false);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini pras perguntas de entrevista: {}", e.getMessage());
            return new QuestionsOutcome(null, "A IA devolveu uma resposta inesperada. Tente de novo.", false);
        }
    }
}

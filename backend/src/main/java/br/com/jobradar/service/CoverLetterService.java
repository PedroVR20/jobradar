package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Gera carta de apresentação personalizada por vaga via Gemini. Usa os
 * campos que o Job Radar já guarda (título, empresa, senioridade, modalidade,
 * local, tags, salário, notas pessoais do usuário) — o modelo de dados não
 * tem a descrição completa da vaga nem o currículo/perfil do candidato, então
 * o resultado é um texto direcionado à vaga, mas genérico quanto à
 * experiência do candidato (o prompt instrui a IA a não inventar histórico
 * profissional). O campo extraContext deixa o usuário colar mais detalhes
 * (trechos da descrição, requisitos, seu próprio resumo) pra melhorar a
 * qualidade quando quiser.
 */
@Service
@RequiredArgsConstructor
public class CoverLetterService {

    private final GeminiService geminiService;

    public GeminiService.GeminiResult gerar(Job job, String extraContext) {
        StringBuilder contexto = new StringBuilder();
        contexto.append("Vaga: ").append(job.getTitle()).append("\n");
        contexto.append("Empresa: ").append(job.getCompany()).append("\n");
        if (job.getSeniority() != null) {
            contexto.append("Nível: ").append(job.getSeniority()).append("\n");
        }
        if (job.getWorkplaceType() != null) {
            contexto.append("Modalidade: ").append(job.getWorkplaceType()).append("\n");
        }
        String local = Stream.of(job.getCity(), job.getState()).filter(Objects::nonNull).reduce((a, b) -> a + ", " + b).orElse(null);
        if (local != null) {
            contexto.append("Local: ").append(local).append("\n");
        }
        if (job.getTags() != null && !job.getTags().isBlank()) {
            contexto.append("Tecnologias/tags: ").append(job.getTags().replace(",", ", ")).append("\n");
        }
        if (job.getSalary() != null) {
            contexto.append("Salário: ").append(job.getSalary()).append("\n");
        }
        if (job.getNotes() != null && !job.getNotes().isBlank()) {
            contexto.append("Notas pessoais do candidato sobre essa vaga: ").append(job.getNotes()).append("\n");
        }
        if (extraContext != null && !extraContext.isBlank()) {
            contexto.append("Contexto adicional fornecido pelo candidato:\n").append(extraContext).append("\n");
        }

        String prompt = """
                Escreva uma carta de apresentação curta e direta (máximo 4 parágrafos)
                em português do Brasil, em primeira pessoa, para uma candidatura à
                vaga abaixo. Tom profissional mas natural, sem clichês genéricos tipo
                "sou apaixonado por tecnologia" — foque em conectar o perfil às
                informações concretas da vaga. Não invente experiências, certificações
                ou anos de carreira que não foram informados: se não houver dados
                suficientes sobre o histórico do candidato, mantenha o texto focado no
                interesse genuíno pela vaga/empresa/stack e peça a oportunidade de uma
                conversa, sem inventar trajetória profissional.

                %s

                Devolva só o texto da carta, sem markdown, sem título, sem aspas ao redor.
                """.formatted(contexto);

        return geminiService.generate(prompt);
    }
}

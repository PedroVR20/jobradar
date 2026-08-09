package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.stream.Stream;

/**
 * Gera carta de apresentação personalizada por vaga via Gemini. Usa os
 * campos que o Job Radar já guarda (título, empresa, senioridade, modalidade,
 * local, tags, salário, notas pessoais do usuário), mais a descrição real da
 * vaga buscada na hora na própria página dela (best effort — nem toda fonte
 * deixa, ver JobDescriptionService) e o currículo/perfil do candidato quando
 * o usuário salvou um em Configurações (enviado pelo frontend, nunca
 * persistido no backend). O campo extraContext deixa colar mais detalhes
 * pontuais pra essa vaga específica, por cima disso tudo.
 */
@Service
@RequiredArgsConstructor
public class CoverLetterService {

    private final GeminiService geminiService;
    private final JobDescriptionService jobDescriptionService;

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

        String descricao = jobDescriptionService.fetchDescription(job.getUrl());
        if (descricao != null) {
            contexto.append("Descrição completa da vaga (extraída da página real):\n").append(descricao).append("\n");
        }

        if (extraContext != null && !extraContext.isBlank()) {
            contexto.append("Contexto adicional/perfil fornecido pelo candidato:\n").append(extraContext).append("\n");
        }

        String prompt = """
                Escreva uma carta de apresentação curta e direta (máximo 4 parágrafos)
                em português do Brasil, em primeira pessoa, para uma candidatura à
                vaga abaixo. Tom profissional mas natural, sem clichês genéricos tipo
                "sou apaixonado por tecnologia" — foque em conectar o perfil às
                informações concretas da vaga (se houver descrição completa, use os
                requisitos/responsabilidades reais dela, não só o título). Não invente
                experiências, certificações ou anos de carreira que não foram
                informados: se não houver dados suficientes sobre o histórico do
                candidato, mantenha o texto focado no interesse genuíno pela
                vaga/empresa/stack e peça a oportunidade de uma conversa, sem inventar
                trajetória profissional.

                %s

                Devolva só o texto da carta, sem markdown, sem título, sem aspas ao redor.
                """.formatted(contexto);

        return geminiService.generate(prompt);
    }
}

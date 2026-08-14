package br.com.jobradar.dto;

import br.com.jobradar.model.Job;
import br.com.jobradar.service.SeniorityClassifier;

import java.util.Arrays;
import java.util.List;

/**
 * Fase 6.4 — formaliza o formato de {@code GET /api/jobs} como um record
 * tipado em vez do {@code Map<String,Object>} montado à mão que existia
 * antes ({@code JobController.toDto}). Mesmo shape de campo por campo (zero
 * mudança de contrato pro frontend) — o ganho é não depender de string
 * solta ("title", "companyLogoUrl"...) espalhada pelo código: um campo novo
 * (ou removido) aqui quebra a compilação em vez de silenciosamente sumir
 * ou aparecer como {@code null} numa resposta JSON.
 *
 * <p>Não é uma projeção JPA (não evita carregar os campos do {@link Job} —
 * isso já foi resolvido na Fase 6.1 tirando o embedding, o único campo
 * realmente pesado). É só o formato de SAÍDA formalizado.</p>
 */
public record JobListItemDto(
        Long id,
        String title,
        String company,
        String url,
        String source,
        String seniority,
        String salary,
        String workplaceType,
        String state,
        String city,
        String postedAt,
        String expiresAt,
        String fetchedAt,
        boolean seen,
        boolean interested,
        boolean applied,
        String appliedAt,
        boolean inProgress,
        String inProgressAt,
        boolean rejected,
        String rejectedAt,
        List<String> tags,
        String companyLogoUrl,
        boolean pcd,
        boolean pinned,
        String notes,
        boolean classifiedByAi
) {
    public static JobListItemDto de(Job job) {
        return new JobListItemDto(
                job.getId(),
                job.getTitle(),
                job.getCompany(),
                job.getUrl(),
                job.getSource(),
                job.getSeniority() != null ? job.getSeniority() : SeniorityClassifier.NAO_INFORMADO,
                job.getSalary(),
                job.getWorkplaceType(),
                job.getState(),
                job.getCity(),
                job.getPostedAt() != null ? job.getPostedAt().toString() : null,
                job.getExpiresAt() != null ? job.getExpiresAt().toString() : null,
                job.getFetchedAt() != null ? job.getFetchedAt().toString() : null,
                job.isSeen(),
                job.isInterested(),
                job.isApplied(),
                job.getAppliedAt() != null ? job.getAppliedAt().toString() : null,
                job.isInProgress(),
                job.getInProgressAt() != null ? job.getInProgressAt().toString() : null,
                job.isRejected(),
                job.getRejectedAt() != null ? job.getRejectedAt().toString() : null,
                job.getTags() != null ? Arrays.asList(job.getTags().split(",")) : List.of(),
                job.getCompanyLogoUrl(),
                job.getPcd() != null && job.getPcd(),
                job.getFavorited() != null && job.getFavorited(),
                job.getNotes(),
                job.getClassifiedByAi() != null && job.getClassifiedByAi()
        );
    }
}

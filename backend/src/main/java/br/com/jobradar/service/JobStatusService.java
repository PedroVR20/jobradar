package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.model.JobEvent;
import br.com.jobradar.repository.JobEventRepository;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Ponto único que traduz um status "lógico" (NOVA/VISTA/INTERESSADO/APLICADA/
 * ANDAMENTO/RECUSADA) para os campos booleanos da entidade Job. Extraído de
 * JobController (que fazia isso inline como método privado) pra poder ser
 * reaproveitado pelo Hunter também — a ferramenta marcarStatusDeVaga do chat
 * (ver JarvisChatService) precisa exatamente da mesma regra, senão um jeito
 * de mudar status via UI e outro via chat divergiam com o tempo.
 */
@Service
@RequiredArgsConstructor
public class JobStatusService {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;

    public static final List<String> VALID_STATUSES =
            List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA");

    // Fase 8.7 — motivo estruturado, só faz sentido em conjunto com
    // status=RECUSADA (ver Job.rejectedReason). PersonalRankingService usa
    // isso pra aprender só da dimensão certa (ex: recusa por SALARIO não
    // deveria penalizar a stack/senioridade/empresa da vaga).
    public static final List<String> VALID_REJECTED_REASONS =
            List.of("SALARIO", "LOCALIDADE", "SENIORIDADE", "STACK", "EMPRESA", "OUTRO");

    // "RECUSADA" NÃO força applied=true sozinho — antes forçava, assumindo que
    // toda recusa vem depois de uma candidatura de verdade, mas o usuário usa
    // "Recusada/congelada" também como "descartar/não tenho interesse" direto
    // de vagas nunca aplicadas (ex: limpar vagas antigas de anos atrás). Fica
    // com o applied que a vaga já tinha — true só se já era true antes.
    public void aplicarStatus(Job job, String status) {
        aplicarStatus(job, status, null);
    }

    public void aplicarStatus(Job job, String status, String rejectedReason) {
        String statusAntes = statusAtual(job);

        boolean applied = status.equals("APLICADA") || status.equals("ANDAMENTO")
                || (status.equals("RECUSADA") && job.isApplied());
        boolean inProgress = status.equals("ANDAMENTO");

        job.setSeen(!status.equals("NOVA"));
        job.setInterested(status.equals("INTERESSADO"));
        job.setApplied(applied);
        if (applied && job.getAppliedAt() == null) {
            job.setAppliedAt(LocalDateTime.now());
        }
        job.setInProgress(inProgress);
        if (inProgress && job.getInProgressAt() == null) {
            job.setInProgressAt(LocalDateTime.now());
        }
        job.setRejected(status.equals("RECUSADA"));
        job.setRejectedAt(status.equals("RECUSADA") ? LocalDateTime.now() : null);
        // Só grava motivo válido; sai da recusa (qualquer outro status) limpa
        // o motivo antigo — não faz sentido carregar "recusei por SALARIO"
        // numa vaga que voltou a ser NOVA/INTERESSADO depois.
        job.setRejectedReason(status.equals("RECUSADA") && rejectedReason != null && VALID_REJECTED_REASONS.contains(rejectedReason)
                ? rejectedReason : null);

        // Timeline de eventos (ver JobEvent) — só registra transição de
        // verdade (status mudou), e só pra vaga já persistida (id != null):
        // o cadastro manual monta o Job novo e chama aplicarStatus ANTES do
        // primeiro save, não tem FK válida ainda pra esse caso específico.
        if (job.getId() != null && !statusAntes.equals(status)) {
            jobEventRepository.save(JobEvent.builder()
                    .job(job)
                    .status(status)
                    .occurredAt(LocalDateTime.now())
                    .build());
        }
    }

    // Mesma derivação de "statusDe" duplicada em JobController/JarvisChatService
    // — não vale extrair só por causa disso (5 linhas, 3 usos), mas registrado
    // aqui como comentário caso vire mais um lugar no futuro.
    private String statusAtual(Job j) {
        if (j.isRejected()) return "RECUSADA";
        if (j.isInProgress()) return "ANDAMENTO";
        if (j.isApplied()) return "APLICADA";
        if (j.isInterested()) return "INTERESSADO";
        if (j.isSeen()) return "VISTA";
        return "NOVA";
    }

    /** Aplica o status e já salva — atalho usado pela ferramenta do Hunter. */
    public Job aplicarEsalvar(Job job, String status) {
        aplicarStatus(job, status);
        return jobRepository.save(job);
    }
}

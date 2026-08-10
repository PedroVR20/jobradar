package br.com.jobradar.service;

import br.com.jobradar.model.Job;
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

    public static final List<String> VALID_STATUSES =
            List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA");

    // "RECUSADA" NÃO força applied=true sozinho — antes forçava, assumindo que
    // toda recusa vem depois de uma candidatura de verdade, mas o usuário usa
    // "Recusada/congelada" também como "descartar/não tenho interesse" direto
    // de vagas nunca aplicadas (ex: limpar vagas antigas de anos atrás). Fica
    // com o applied que a vaga já tinha — true só se já era true antes.
    public void aplicarStatus(Job job, String status) {
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
    }

    /** Aplica o status e já salva — atalho usado pela ferramenta do Hunter. */
    public Job aplicarEsalvar(Job job, String status) {
        aplicarStatus(job, status);
        return jobRepository.save(job);
    }
}

package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Primeiro teste automatizado do projeto (histórico: zero testes até aqui).
 * aplicarStatus é o ponto único que traduz status "lógico" pros campos
 * booleanos do Job — usado tanto pelo botão "mover pra outra aba" da UI
 * quanto pela ferramenta marcarStatusDeVaga do Hunter. Um teste aqui cobre
 * os dois caminhos de uma vez.
 */
class JobStatusServiceTest {

    // aplicarStatus só grava evento (JobEventRepository) pra Job já
    // persistido (id != null) — os fixtures do teste nunca têm id, então dá
    // pra testar sem mock nenhum dos dois repositórios.
    private final JobStatusService service = new JobStatusService(null, null);

    private Job novaVaga() {
        return Job.builder().title("Dev").company("Acme").url("https://x/1").source("MANUAL").build();
    }

    @Test
    void aplicarStatusNova_zeraTudo() {
        Job job = novaVaga();
        job.setSeen(true);
        job.setApplied(true);

        service.aplicarStatus(job, "NOVA");

        assertThat(job.isSeen()).isFalse();
        assertThat(job.isInterested()).isFalse();
        assertThat(job.isApplied()).isFalse();
        assertThat(job.isInProgress()).isFalse();
        assertThat(job.isRejected()).isFalse();
    }

    @Test
    void aplicarStatusAplicada_marcaAppliedEDataDaPrimeiraVez() {
        Job job = novaVaga();

        service.aplicarStatus(job, "APLICADA");

        assertThat(job.isApplied()).isTrue();
        assertThat(job.isSeen()).isTrue();
        assertThat(job.getAppliedAt()).isNotNull();
    }

    @Test
    void aplicarStatusAplicada_naoSobrescreveAppliedAtJaExistente() {
        Job job = novaVaga();
        var primeiraData = java.time.LocalDateTime.of(2026, 1, 1, 10, 0);
        job.setAppliedAt(primeiraData);
        job.setApplied(true);

        service.aplicarStatus(job, "APLICADA");

        assertThat(job.getAppliedAt()).isEqualTo(primeiraData);
    }

    @Test
    void aplicarStatusRecusada_naoForcaAppliedSeNuncaAplicou() {
        // Regressão intencional: "Recusada/congelada" também é usada como
        // "descartar" direto de uma vaga nunca aplicada — não pode inflar
        // métricas de candidatura contando isso como aplicação de verdade.
        Job job = novaVaga();

        service.aplicarStatus(job, "RECUSADA");

        assertThat(job.isRejected()).isTrue();
        assertThat(job.isApplied()).isFalse();
    }

    @Test
    void aplicarStatusRecusada_mantemAppliedSeJaEstavaAplicada() {
        Job job = novaVaga();
        job.setApplied(true);

        service.aplicarStatus(job, "RECUSADA");

        assertThat(job.isRejected()).isTrue();
        assertThat(job.isApplied()).isTrue();
    }

    @Test
    void aplicarStatusAndamento_marcaInProgressEApplied() {
        Job job = novaVaga();

        service.aplicarStatus(job, "ANDAMENTO");

        assertThat(job.isInProgress()).isTrue();
        assertThat(job.isApplied()).isTrue();
        assertThat(job.getInProgressAt()).isNotNull();
    }
}

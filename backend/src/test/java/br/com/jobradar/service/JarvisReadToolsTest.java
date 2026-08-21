package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fase 14.3 — separado de JarvisChatServiceTest quando as ferramentas de
 * leitura saíram pra {@link JarvisReadTools}. Cobre os dois bugs reais já
 * reportados nessa área (ver git log): o "-21 aplicadas" (statusBate/
 * resumoFunil contando errado) e o Hunter usando a ferramenta errada pra
 * "vagas que estão paradas". statusBate/contemBusca são puras, e
 * executarVagasParadas só depende do JobRepository (mockado aqui, não
 * precisa de banco de verdade).
 */
class JarvisReadToolsTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    // Os outros colaboradores (compatibilidade/salário/carta/embedding/email)
    // não são tocados pelos testes abaixo — null é seguro aqui, mesmo padrão
    // que JarvisChatServiceTest já usava antes da Fase 14.3.
    private final JarvisReadTools readTools =
            new JarvisReadTools(jobRepository, null, null, null, null, null, null);

    private Job job(String status) {
        Job j = Job.builder().title("Dev Java").company("Acme").url("https://x/" + status).source("MANUAL").build();
        switch (status) {
            case "NOVA" -> { }
            case "VISTA" -> j.setSeen(true);
            case "INTERESSADO" -> { j.setSeen(true); j.setInterested(true); }
            case "APLICADA" -> { j.setSeen(true); j.setApplied(true); }
            case "ANDAMENTO" -> { j.setSeen(true); j.setApplied(true); j.setInProgress(true); }
            case "RECUSADA" -> j.setRejected(true);
            default -> throw new IllegalArgumentException(status);
        }
        return j;
    }

    @Test
    void statusBate_cadaVagaSoBateComOSeuProprioStatus() {
        String[] statuses = {"NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA"};
        for (String dono : statuses) {
            Job j = job(dono);
            for (String candidato : statuses) {
                boolean esperado = dono.equals(candidato);
                assertThat(readTools.statusBate(j, candidato))
                        .as("vaga com status real %s comparada contra filtro %s", dono, candidato)
                        .isEqualTo(esperado);
            }
        }
    }

    @Test
    void statusBate_semFiltroAceitaQualquerStatus() {
        assertThat(readTools.statusBate(job("RECUSADA"), null)).isTrue();
    }

    @Test
    void contemBusca_procuraEmTituloEmpresaETags() {
        Job j = job("NOVA");
        j.setTags("java,spring-boot");

        assertThat(readTools.contemBusca(j, "java")).isTrue();
        assertThat(readTools.contemBusca(j, "acme")).isTrue();
        assertThat(readTools.contemBusca(j, "python")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void executarVagasParadas_soConsideraAplicadaOuAndamentoAcimaDoLimite() {
        Job paradaHaMuito = job("APLICADA");
        paradaHaMuito.setAppliedAt(LocalDateTime.now().minusDays(30));

        Job aplicadaRecente = job("APLICADA");
        aplicadaRecente.setAppliedAt(LocalDateTime.now().minusDays(1));

        // recusadaAntiga NÃO entra na lista mockada — na Fase 14.1 o filtro
        // "aplicada e não recusada" virou WHERE de SQL (ver
        // JobSpecifications.appliedNaoRejeitada), então o banco de verdade
        // nunca devolveria essa vaga pro método de qualquer forma.
        when(jobRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(paradaHaMuito, aplicadaRecente));

        Map<String, Object> resultado = (Map<String, Object>) readTools.executarVagasParadas(Map.of("diasMinimo", 10));
        List<?> vagas = (List<?>) resultado.get("vagas");

        assertThat(vagas).hasSize(1);
    }
}

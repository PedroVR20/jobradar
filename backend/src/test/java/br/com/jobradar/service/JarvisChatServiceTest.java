package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Cobre os dois bugs reais já reportados nessa área (ver git log): o "-21
 * aplicadas" (statusBate/resumoFunil contando errado) e o Hunter usando a
 * ferramenta errada pra "vagas que estão paradas". Só as duas ferramentas
 * mais fáceis de isolar sem montar toda a cadeia de function-calling do
 * Gemini — statusBate/contemBusca são puras, e executarVagasParadas só
 * depende do JobRepository (mockado aqui, não precisa de banco de verdade).
 */
class JarvisChatServiceTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    // statusBate/contemBusca/executarVagasParadas/dispatch não tocam nos
    // outros serviços injetados — null é seguro aqui.
    private final JarvisChatService service =
            new JarvisChatService(null, jobRepository, null, null, null, null, null, null, null);

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
                assertThat(service.statusBate(j, candidato))
                        .as("vaga com status real %s comparada contra filtro %s", dono, candidato)
                        .isEqualTo(esperado);
            }
        }
    }

    @Test
    void statusBate_semFiltroAceitaQualquerStatus() {
        assertThat(service.statusBate(job("RECUSADA"), null)).isTrue();
    }

    @Test
    void contemBusca_procuraEmTituloEmpresaETags() {
        Job j = job("NOVA");
        j.setTags("java,spring-boot");

        assertThat(service.contemBusca(j, "java")).isTrue();
        assertThat(service.contemBusca(j, "acme")).isTrue();
        assertThat(service.contemBusca(j, "python")).isFalse();
    }

    @SuppressWarnings("unchecked")
    @Test
    void executarVagasParadas_soConsideraAplicadaOuAndamentoAcimaDoLimite() {
        Job paradaHaMuito = job("APLICADA");
        paradaHaMuito.setAppliedAt(LocalDateTime.now().minusDays(30));

        Job aplicadaRecente = job("APLICADA");
        aplicadaRecente.setAppliedAt(LocalDateTime.now().minusDays(1));

        Job recusadaAntiga = job("RECUSADA"); // nunca deve contar como "parada"

        when(jobRepository.findAll()).thenReturn(List.of(paradaHaMuito, aplicadaRecente, recusadaAntiga));

        Map<String, Object> resultado = (Map<String, Object>) service.executarVagasParadas(Map.of("diasMinimo", 10));
        List<?> vagas = (List<?>) resultado.get("vagas");

        assertThat(vagas).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void executarFerramenta_dispatchaApagarVagaEDevolveErroSemQuebrar() {
        when(jobRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        var chamada = new GeminiService.FunctionCallRequest("apagarVaga", Map.of("vagaId", 999), null);
        Object resultado = service.executarFerramenta(chamada, null, null);

        assertThat(resultado).isInstanceOf(Map.class);
        Map<String, Object> mapa = (Map<String, Object>) resultado;
        assertThat(mapa).containsKey("erro");
    }

    @Test
    void tentarFastPath_respondeResumoDoFunilSemChamarGemini() {
        when(jobRepository.findAll()).thenReturn(List.of(job("NOVA"), job("APLICADA"), job("RECUSADA")));

        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("Quantas vagas eu tenho?", toolName -> { });

        assertThat(resultado).isNotNull();
        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.reply()).contains("3 vagas no total");
        assertThat(resultado.toolResults()).hasSize(1);
        assertThat(resultado.toolResults().get(0).tool()).isEqualTo("resumoFunil");
    }

    @Test
    void tentarFastPath_naoDisparaEmPerguntaComContextoExtra() {
        // "quantas vagas eu tenho no Itaú" não é a mesma pergunta que
        // "quantas vagas eu tenho" — precisa cair no LLM normal, não no
        // fast-path (que só bate em correspondência EXATA da frase inteira).
        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("quantas vagas eu tenho no Itaú", toolName -> { });

        assertThat(resultado).isNull();
    }

    @Test
    void tentarFastPath_ignoraAcentuacaoEPontuacao() {
        when(jobRepository.findAll()).thenReturn(List.of(job("NOVA")));

        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("Resumo do meu funil!", toolName -> { });

        assertThat(resultado).isNotNull();
        assertThat(resultado.reply()).contains("1 vagas no total");
    }
}

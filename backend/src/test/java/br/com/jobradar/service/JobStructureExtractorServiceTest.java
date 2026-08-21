package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Fase 9.4 — cobre o cache por vaga (o ponto central do design: nunca gasta
 * uma segunda chamada de IA na mesma vaga), a falta de descrição buscável, e
 * o parse de uma resposta real do Gemini.
 */
class JobStructureExtractorServiceTest {

    private final GeminiService geminiService = mock(GeminiService.class);
    private final JobDescriptionService jobDescriptionService = mock(JobDescriptionService.class);
    private final AiFeatureBudgetService budgetService = mock(AiFeatureBudgetService.class);
    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobStructureExtractorService service =
            new JobStructureExtractorService(geminiService, jobDescriptionService, budgetService, jobRepository);

    private Job job() {
        return Job.builder().id(1L).title("Dev Java Pleno").company("Acme").url("https://x/1").source("MANUAL").build();
    }

    @Test
    void vagaJaExtraidaAntes_devolveDoCacheSemChamarGeminiOuBuscarDescricao() {
        Job j = job();
        j.setEstruturaExtraidaEm(java.time.LocalDateTime.now());
        j.setRequisitosObrigatorios("java ||| spring");
        j.setRequisitosDesejaveis("docker");
        j.setAnosExperienciaMin(3);
        j.setEscolaridadeRequerida("Superior completo");
        j.setBeneficios("vale refeicao ||| plano de saude");
        // sinal de alerta com vírgula DE PROPÓSITO — é exatamente o caso que
        // motivou trocar o separador de vírgula pra " ||| " (ver comentário
        // em JobStructureExtractorService.SEPARADOR): uma frase gerada pelo
        // Gemini pode conter vírgula, e vírgula-como-separador corromperia
        // o round-trip.
        j.setSinaisAlerta("Salário omitido, apesar do título sugerir liderança");

        JobStructureExtractorService.ExtractOutcome outcome = service.extrair(j);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.cacheHit()).isTrue();
        assertThat(outcome.estrutura().requisitosObrigatorios()).containsExactly("java", "spring");
        assertThat(outcome.estrutura().anosExperienciaMin()).isEqualTo(3);
        assertThat(outcome.estrutura().sinaisAlerta()).containsExactly("Salário omitido, apesar do título sugerir liderança");
        org.mockito.Mockito.verifyNoInteractions(geminiService, jobDescriptionService, budgetService);
    }

    @Test
    void semDescricaoBuscavel_devolveErroSemGastarOrcamento() {
        when(jobDescriptionService.fetchDescription(anyString())).thenReturn(null);

        JobStructureExtractorService.ExtractOutcome outcome = service.extrair(job());

        assertThat(outcome.ok()).isFalse();
        org.mockito.Mockito.verifyNoInteractions(budgetService, geminiService);
    }

    @Test
    void orcamentoEsgotado_devolveMensagemDoBudgetService() {
        when(jobDescriptionService.fetchDescription(anyString())).thenReturn("Requisitos: Java, Spring. Diferencial: Docker.");
        when(budgetService.permitir(AiFeatureBudgetService.STRUCTURE_EXTRACT)).thenReturn(false);
        when(budgetService.mensagemLimiteAtingido(AiFeatureBudgetService.STRUCTURE_EXTRACT)).thenReturn("orçamento esgotado");

        JobStructureExtractorService.ExtractOutcome outcome = service.extrair(job());

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).isEqualTo("orçamento esgotado");
    }

    @Test
    void respostaValida_extraiEPersisteNaVaga() {
        when(jobDescriptionService.fetchDescription(anyString())).thenReturn("Requisitos: Java, Spring. Diferencial: Docker. 3 anos de experiência. Superior completo.");
        when(budgetService.permitir(AiFeatureBudgetService.STRUCTURE_EXTRACT)).thenReturn(true);
        when(geminiService.generate(anyString())).thenReturn(new GeminiService.GeminiResult(
                """
                {
                  "requisitosObrigatorios": ["Java", "Spring"],
                  "requisitosDesejaveis": ["Docker"],
                  "anosExperienciaMin": 3,
                  "escolaridadeRequerida": "Superior completo",
                  "beneficios": ["Vale refeição", "Plano de saúde"],
                  "sinaisAlerta": ["Salário não informado apesar do título de \\"líder técnico\\""]
                }
                """, null, false));

        Job j = job();
        JobStructureExtractorService.ExtractOutcome outcome = service.extrair(j);

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.cacheHit()).isFalse();
        assertThat(outcome.estrutura().requisitosObrigatorios()).containsExactly("Java", "Spring");
        assertThat(outcome.estrutura().anosExperienciaMin()).isEqualTo(3);
        assertThat(outcome.estrutura().escolaridadeRequerida()).isEqualTo("Superior completo");
        assertThat(outcome.estrutura().beneficios()).containsExactly("Vale refeição", "Plano de saúde");
        assertThat(outcome.estrutura().sinaisAlerta()).containsExactly("Salário não informado apesar do título de \"líder técnico\"");

        // Persistiu no próprio Job, marcando o cache pra sempre.
        assertThat(j.getEstruturaExtraidaEm()).isNotNull();
        assertThat(j.getRequisitosObrigatorios()).isEqualTo("Java ||| Spring");
        verify(jobRepository).save(j);
    }

    @Test
    void sinalDeAlertaComVirgula_naoQuebraORoundTripAposSalvar() {
        // Reproduz o bug que o separador " ||| " existe pra evitar: se o
        // Gemini devolver uma frase com vírgula (comum em linguagem natural)
        // e o código juntasse/separasse por vírgula, essa MESMA vaga lida de
        // novo do banco (cache hit) devolveria a frase quebrada em pedaços.
        when(jobDescriptionService.fetchDescription(anyString())).thenReturn("descricao qualquer");
        when(budgetService.permitir(AiFeatureBudgetService.STRUCTURE_EXTRACT)).thenReturn(true);
        when(geminiService.generate(anyString())).thenReturn(new GeminiService.GeminiResult(
                """
                {"requisitosObrigatorios": [], "requisitosDesejaveis": [], "anosExperienciaMin": null,
                 "escolaridadeRequerida": null, "beneficios": [],
                 "sinaisAlerta": ["Escopo maior que o nível pedido, incluindo gestão de equipe"]}
                """, null, false));

        Job j = job();
        service.extrair(j);

        // Simula reler a MESMA vaga do banco (cache hit) — o valor que foi
        // persistido precisa reconstruir a frase original inteira, não
        // quebrada na vírgula interna dela.
        JobStructureExtractorService.ExtractOutcome cached = service.extrair(j);
        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.estrutura().sinaisAlerta()).containsExactly("Escopo maior que o nível pedido, incluindo gestão de equipe");
    }
}

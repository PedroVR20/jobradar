package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fase 9.1 — cobre os casos de borda da pré-triagem em lote: perfil vazio,
 * lote vazio (não gasta orçamento nem chama o Gemini à toa), orçamento
 * esgotado, resposta malformada, e o caso real que motivou o comentário
 * sobre ordem no service — o Gemini pode devolver o array fora de ordem ou
 * pular uma vaga, e o resultado ainda precisa vir na ordem do lote pedido.
 */
class AiTriageServiceTest {

    private final GeminiService geminiService = mock(GeminiService.class);
    private final AiFeatureBudgetService budgetService = mock(AiFeatureBudgetService.class);
    private final AiTriageService service = new AiTriageService(geminiService, budgetService);

    private Job job(long id, String title) {
        return Job.builder().id(id).title(title).company("Acme").url("https://x/" + id).source("MANUAL").build();
    }

    @Test
    void perfilVazio_naoChamaGeminiNemGastaOrcamento() {
        AiTriageService.TriageOutcome outcome = service.triarLote(List.of(job(1, "Dev")), "  ");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).contains("Configurações");
        org.mockito.Mockito.verifyNoInteractions(geminiService, budgetService);
    }

    @Test
    void loteVazio_devolveListaVaziaSemChamarGemini() {
        AiTriageService.TriageOutcome outcome = service.triarLote(List.of(), "Java, Spring, 5 anos de experiência");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.veredictos()).isEmpty();
        org.mockito.Mockito.verifyNoInteractions(geminiService, budgetService);
    }

    @Test
    void orcamentoEsgotado_devolveMensagemDoBudgetService() {
        when(budgetService.permitir(AiFeatureBudgetService.BATCH_TRIAGE)).thenReturn(false);
        when(budgetService.mensagemLimiteAtingido(AiFeatureBudgetService.BATCH_TRIAGE)).thenReturn("orçamento esgotado hoje");

        AiTriageService.TriageOutcome outcome = service.triarLote(List.of(job(1, "Dev")), "Java");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).isEqualTo("orçamento esgotado hoje");
    }

    @Test
    void respostaValida_devolveVereditosNaOrdemDoLotePedido() {
        when(budgetService.permitir(AiFeatureBudgetService.BATCH_TRIAGE)).thenReturn(true);
        // Resposta do Gemini fora de ordem de propósito (id=2 antes de id=1)
        // — o resultado ainda precisa sair na ordem do lote (1, 2, 3).
        when(geminiService.generate(anyString())).thenReturn(new GeminiService.GeminiResult(
                """
                [
                  {"id": 2, "veredito": "DESCARTAR", "motivo": "Senioridade incompatível"},
                  {"id": 1, "veredito": "RECOMENDADA", "motivo": "Stack bate 100%"}
                ]
                """, null, false));

        AiTriageService.TriageOutcome outcome = service.triarLote(
                List.of(job(1, "Dev Java Pleno"), job(2, "Dev Senior .NET"), job(3, "Dev Python")),
                "Java, Spring, 5 anos de experiência");

        assertThat(outcome.ok()).isTrue();
        List<AiTriageService.TriageVerdict> v = outcome.veredictos();
        assertThat(v).hasSize(3);
        assertThat(v.get(0).jobId()).isEqualTo(1L);
        assertThat(v.get(0).veredito()).isEqualTo(AiTriageService.Veredito.RECOMENDADA);
        assertThat(v.get(1).jobId()).isEqualTo(2L);
        assertThat(v.get(1).veredito()).isEqualTo(AiTriageService.Veredito.DESCARTAR);
        // Vaga 3 não veio na resposta do Gemini — cai pra TALVEZ (incerto),
        // não quebra o lote inteiro por causa de uma vaga faltando.
        assertThat(v.get(2).jobId()).isEqualTo(3L);
        assertThat(v.get(2).veredito()).isEqualTo(AiTriageService.Veredito.TALVEZ);
    }

    @Test
    void respostaMalformada_devolveErroGenericoSemQuebrar() {
        when(budgetService.permitir(AiFeatureBudgetService.BATCH_TRIAGE)).thenReturn(true);
        when(geminiService.generate(anyString())).thenReturn(new GeminiService.GeminiResult("isso não é JSON", null, false));

        AiTriageService.TriageOutcome outcome = service.triarLote(List.of(job(1, "Dev")), "Java");

        assertThat(outcome.ok()).isFalse();
        assertThat(outcome.errorMessage()).isNotBlank();
    }

    @Test
    void loteMaiorQueOLimite_truncaSemQuebrar() {
        when(budgetService.permitir(AiFeatureBudgetService.BATCH_TRIAGE)).thenReturn(true);
        when(geminiService.generate(anyString())).thenReturn(new GeminiService.GeminiResult("[]", null, false));

        List<Job> muitasVagas = java.util.stream.IntStream.rangeClosed(1, AiTriageService.MAX_LOTE + 10)
                .mapToObj(i -> job(i, "Dev " + i))
                .toList();

        AiTriageService.TriageOutcome outcome = service.triarLote(muitasVagas, "Java");

        assertThat(outcome.ok()).isTrue();
        assertThat(outcome.veredictos()).hasSize(AiTriageService.MAX_LOTE);
    }
}

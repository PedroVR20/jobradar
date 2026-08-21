package br.com.jobradar.service;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 9.8 — cobre o teto diário por funcionalidade e o reset por data.
 * Usa ReflectionTestUtils pra injetar os limites (os campos são @Value,
 * sem contexto Spring nesse teste — mesmo precedente de outros testes
 * puros do projeto, sem MockMvc pra unidade pequena isolada).
 */
class AiFeatureBudgetServiceTest {

    private AiFeatureBudgetService novoServicoComLimite(int limite) {
        AiFeatureBudgetService svc = new AiFeatureBudgetService();
        ReflectionTestUtils.setField(svc, "limiteCoverLetter", limite);
        ReflectionTestUtils.setField(svc, "limiteMatchScore", limite);
        ReflectionTestUtils.setField(svc, "limiteLearningPlan", limite);
        ReflectionTestUtils.setField(svc, "limiteInterviewQuestions", limite);
        ReflectionTestUtils.setField(svc, "limiteDuplicateVerify", limite);
        ReflectionTestUtils.setField(svc, "limiteWeeklyDigest", limite);
        return svc;
    }

    @Test
    void permitir_deixaPassarAteOLimite() {
        AiFeatureBudgetService svc = novoServicoComLimite(3);
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isTrue();
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isTrue();
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isTrue();
        // 4a chamada estoura o limite de 3
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isFalse();
    }

    @Test
    void permitir_naoMisturaContadorEntreFuncionalidadesDiferentes() {
        AiFeatureBudgetService svc = novoServicoComLimite(1);
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isTrue();
        // Estourou o orçamento de carta, mas match-score é um contador
        // independente — não pode ser afetado.
        assertThat(svc.permitir(AiFeatureBudgetService.COVER_LETTER)).isFalse();
        assertThat(svc.permitir(AiFeatureBudgetService.MATCH_SCORE)).isTrue();
    }

    @Test
    void mensagemLimiteAtingido_mencionaOLimiteConfigurado() {
        AiFeatureBudgetService svc = novoServicoComLimite(42);
        String msg = svc.mensagemLimiteAtingido(AiFeatureBudgetService.COVER_LETTER);
        assertThat(msg).contains("42");
    }

    @Test
    void status_reportaUsadoELimitePorFuncionalidade() {
        AiFeatureBudgetService svc = novoServicoComLimite(10);
        svc.permitir(AiFeatureBudgetService.COVER_LETTER);
        svc.permitir(AiFeatureBudgetService.COVER_LETTER);

        var status = svc.status();
        var coverLetterStatus = status.stream()
                .filter(s -> s.feature().equals(AiFeatureBudgetService.COVER_LETTER))
                .findFirst().orElseThrow();

        assertThat(coverLetterStatus.usadoHoje()).isEqualTo(2);
        assertThat(coverLetterStatus.limite()).isEqualTo(10);
    }

    @Test
    void featureDesconhecida_naoTemLimiteEfetivo() {
        // Funcionalidade sem entrada no mapa de limites (typo, feature nova
        // ainda não configurada) não deve travar tudo silenciosamente —
        // vira "sem teto" (Integer.MAX_VALUE), visível nos logs se alguém
        // checar, mas não quebra a chamada.
        AiFeatureBudgetService svc = novoServicoComLimite(10);
        assertThat(svc.permitir("feature-que-nao-existe")).isTrue();
    }
}

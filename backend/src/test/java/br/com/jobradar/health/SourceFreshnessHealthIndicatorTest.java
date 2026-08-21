package br.com.jobradar.health;

import br.com.jobradar.repository.FonteSaudeProjection;
import br.com.jobradar.repository.JobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fase 15.5 — cobre o critério de "fonte parada" (mais de 10 dias sem vaga
 * nova) e o caso que não deve disparar (fonte sem NENHUMA vaga ainda, que é
 * "nunca trouxe", não "parou de trazer"). FonteSaudeProjection é implementada
 * direto como record (não mockada) — é só um carregador de dados sem
 * comportamento, um dublê real é mais simples e mais confiável aqui do que
 * stubar getter por getter.
 */
class SourceFreshnessHealthIndicatorTest {

    private record FakeProjection(String source, Long total, Long comSalario, Long comEstado,
                                   LocalDateTime vagaMaisRecente, LocalDateTime ultimoFetch)
            implements FonteSaudeProjection {
        @Override public String getSource() { return source; }
        @Override public Long getTotal() { return total; }
        @Override public Long getComSalario() { return comSalario; }
        @Override public Long getComEstado() { return comEstado; }
        @Override public LocalDateTime getVagaMaisRecente() { return vagaMaisRecente; }
        @Override public LocalDateTime getUltimoFetch() { return ultimoFetch; }
    }

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final SourceFreshnessHealthIndicator indicator = new SourceFreshnessHealthIndicator(jobRepository);

    private FonteSaudeProjection projection(String source, LocalDateTime vagaMaisRecente) {
        return new FakeProjection(source, 10L, 5L, 8L, vagaMaisRecente, vagaMaisRecente);
    }

    @Test
    void todasFontesRecentes_statusUp() {
        when(jobRepository.saudeDasFontes()).thenReturn(List.of(
                projection("GUPY", LocalDateTime.now().minusDays(1)),
                projection("REMOTIVE", LocalDateTime.now().minusDays(5))
        ));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
        assertThat((List<?>) health.getDetails().get("fontesDegradadas")).isEmpty();
    }

    @Test
    void fonteSemVagaNovaHaMaisDe10Dias_statusDegraded() {
        when(jobRepository.saudeDasFontes()).thenReturn(List.of(
                projection("GUPY", LocalDateTime.now().minusDays(1)),
                projection("NERDIN", LocalDateTime.now().minusDays(15))
        ));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> degradadas = (List<Map<String, Object>>) health.getDetails().get("fontesDegradadas");
        assertThat(degradadas).hasSize(1);
        assertThat(degradadas.get(0).get("fonte")).isEqualTo("NERDIN");
    }

    @Test
    void fonteSemNenhumaVagaAinda_naoContaComoDegradada() {
        // vagaMaisRecente == null: fonte nova que nunca trouxe nada ainda,
        // diferente de uma fonte que TRAZIA e parou — não é o mesmo alerta.
        when(jobRepository.saudeDasFontes()).thenReturn(List.of(
                projection("FONTE_NOVA", null)
        ));

        Health health = indicator.health();

        assertThat(health.getStatus().getCode()).isEqualTo("UP");
    }
}

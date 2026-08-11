package br.com.jobradar.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Só a matemática pura (similaridade de cosseno + serialização do vetor) —
 * a parte que fala com o Gemini de verdade não dá pra testar sem mock de
 * HTTP, fora de escopo pra um teste "mínimo".
 */
class JobEmbeddingServiceTest {

    @Test
    void similaridadeCosseno_vetoresIdenticosDaUm() {
        float[] a = {1f, 2f, 3f};
        assertThat(JobEmbeddingService.similaridadeCosseno(a, a)).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void similaridadeCosseno_vetoresOpostosDaMenosUm() {
        float[] a = {1f, 0f};
        float[] b = {-1f, 0f};
        assertThat(JobEmbeddingService.similaridadeCosseno(a, b)).isCloseTo(-1.0, within(1e-6));
    }

    @Test
    void similaridadeCosseno_vetoresOrtogonaisDaZero() {
        float[] a = {1f, 0f};
        float[] b = {0f, 1f};
        assertThat(JobEmbeddingService.similaridadeCosseno(a, b)).isCloseTo(0.0, within(1e-6));
    }

    @Test
    void serializarEParsear_fazemRoundTrip() {
        float[] original = {0.123f, -0.456f, 7.89f};
        String serializado = JobEmbeddingService.serializar(original);
        float[] resultado = JobEmbeddingService.parsear(serializado);

        assertThat(resultado).hasSize(original.length);
        for (int i = 0; i < original.length; i++) {
            assertThat(resultado[i]).isCloseTo(original[i], within(1e-5f));
        }
    }
}

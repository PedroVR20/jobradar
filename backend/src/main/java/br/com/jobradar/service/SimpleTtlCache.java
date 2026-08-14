package br.com.jobradar.service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Fase 3.4 — cache curto pra ferramentas determinísticas do Hunter
 * (resumoFunil, metricasDeDesempenho, desempenhoPorFonte) que recalculam do
 * zero (varrendo milhares de vagas) a cada pergunta, mesmo quando nada
 * mudou entre uma pergunta e outra na mesma conversa.
 *
 * <p>TTL curto (ver {@code TTL_PADRAO_SEGUNDOS}) em vez de invalidação
 * precisa amarrada a cada ponto de escrita (status de vaga muda em vários
 * lugares: {@code JobController}, {@code JobStatusService}, ferramentas de
 * escrita do próprio chat) — amarrar invalidação em todos esses pontos
 * seria mais código pra um ganho marginal, já que o usuário não fica
 * fazendo 2 perguntas por segundo pro Hunter. Um TTL de alguns segundos já
 * resolve o caso real (várias perguntas seguidas na mesma conversa) sem
 * risco prático de mostrar dado visivelmente desatualizado.</p>
 */
class SimpleTtlCache {

    private record Entrada(Object valor, Instant expiraEm) {}

    private final Map<String, Entrada> cache = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    <T> T getOuCalcula(String chave, int ttlSegundos, Supplier<T> calculo) {
        Entrada existente = cache.get(chave);
        if (existente != null && Instant.now().isBefore(existente.expiraEm())) {
            return (T) existente.valor();
        }
        T valor = calculo.get();
        cache.put(chave, new Entrada(valor, Instant.now().plusSeconds(ttlSegundos)));
        return valor;
    }
}

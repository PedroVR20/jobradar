package br.com.jobradar.repository;

/**
 * Fase 12.5 — projeção genérica pra qualquer {@code GROUP BY coluna, COUNT(*)}
 * simples. Reaproveitada por {@link JobRepository#contagemPorSource()} e
 * {@link JobRepository#contagemPorSenioridade()}, que antes eram 7 + 5
 * chamadas de {@code countBySource}/{@code countBySeniority} (uma por valor
 * fixo, em {@code JobController.getStats}) — 12 queries pra responder uma
 * pergunta que uma query só já responde. Mesmo raciocínio já registrado no
 * comentário de {@link JobRepository#saudeDasFontes()}: lista fixa de
 * valores não escala quando aparece uma fonte/senioridade nova.
 */
public interface ChaveContagemProjection {
    String getChave();
    Long getTotal();
}

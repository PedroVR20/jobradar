package br.com.jobradar.repository;

import java.time.LocalDateTime;

/**
 * Projeção do agregado por fonte usado no painel de saúde das fontes
 * (Fase 2.7, ver {@link JobRepository#saudeDasFontes()}). Os nomes dos
 * getters precisam bater (case-insensitive) com os apelidos ("AS ...") da
 * query JPQL — é assim que o Spring Data sabe mapear cada coluna agregada.
 */
public interface FonteSaudeProjection {
    String getSource();
    Long getTotal();
    Long getComSalario();
    Long getComEstado();
    LocalDateTime getVagaMaisRecente();
    LocalDateTime getUltimoFetch();
}

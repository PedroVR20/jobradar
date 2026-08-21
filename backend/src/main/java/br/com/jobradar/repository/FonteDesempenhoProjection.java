package br.com.jobradar.repository;

/**
 * Fase 14.1 — usada por {@link JobRepository#desempenhoPorFonte()}, que
 * substitui um {@code findAll()} do catálogo inteiro seguido de agrupamento
 * em Java (ferramenta "desempenhoPorFonte" do Hunter, ver JarvisChatService)
 * por um único {@code GROUP BY}. Mesmo raciocínio de
 * {@link ChaveContagemProjection}, com dois agregados a mais porque a
 * pergunta original não é só "quantas vagas por fonte" — é "de onde vêm as
 * vagas que realmente avançam" (total × aplicadas × em andamento).
 */
public interface FonteDesempenhoProjection {
    String getFonte();
    Long getTotal();
    Long getAplicadas();
    Long getEmAndamento();
}

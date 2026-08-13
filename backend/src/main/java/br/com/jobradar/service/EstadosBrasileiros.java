package br.com.jobradar.service;

import java.util.Map;

/**
 * Sigla (UF) → nome completo do estado, no mesmo formato que as fontes
 * estruturadas já usam no campo {@code Job.state} (ex: Gupy manda "São
 * Paulo" por extenso, não "SP") — usado por fontes que só trazem a sigla no
 * texto bruto, pra manter o valor consistente entre fontes diferentes
 * (senão o filtro de estado do app teria "São Paulo" de um lado e "SP" de
 * outro, e trataria como estados diferentes).
 */
final class EstadosBrasileiros {

    private EstadosBrasileiros() {}

    private static final Map<String, String> UF_PARA_NOME = Map.ofEntries(
            Map.entry("AC", "Acre"), Map.entry("AL", "Alagoas"), Map.entry("AP", "Amapá"),
            Map.entry("AM", "Amazonas"), Map.entry("BA", "Bahia"), Map.entry("CE", "Ceará"),
            Map.entry("DF", "Distrito Federal"), Map.entry("ES", "Espírito Santo"), Map.entry("GO", "Goiás"),
            Map.entry("MA", "Maranhão"), Map.entry("MT", "Mato Grosso"), Map.entry("MS", "Mato Grosso do Sul"),
            Map.entry("MG", "Minas Gerais"), Map.entry("PA", "Pará"), Map.entry("PB", "Paraíba"),
            Map.entry("PR", "Paraná"), Map.entry("PE", "Pernambuco"), Map.entry("PI", "Piauí"),
            Map.entry("RJ", "Rio de Janeiro"), Map.entry("RN", "Rio Grande do Norte"), Map.entry("RS", "Rio Grande do Sul"),
            Map.entry("RO", "Rondônia"), Map.entry("RR", "Roraima"), Map.entry("SC", "Santa Catarina"),
            Map.entry("SP", "São Paulo"), Map.entry("SE", "Sergipe"), Map.entry("TO", "Tocantins")
    );

    /** Devolve o nome completo pra uma sigla de 2 letras, ou {@code null} se não reconhecer. */
    static String nomeCompleto(String sigla) {
        if (sigla == null) return null;
        return UF_PARA_NOME.get(sigla.trim().toUpperCase());
    }
}

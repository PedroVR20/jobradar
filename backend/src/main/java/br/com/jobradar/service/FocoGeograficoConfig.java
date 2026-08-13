package br.com.jobradar.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.List;

/**
 * Fase 2.6 — antes só a Gupy tinha um {@code FOCO_ESTADO = "Rio de Janeiro"}
 * hardcoded (buscando os mesmos termos de novo com esse filtro de estado,
 * pra compensar a busca nacional cortar em 100 resultados por termo e
 * vaga do Rio ficar de fora do corte por relevância). Promovido pra
 * configuração compartilhada — qualquer fonte nova que suporte filtro
 * geográfico (Greenhouse por location, Adzuna por `where=`, SINE por
 * município) lê o MESMO estado configurado aqui, em vez de cada fonte ter
 * seu próprio hardcoded que pode divergir silenciosamente com o tempo.
 */
@Component
public class FocoGeograficoConfig {

    @Value("${app.foco.estados:Rio de Janeiro}")
    private String estadosCsv;

    /** Estados (por extenso, ex: "Rio de Janeiro") que merecem uma segunda passada de busca por fonte. */
    public List<String> estados() {
        return Arrays.stream(estadosCsv.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
    }

    /** Atalho pro caso comum de UM estado de foco só — devolve o primeiro, ou null se a lista estiver vazia. */
    public String primeiroEstado() {
        List<String> lista = estados();
        return lista.isEmpty() ? null : lista.get(0);
    }
}

package br.com.jobradar.dto;

import java.util.List;

/**
 * Fase 6.3 — resposta paginada de {@code GET /api/jobs}. Antes o endpoint
 * devolvia a lista inteira que batia no filtro (4,37 MB medidos sem filtro
 * nenhum, pra uma tela que mostra 30 por vez) — agora só a página pedida
 * viaja pela rede, com {@code totalElements} pro frontend saber quanto
 * ainda falta pro "carregar mais".
 */
public record JobPageResult(
        List<JobListItemDto> content,
        long totalElements,
        int page,
        int size,
        int totalPages
) {}

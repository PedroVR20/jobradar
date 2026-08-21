package br.com.jobradar.service;

import br.com.jobradar.model.Job;

import java.util.List;

/**
 * Contrato comum de toda fonte de vaga (Gupy, Remotive, Greenhouse, etc.) —
 * antes de existir essa interface, {@link JobAggregatorService#fetchAllJobs}
 * tinha 7 chamadas {@code allJobs.addAll(xService.fetchJobs())} hardcoded, e
 * adicionar uma fonte nova significava editar o agregador (Fase 2.5). Agora
 * o Spring injeta automaticamente TODO bean que implementa essa interface
 * numa {@code List<JobSource>} — adicionar fonte vira só criar a classe com
 * {@code @Service}, sem tocar em mais nada.
 *
 * <p>Contrato de falha: {@link #fetchJobs()} NUNCA deve lançar exceção pro
 * chamador — cada implementação já trata seus próprios erros internamente
 * (rede fora do ar, formato inesperado) e devolve lista vazia nesses casos,
 * só logando. O agregador ainda envolve a chamada num try/catch extra por
 * segurança, mas a fonte não deve depender disso.</p>
 */
public interface JobSource {

    /** Nome legível da fonte, usado em log e no painel de saúde (Fase 2.7) — ex: "Gupy", "Greenhouse". */
    String nome();

    /** Busca as vagas dessa fonte agora. Nunca lança — ver contrato de falha acima. */
    List<Job> fetchJobs();
}

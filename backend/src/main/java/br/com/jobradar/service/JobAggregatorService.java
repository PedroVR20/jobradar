package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobAggregatorService {

    private final JobRepository jobRepository;
    private final RemotiveService remotiveService;
    private final ArbeitnowService arbeitnowService;
    private final WorkRemotelyService workRemotelyService;
    private final GupyService gupyService;
    private final EurecaService eurecaService;
    private final QuerovagastechService querovagastechService;
    private final NerdinService nerdinService;
    private final SeniorityClassifier seniorityClassifier;

    /**
     * Roda automaticamente a cada 2 horas, sempre em hora cheia par
     * (00h, 02h, 04h, 06h, 08h, 10h, 12h, 14h, 16h, 18h, 20h, 22h BRT) —
     * antes era a cada 4h, reduzido pra pegar vagas novas mais rápido.
     */
    @Scheduled(cron = "0 0 */2 * * *", zone = "America/Sao_Paulo")
    @Transactional
    public void fetchPeriodico() {
        log.info("=== Fetch periódico iniciado ===");
        fetchAllJobs();
        limparVagasRecusadasAntigas();
        limparVagasAntigasNuncaEngajadas();
    }

    // Quantos dias uma vaga fica na aba "Recusadas" antes de ser apagada de vez.
    private static final int DIAS_PARA_EXCLUIR_RECUSADAS = 7;

    /**
     * Apaga permanentemente vagas marcadas como recusadas/congeladas há mais
     * de {@link #DIAS_PARA_EXCLUIR_RECUSADAS} dias, pra aba não acumular
     * vaga morta pra sempre. Roda no fetch diário e ao subir o backend.
     */
    @Transactional
    public void limparVagasRecusadasAntigas() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DIAS_PARA_EXCLUIR_RECUSADAS);
        int apagadas = jobRepository.deleteByRejectedTrueAndRejectedAtBefore(cutoff);
        if (apagadas > 0) {
            log.info("=== {} vagas recusadas há mais de {} dias foram apagadas ===",
                    apagadas, DIAS_PARA_EXCLUIR_RECUSADAS);
        }
    }

    // Janela móvel de retenção pra vaga antiga nunca engajada — hoje remove
    // tudo publicado antes de ~2 anos atrás; daqui a um ano remove tudo
    // publicado antes de ~2 anos daquela data, e assim sempre (não é uma
    // data fixa tipo "antes de 2024", é sempre "há mais de 2 anos").
    private static final int DIAS_PARA_EXCLUIR_VAGAS_ANTIGAS = 730;

    /**
     * Apaga permanentemente vaga publicada há mais de
     * {@link #DIAS_PARA_EXCLUIR_VAGAS_ANTIGAS} dias que o usuário nunca
     * interagiu de verdade (nunca marcou interesse/aplicou/recusou) e não
     * fixou — pedido depois do usuário relatar que usava "Recusada" como
     * workaround só pra sumir com vaga de 2020 parada em "Novas" sem nunca
     * ter aplicado, o que inflava as métricas de aplicação (ver
     * aplicarStatus() em JobController). Qualquer vaga que o usuário
     * realmente tocou (mesmo antiga) nunca é apagada por essa rotina.
     */
    @Transactional
    public void limparVagasAntigasNuncaEngajadas() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DIAS_PARA_EXCLUIR_VAGAS_ANTIGAS);
        int apagadas = jobRepository.deleteOldUnengagedJobs(cutoff);
        if (apagadas > 0) {
            log.info("=== {} vagas antigas (publicadas antes de {}) nunca engajadas foram apagadas ===",
                    apagadas, cutoff.toLocalDate());
        }
    }

    /**
     * Roda também ao subir a aplicação para já ter dados no dashboard.
     *
     * <p>Importante: NÃO usa {@code @PostConstruct} de propósito.
     * {@code @PostConstruct} roda durante a inicialização do contexto Spring,
     * ANTES do Tomcat abrir a porta 8080 pra escutar — como esse fetch inteiro
     * leva ~3 minutos (o Nerdin sozinho passa de 1min), isso deixava a porta
     * fechada esse tempo todo, e qualquer requisição nesse meio-tempo (ex: o
     * nginx do frontend tentando carregar a página) recebia conexão recusada
     * → 502. {@code @EventListener(ApplicationReadyEvent.class)} só dispara
     * DEPOIS que o Tomcat já está aceitando conexões, e {@code @Async} garante
     * que roda numa thread separada sem seat segurar mais nada — o app fica
     * respondendo (com os dados que já tinha) enquanto isso roda por baixo.</p>
     */
    private static final List<String> FONTES_100_REMOTO = List.of("REMOTIVE", "ARBEITNOW", "WWR");

    @EventListener(ApplicationReadyEvent.class)
    @Async
    public void fetchNaInicializacao() {
        log.info("=== Fetch inicial ao subir a aplicação (em background — app já está respondendo) ===");
        classificarVagasAntigas();
        marcarModalidadeRemotaAntigas();
        fetchAllJobs();
        enriquecerSalariosGupyAntigas();
        limparVagasRecusadasAntigas();
        limparVagasAntigasNuncaEngajadas();
    }

// Limita quantas vagas antigas sem salário são checadas por ciclo —
    // cada checagem é uma requisição HTTP extra à Gupy, então isso evita
    // disparar centenas de requisições de uma vez.
    private static final int MAX_BACKFILL_SALARIO = 150;

    /**
     * Backfill: vagas Gupy já salvas sem salário (a busca por termo não traz
     * esse dado) são checadas uma a uma via endpoint de detalhe, em lotes
     * pequenos por ciclo, até o catálogo inteiro ficar coberto.
     */
    @Transactional
    public void enriquecerSalariosGupyAntigas() {
        List<Job> semSalario = jobRepository.findBySourceAndSalaryIsNullOrderByPostedAtDesc(
                "GUPY", PageRequest.of(0, MAX_BACKFILL_SALARIO));
        if (semSalario.isEmpty()) return;

        int achados = 0;
        for (Job job : semSalario) {
            String salario = gupyService.fetchSalaryHint(job.getUrl());
            if (salario != null) {
                job.setSalary(salario);
                jobRepository.save(job);
                achados++;
            }
        }
        log.info("=== Backfill de salário Gupy: {} vagas checadas, {} com salário encontrado ===",
                semSalario.size(), achados);
    }

    /**
     * Backfill: Remotive/Arbeitnow/WWR só trazem vagas 100% remotas, então
     * vagas antigas dessas fontes sem workplaceType são marcadas como REMOTO.
     */
    @Transactional
    public void marcarModalidadeRemotaAntigas() {
        List<Job> semModalidade = jobRepository.findByWorkplaceTypeIsNullAndSourceIn(FONTES_100_REMOTO);
        if (semModalidade.isEmpty()) return;

        for (Job job : semModalidade) {
            job.setWorkplaceType("REMOTO");
        }
        jobRepository.saveAll(semModalidade);
        log.info("=== {} vagas antigas marcadas como REMOTO ===", semModalidade.size());
    }

    /**
     * Backfill: vagas salvas antes da coluna seniority existir são
     * classificadas retroativamente pelo título/tags.
     */
    @Transactional
    public void classificarVagasAntigas() {
        List<Job> semSenioridade = jobRepository.findBySeniorityIsNull();
        if (semSenioridade.isEmpty()) return;

        for (Job job : semSenioridade) {
            job.setSeniority(seniorityClassifier.classify(job.getTitle(), job.getTags()));
        }
        jobRepository.saveAll(semSenioridade);
        log.info("=== {} vagas antigas classificadas por senioridade ===", semSenioridade.size());
    }

    // Trava simples pra evitar dois fetches rodando ao mesmo tempo — desde
    // que o fetch inicial passou a rodar em background (ver
    // fetchNaInicializacao), o app fica respondendo durante ele, e agora dá
    // pra alguém clicar em "Buscar agora" (ou o cron de 2h disparar) enquanto
    // o inicial ainda está em andamento. Sem essa trava, o Nerdin (o mais
    // pesado) rodaria duas vezes ao mesmo tempo à toa.
    private final AtomicBoolean fetchEmAndamento = new AtomicBoolean(false);

    // Sentinela devolvida quando a chamada foi ignorada por já ter um fetch
    // em andamento — distinto de "0 vagas novas" (que é um resultado válido).
    public static final int FETCH_JA_EM_ANDAMENTO = -1;

    @Transactional
    public int fetchAllJobs() {
        if (!fetchEmAndamento.compareAndSet(false, true)) {
            log.info("=== Fetch já em andamento, ignorando chamada concorrente ===");
            return FETCH_JA_EM_ANDAMENTO;
        }
        try {
            List<Job> allJobs = new ArrayList<>();
            allJobs.addAll(remotiveService.fetchJobs());
            allJobs.addAll(arbeitnowService.fetchJobs());
            allJobs.addAll(workRemotelyService.fetchJobs());
            allJobs.addAll(gupyService.fetchJobs());
            allJobs.addAll(eurecaService.fetchJobs());
            allJobs.addAll(querovagastechService.fetchJobs());
            allJobs.addAll(nerdinService.fetchJobs());

            int novos = 0;
            int enriquecidas = 0;
            for (Job job : allJobs) {
                Optional<Job> existente = jobRepository.findByUrl(job.getUrl());
                if (existente.isEmpty()) {
                    // Antes, quando o regex não decidia (NAO_INFORMADO), tentava uma
                    // segunda opinião via IA (AiClassifierService) — removido: na
                    // prática acertava pouco (título ambíguo pro regex costuma ser
                    // ambíguo pra IA também) e gastava cota de Gemini à toa em toda
                    // vaga nova ambígua, sem contrapartida que justificasse.
                    String seniority = seniorityClassifier.classify(job.getTitle(), job.getTags());
                    job.setSeniority(seniority);
                    if ("GUPY".equals(job.getSource()) && job.getSalary() == null) {
                        job.setSalary(gupyService.fetchSalaryHint(job.getUrl()));
                    }
                    jobRepository.save(job);
                    novos++;
                } else if (enriquecer(existente.get(), job)) {
                    jobRepository.save(existente.get());
                    enriquecidas++;
                }
            }

            log.info("=== Fetch concluído: {} vagas totais, {} novas salvas, {} enriquecidas ===",
                    allJobs.size(), novos, enriquecidas);
            return novos;
        } finally {
            fetchEmAndamento.set(false);
        }
    }

    /**
     * Preenche campos que a vaga já salva não tinha (workplaceType, state, city,
     * expiresAt, salary) com dados do fetch mais recente, sem tocar em seen/applied.
     * Útil quando um campo novo é introduzido depois que a vaga já foi salva, ou
     * quando a extração (ex: salário via regex na descrição) melhora com o tempo.
     */
    private boolean enriquecer(Job existente, Job recemBuscada) {
        boolean mudou = false;
        if (existente.getSalary() == null && recemBuscada.getSalary() != null) {
            existente.setSalary(recemBuscada.getSalary());
            mudou = true;
        }
        if (existente.getWorkplaceType() == null && recemBuscada.getWorkplaceType() != null) {
            existente.setWorkplaceType(recemBuscada.getWorkplaceType());
            mudou = true;
        }
        if (existente.getState() == null && recemBuscada.getState() != null) {
            existente.setState(recemBuscada.getState());
            mudou = true;
        }
        if (existente.getCity() == null && recemBuscada.getCity() != null) {
            existente.setCity(recemBuscada.getCity());
            mudou = true;
        }
        if (existente.getExpiresAt() == null && recemBuscada.getExpiresAt() != null) {
            existente.setExpiresAt(recemBuscada.getExpiresAt());
            mudou = true;
        }
        if (existente.getCompanyLogoUrl() == null && recemBuscada.getCompanyLogoUrl() != null) {
            existente.setCompanyLogoUrl(recemBuscada.getCompanyLogoUrl());
            mudou = true;
        }
        if (existente.getPcd() == null && recemBuscada.getPcd() != null) {
            existente.setPcd(recemBuscada.getPcd());
            mudou = true;
        }
        return mudou;
    }
}

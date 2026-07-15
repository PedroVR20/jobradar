package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
    private final SeniorityClassifier seniorityClassifier;

    /**
     * Roda automaticamente todo dia às 08:00 BRT
     */
    @Scheduled(cron = "0 0 8 * * *", zone = "America/Sao_Paulo")
    @Transactional
    public void fetchDiario() {
        log.info("=== Fetch diário iniciado ===");
        fetchAllJobs();
        limparVagasRecusadasAntigas();
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

    /**
     * Roda também ao subir a aplicação para já ter dados no dashboard
     */
    private static final List<String> FONTES_100_REMOTO = List.of("REMOTIVE", "ARBEITNOW", "WWR");

    @PostConstruct
    public void fetchNaInicializacao() {
        log.info("=== Fetch inicial ao subir a aplicação ===");
        classificarVagasAntigas();
        marcarModalidadeRemotaAntigas();
        purgarVagasNaoTech();
        fetchAllJobs();
        enriquecerSalariosGupyAntigas();
        limparVagasRecusadasAntigas();
    }

    /**
     * Remove do banco vagas que não são de tecnologia — principalmente vagas
     * da Eureca que foram importadas antes do filtro TechJobFilter existir
     * (ex: "Estágio em Operações de Trens", "Superior Administrativo").
     * Também cobre vagas Gupy importadas por termos ambíguos.
     * Vagas favoritadas ou com notas do usuário são preservadas por segurança.
     */
    @Transactional
    public void purgarVagasNaoTech() {
        List<Job> candidatas = new ArrayList<>(jobRepository.findBySource("EURECA"));
        candidatas.addAll(jobRepository.findBySource("GUPY"));

        List<Long> paraDeletar = candidatas.stream()
                .filter(j -> !TechJobFilter.isTechJob(j.getTitle()))
                .filter(j -> !Boolean.TRUE.equals(j.getFavorited()))
                .filter(j -> j.getNotes() == null || j.getNotes().isBlank())
                .map(Job::getId)
                .toList();

        if (!paraDeletar.isEmpty()) {
            jobRepository.deleteAllById(paraDeletar);
            log.info("=== Purga non-tech: {} vagas removidas do banco (Eureca/Gupy sem keyword de TI) ===",
                    paraDeletar.size());
        } else {
            log.info("=== Purga non-tech: nenhuma vaga para remover ===");
        }
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

    @Transactional
    public int fetchAllJobs() {
        List<Job> allJobs = new ArrayList<>();
        allJobs.addAll(remotiveService.fetchJobs());
        allJobs.addAll(arbeitnowService.fetchJobs());
        allJobs.addAll(workRemotelyService.fetchJobs());
        allJobs.addAll(gupyService.fetchJobs());
        allJobs.addAll(eurecaService.fetchJobs());

        int novos = 0;
        int enriquecidas = 0;
        for (Job job : allJobs) {
            Optional<Job> existente = jobRepository.findByUrl(job.getUrl());
            if (existente.isEmpty()) {
                job.setSeniority(seniorityClassifier.classify(job.getTitle(), job.getTags()));
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

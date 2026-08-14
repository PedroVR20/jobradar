package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobEmbeddingRepository;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobAggregatorService {

    private final JobRepository jobRepository;
    // Fase 2.5 — antes eram 7 campos individuais (RemotiveService,
    // ArbeitnowService, ...) com 7 chamadas allJobs.addAll(xService.fetchJobs())
    // hardcoded em fetchAllJobs() — adicionar uma fonte nova (Greenhouse,
    // Adzuna, SINE Aberto, ver Fase 2) significava editar esse método. Agora
    // o Spring injeta automaticamente TODO bean que implementa JobSource
    // nessa lista — adicionar fonte vira só criar a classe com @Service.
    private final List<JobSource> fontes;
    // gupyService continua injetado À PARTE (o MESMO bean singleton que já
    // está dentro de `fontes`, Spring não duplica) porque tem um método
    // extra fora do contrato JobSource: fetchSalaryHint, usado só pelo
    // backfill de salário (ver enriquecerSalariosGupyAntigas).
    private final GupyService gupyService;
    private final SeniorityClassifier seniorityClassifier;
    private final JobEmbeddingService jobEmbeddingService;
    private final JobEmbeddingRepository jobEmbeddingRepository;
    private final CompanyNormalizer companyNormalizer;
    private final RelevanceClassifier relevanceClassifier;
    private final JobLinkCheckerService jobLinkCheckerService;

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
        arquivarVagasAntigasNuncaEngajadas();
        jobLinkCheckerService.checarLinksAntigos();
        limparEmbeddingsOrfaos();
    }

    // Fase 6.1 — job_embeddings não tem FK formal pra jobs (ver comentário em
    // JobEmbedding sobre por que), então limpezas em lote de Job (bulk
    // DELETE via JPQL, que não dispara cascade) podem deixar linha órfã pra
    // trás. Varre e remove periodicamente, mesmo ciclo das outras limpezas.
    @Transactional
    public void limparEmbeddingsOrfaos() {
        int apagados = jobEmbeddingRepository.deleteOrphans();
        if (apagados > 0) {
            log.info("=== {} embeddings órfãos (vaga já apagada) removidos ===", apagados);
        }
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

    // Fase 7.3+8.4 — janela BEM mais curta que DIAS_PARA_EXCLUIR_VAGAS_ANTIGAS
    // (730 dias): o problema real não é "vaga de 2 anos atrás" (isso o
    // delete definitivo já resolve), é vaga de 1-2 meses atrás parada em
    // Novas/Já vistas sem nunca ter sido decidida, poluindo a lista ativa
    // no dia a dia. Arquivar é reversível (JobRepository.reativarVaga) —
    // deletar não. Cobre "nunca vista" de graça: toda vaga nunca vista
    // também nunca foi engajada, então já cai na mesma regra.
    private static final int DIAS_PARA_ARQUIVAR_NUNCA_ENGAJADA = 30;

    /**
     * Arquiva (não apaga) vaga publicada há mais de
     * {@link #DIAS_PARA_ARQUIVAR_NUNCA_ENGAJADA} dias que o usuário nunca
     * interagiu de verdade — mesma regra de {@link #limparVagasAntigasNuncaEngajadas()},
     * intervalo bem mais curto, reversível. Roda no fetch periódico e ao
     * subir o backend.
     */
    @Transactional
    public void arquivarVagasAntigasNuncaEngajadas() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(DIAS_PARA_ARQUIVAR_NUNCA_ENGAJADA);
        String motivo = "Sem interação há mais de " + DIAS_PARA_ARQUIVAR_NUNCA_ENGAJADA + " dias";
        int arquivadas = jobRepository.arquivarVagasAntigasNuncaEngajadas(cutoff, motivo);
        if (arquivadas > 0) {
            log.info("=== {} vagas antigas (publicadas antes de {}) nunca engajadas foram arquivadas ===",
                    arquivadas, cutoff.toLocalDate());
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
        classificarQualidadeVagasAntigas();
        marcarModalidadeRemotaAntigas();
        limparLocalizacaoNerdinAntiga();
        fetchAllJobs();
        enriquecerSalariosGupyAntigas();
        limparVagasRecusadasAntigas();
        limparVagasAntigasNuncaEngajadas();
        arquivarVagasAntigasNuncaEngajadas();
        jobLinkCheckerService.checarLinksAntigos();
    }

    // Limita quantas vagas antigas sem salário são checadas por ciclo —
    // cada checagem é uma requisição HTTP extra à Gupy, então isso evita
    // disparar centenas de requisições de uma vez. Subido de 150 pra 300
    // (Fase 1.3) junto da correção do bug abaixo — sem o bug, cada vaga só
    // precisa ser checada uma vez de verdade, então dá pra ser mais
    // generoso sem desperdiçar requisição repetida.
    private static final int MAX_BACKFILL_SALARIO = 300;

    /**
     * Backfill: vagas Gupy já salvas sem salário (a busca por termo não traz
     * esse dado) são checadas uma a uma via endpoint de detalhe, em lotes
     * por ciclo, até o catálogo inteiro ficar coberto.
     *
     * <p>BUG REAL corrigido (Fase 1.3): antes pegava sempre as N vagas MAIS
     * RECENTES sem salário (ORDER BY postedAt DESC). Medido em produção:
     * 150 vagas checadas, 0 com salário achado — ou seja, as vagas mais
     * recentes genuinamente não tinham salário divulgado, e o backfill
     * martelava o MESMO lote todo ciclo pra sempre, nunca avançando pras
     * ~1900 outras vagas Gupy sem salário que nunca tinham sido checadas.
     * Cobertura real ficou travada em 6% (131/2202) por causa disso.</p>
     *
     * <p>Agora {@code salaryCheckedAt} marca quando uma vaga foi checada —
     * a fase 1 do lote prioriza vaga NUNCA checada (é isso que faz o
     * backfill avançar pelo catálogo de verdade); só se sobrar cota depois
     * de esgotar as nunca-checadas é que reprocessa as já checadas há mais
     * tempo (empresa pode ter adicionado salário depois).</p>
     */
    @Transactional
    public void enriquecerSalariosGupyAntigas() {
        List<Job> candidatas = new ArrayList<>(jobRepository.findBySourceAndSalaryIsNullAndSalaryCheckedAtIsNull(
                "GUPY", PageRequest.of(0, MAX_BACKFILL_SALARIO)));
        int nuncaCheckadas = candidatas.size();

        int faltam = MAX_BACKFILL_SALARIO - candidatas.size();
        if (faltam > 0) {
            candidatas.addAll(jobRepository.findBySourceAndSalaryIsNullAndSalaryCheckedAtIsNotNullOrderBySalaryCheckedAtAsc(
                    "GUPY", PageRequest.of(0, faltam)));
        }
        if (candidatas.isEmpty()) return;

        int achados = 0;
        for (Job job : candidatas) {
            String salario = gupyService.fetchSalaryHint(job.getUrl());
            job.setSalaryCheckedAt(LocalDateTime.now());
            if (salario != null) {
                job.setSalary(salario);
                achados++;
            }
        }
        jobRepository.saveAll(candidatas);
        log.info("=== Backfill de salário Gupy: {} vagas checadas ({} nunca checadas antes), {} com salário encontrado ===",
                candidatas.size(), nuncaCheckadas, achados);
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
     * Backfill pontual (Fase 1.2): vagas do Nerdin salvas ANTES do fix que
     * separa "Cidade • UF" em cidade+estado de verdade ficaram com o texto
     * cru mashed no campo city (ex: "Rio de Janeiro • RJ" como se fosse o
     * nome da cidade) — limpa essas retroativamente. Roda uma vez por
     * vaga (o "•" some depois de limpa, então não reprocessa a mesma toda
     * inicialização).
     */
    @Transactional
    public void limparLocalizacaoNerdinAntiga() {
        List<Job> comLocalCru = jobRepository.findBySourceAndCityContaining("NERDIN", "•");
        if (comLocalCru.isEmpty()) return;

        int corrigidas = 0;
        for (Job job : comLocalCru) {
            String[] partes = job.getCity().split("•");
            if (partes.length != 2) continue;
            String estado = EstadosBrasileiros.nomeCompleto(partes[1].trim());
            job.setCity(partes[0].trim());
            if (job.getState() == null && estado != null) job.setState(estado);
            corrigidas++;
        }
        jobRepository.saveAll(comLocalCru);
        log.info("=== {} vagas antigas do Nerdin com localização crua corrigidas (cidade+estado separados) ===", corrigidas);
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

    /**
     * Backfill (Fase 7.1 + 7.6): vagas salvas antes dessas duas colunas
     * existirem (companyNormalized nunca é apagado depois de preenchido,
     * então "sem ele" só acontece uma vez por vaga, igual ao backfill de
     * senioridade acima). Roda os dois campos juntos porque os dois são
     * cálculo local (regex/normalização de texto), sem custo de rede —
     * não precisa de lote limitado como o backfill de salário da Gupy.
     */
    @Transactional
    public void classificarQualidadeVagasAntigas() {
        List<Job> semClassificacao = jobRepository.findByCompanyNormalizedIsNull();
        if (semClassificacao.isEmpty()) return;

        for (Job job : semClassificacao) {
            job.setCompanyNormalized(companyNormalizer.normalizar(job.getCompany()));
            job.setForaDeArea(!relevanceClassifier.isRelevante(job.getTitle(), job.getTags()));
        }
        jobRepository.saveAll(semClassificacao);
        long foraDeArea = semClassificacao.stream().filter(j -> Boolean.TRUE.equals(j.getForaDeArea())).count();
        log.info("=== {} vagas antigas classificadas (empresa normalizada + relevância) — {} marcadas fora de área ===",
                semClassificacao.size(), foraDeArea);
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

    // Mais rígido que o 0.6 usado no painel de duplicatas sob demanda (GET
    // /api/jobs/duplicates) — lá é só um SINAL pro usuário revisar e decidir;
    // aqui a decisão é automática e silenciosa (a vaga nem chega a ser
    // criada), então o limiar precisa ser mais conservador pra não perder
    // vaga de verdade por semelhança de título coincidente.
    private static final double DUPLICATA_JACCARD_MINIMO = 0.75;

    @Transactional
    public int fetchAllJobs() {
        if (!fetchEmAndamento.compareAndSet(false, true)) {
            log.info("=== Fetch já em andamento, ignorando chamada concorrente ===");
            return FETCH_JA_EM_ANDAMENTO;
        }
        try {
            List<Job> allJobs = new ArrayList<>();
            // Fase 2.5 — cada fonte já trata seus próprios erros e nunca
            // deveria lançar (contrato de JobSource), mas o try/catch extra
            // aqui garante que uma fonte NOVA com um bug real (exceção
            // escapando) nunca derruba o fetch das outras fontes junto.
            for (JobSource fonte : fontes) {
                try {
                    allJobs.addAll(fonte.fetchJobs());
                } catch (Exception e) {
                    log.error("=== Fonte '{}' falhou no fetch (exceção não tratada internamente): {} ===",
                            fonte.nome(), e.getMessage());
                }
            }

            // Pool de vagas ativas agrupadas por empresa normalizada, pra
            // achar duplicata entre FONTES diferentes (mesma vaga na Gupy e
            // via QueroVagasTech, cada uma com URL própria — findByUrl não
            // pega esse caso). Montado uma vez fora do loop (não uma query
            // por vaga nova) e atualizado conforme novas vagas entram nesse
            // mesmo fetch, pra pegar duplicata entre duas fontes buscadas
            // agora mesmo, não só contra o que já existia antes.
            Map<String, List<Job>> ativosPorEmpresa = new HashMap<>();
            for (Job existenteJob : jobRepository.findAll()) {
                if (existenteJob.isRejected()) continue;
                ativosPorEmpresa.computeIfAbsent(normalizeCompany(existenteJob.getCompany()), k -> new ArrayList<>()).add(existenteJob);
            }

            int novos = 0;
            int enriquecidas = 0;
            int duplicatasEntreFontes = 0;
            for (Job job : allJobs) {
                Optional<Job> existente = jobRepository.findByUrl(job.getUrl());
                if (existente.isPresent()) {
                    if (enriquecer(existente.get(), job)) {
                        jobRepository.save(existente.get());
                        enriquecidas++;
                    }
                    continue;
                }

                // Antes, quando o regex não decidia (NAO_INFORMADO), tentava uma
                // segunda opinião via IA (AiClassifierService) — removido: na
                // prática acertava pouco (título ambíguo pro regex costuma ser
                // ambíguo pra IA também) e gastava cota de Gemini à toa em toda
                // vaga nova ambígua, sem contrapartida que justificasse.
                String seniority = seniorityClassifier.classify(job.getTitle(), job.getTags());

                Job duplicataProvavel = acharDuplicataEntreFontes(job, seniority, ativosPorEmpresa);
                if (duplicataProvavel != null) {
                    log.info("Fetch: vaga '{}' ({}) tratada como duplicata entre fontes de '{}' ({}), não criada de novo",
                            job.getTitle(), job.getSource(), duplicataProvavel.getTitle(), duplicataProvavel.getSource());
                    if (enriquecer(duplicataProvavel, job)) {
                        jobRepository.save(duplicataProvavel);
                        enriquecidas++;
                    }
                    duplicatasEntreFontes++;
                    continue;
                }

                job.setSeniority(seniority);
                if ("GUPY".equals(job.getSource()) && job.getSalary() == null) {
                    job.setSalary(gupyService.fetchSalaryHint(job.getUrl()));
                }
                // Fase 7.1 + 7.6 — classificados na entrada, não depois: uma
                // vaga nova já nasce com o veredito de relevância e o nome de
                // empresa canônico, em vez de esperar o backfill periódico
                // alcançar ela.
                job.setForaDeArea(!relevanceClassifier.isRelevante(job.getTitle(), job.getTags()));
                job.setCompanyNormalized(companyNormalizer.normalizar(job.getCompany()));
                jobRepository.save(job);
                novos++;
                // Falha silenciosa de propósito (sem IA configurada, Gemini
                // fora do ar) — busca semântica só fica indisponível pra essa
                // vaga até o backfill rodar, não trava o fetch inteiro. Fase
                // 6.1 — embedESalvar já persiste sozinho em job_embeddings,
                // não precisa mais salvar o Job de novo depois.
                jobEmbeddingService.embedESalvar(job);
                ativosPorEmpresa.computeIfAbsent(normalizeCompany(job.getCompany()), k -> new ArrayList<>()).add(job);
            }

            log.info("=== Fetch concluído: {} vagas totais, {} novas salvas, {} enriquecidas, {} duplicatas entre fontes evitadas ===",
                    allJobs.size(), novos, enriquecidas, duplicatasEntreFontes);
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

    // ===================== Deduplicação entre fontes =====================
    // Mesma heurística (empresa normalizada + Jaccard de palavras do título +
    // senioridade igual) que GET /api/jobs/duplicates usa pra SINALIZAR
    // duplicata pro usuário revisar — aqui roda automaticamente no fetch,
    // sem IA (não dá pra gastar cota numa checagem que roda a cada vaga nova
    // de todo fetch periódico) e com limiar mais alto (ver
    // DUPLICATA_JACCARD_MINIMO) por decidir sozinha, sem revisão humana.

    private Job acharDuplicataEntreFontes(Job novo, String seniorityNovo, Map<String, List<Job>> ativosPorEmpresa) {
        String key = normalizeCompany(novo.getCompany());
        if (key.isBlank()) return null;
        List<Job> candidatos = ativosPorEmpresa.get(key);
        if (candidatos == null || candidatos.isEmpty()) return null;

        Set<String> palavrasNovo = titleWords(novo.getTitle());
        for (Job candidato : candidatos) {
            boolean mesmaSenioridade = java.util.Objects.equals(seniorityNovo, candidato.getSeniority());
            if (mesmaSenioridade && jaccard(palavrasNovo, titleWords(candidato.getTitle())) >= DUPLICATA_JACCARD_MINIMO) {
                return candidato;
            }
        }
        return null;
    }

    // Fase 7.6 — delega pro CompanyNormalizer compartilhado, mesma lógica
    // usada em JobController.getDuplicates e agora também persistida em
    // Job.companyNormalized (ver classificarQualidadeVagasAntigas abaixo e
    // o preenchimento em fetchAllJobs).
    private String normalizeCompany(String company) {
        return companyNormalizer.normalizar(company);
    }

    private static final Set<String> TITLE_STOPWORDS = Set.of(
            "de", "da", "do", "das", "dos", "e", "para", "com", "em", "a", "o", "i", "ii", "iii"
    );

    private Set<String> titleWords(String title) {
        if (title == null) return Set.of();
        String norm = normalize(title).replaceAll("[^a-z0-9 ]", " ").trim();
        Set<String> words = new HashSet<>();
        for (String w : norm.split("\\s+")) {
            if (w.length() < 2 || TITLE_STOPWORDS.contains(w)) continue;
            words.add(w);
        }
        return words;
    }

    private double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private String normalize(String text) {
        String decomposed = java.text.Normalizer.normalize(text.toLowerCase(), java.text.Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }
}

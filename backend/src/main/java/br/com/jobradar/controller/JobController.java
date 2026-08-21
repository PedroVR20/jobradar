package br.com.jobradar.controller;

import br.com.jobradar.dto.JobPageResult;
import br.com.jobradar.model.Job;
import br.com.jobradar.repository.ChaveContagemProjection;
import br.com.jobradar.repository.JobEventRepository;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.repository.JobSpecifications;
import br.com.jobradar.service.AiDuplicateVerifierService;
import br.com.jobradar.service.AiFeatureBudgetService;
import br.com.jobradar.service.CompanyNormalizer;
import br.com.jobradar.service.GeminiService;
import br.com.jobradar.service.JarvisAssistantService;
import br.com.jobradar.service.JobAggregatorService;
import br.com.jobradar.service.JobEmbeddingService;
import br.com.jobradar.service.JobQueryService;
import br.com.jobradar.service.JobStatusService;
import br.com.jobradar.service.PersonalRankingService;
import br.com.jobradar.service.SeniorityClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.Normalizer;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * Fase 5.1 — CRUD/listagem/dashboard das vagas. Antes deste split o arquivo
 * tinha 1420 linhas e misturava isso com admin (retreino de modelo, backfill
 * de embedding) e IA por vaga (carta, match-score, chat do Hunter) — ver
 * {@link JobAdminController} e {@link AssistantController} pra onde essas
 * duas partes foram.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class JobController {

    private final JobRepository jobRepository;
    private final JobEventRepository jobEventRepository;
    private final JobAggregatorService aggregatorService;
    private final SeniorityClassifier seniorityClassifier;
    private final AiDuplicateVerifierService aiDuplicateVerifierService;
    private final CompanyNormalizer companyNormalizer;
    private final GeminiService geminiService;
    private final JarvisAssistantService jarvisAssistantService;
    private final JobStatusService jobStatusService;
    private final PersonalRankingService personalRankingService;
    private final JobQueryService jobQueryService;
    private final JobEmbeddingService jobEmbeddingService;
    private final AiFeatureBudgetService aiFeatureBudgetService;

    /**
     * Lista todas as vagas com filtros opcionais
     * GET /api/jobs?source=REMOTIVE&search=java&seniority=JUNIOR&days=7&sort=posted_desc
     *
     * search    → multi-termo: "java senior" exige que TODOS os termos apareçam
     * seniority → ESTAGIO | JUNIOR | PLENO | SENIOR | NAO_INFORMADO
     * workplaceType → REMOTO | HIBRIDO | PRESENCIAL (só vagas brasileiras/Gupy informam)
     * state     → nome do estado por extenso, ignora acentos (ex: "sao paulo" acha "São Paulo")
     * days      → só vagas publicadas nos últimos N dias
     * sort      → posted_desc (padrão) | posted_asc | fetched_desc | personal (Fase 3.1, ver PersonalRankingService)
     * onlyNew   → só não vistas
     * onlySeen  → só vistas, sem interesse marcado, e não aplicadas
     * onlyInteressado → só marcadas com interesse, e não aplicadas
     * onlyApplied → só aplicadas e fora de processo (não confundir com em andamento)
     * onlyInProgress → só aplicadas e em processo seletivo ativo
     * onlyRejected → só recusadas/congeladas (somem sozinhas depois de 7 dias)
     * onlyExpired → Fase 7.2: só vagas com prazo (expiresAt) vencido, ainda
     *   não aplicadas nem recusadas. Nas outras abas de decisão (onlyNew/
     *   onlySeen/onlyInteressado) vaga vencida some sozinha da lista.
     * onlyArchived → Fase 7.3+8.4: só vagas arquivadas (ver
     *   JobAggregatorService.arquivarVagasAntigasNuncaEngajadas). Sem essa
     *   flag, vaga arquivada some de TODAS as abas, não só das de decisão.
     *
     * Fase 6.3 — devolve página, não o catálogo inteiro. Antes disso o
     * corpo desse método inteiro (filtro SQL, busca textual, ordenação)
     * vivia aqui; extraído pra {@link JobQueryService} (Fase 5.5) junto da
     * paginação nova. techStack é novo aqui: antes os pills de tecnologia só
     * filtravam no cliente sobre o catálogo inteiro já carregado — com
     * paginação de verdade isso precisa acontecer no servidor, senão a
     * página 1 pode vir vazia mesmo com resultado no catálogo inteiro.
     */
    @GetMapping
    public JobPageResult getAll(
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String seniority,
            @RequestParam(required = false) String workplaceType,
            @RequestParam(required = false) String state,
            @RequestParam(required = false) Integer days,
            @RequestParam(required = false, defaultValue = "posted_desc") String sort,
            @RequestParam(required = false, defaultValue = "false") boolean onlyNew,
            @RequestParam(required = false, defaultValue = "false") boolean onlySeen,
            @RequestParam(required = false, defaultValue = "false") boolean onlyInteressado,
            @RequestParam(required = false, defaultValue = "false") boolean onlyApplied,
            @RequestParam(required = false, defaultValue = "false") boolean onlyInProgress,
            @RequestParam(required = false, defaultValue = "false") boolean onlyRejected,
            @RequestParam(required = false, defaultValue = "false") boolean onlyExpired,
            @RequestParam(required = false, defaultValue = "false") boolean onlyArchived,
            @RequestParam(required = false) List<String> techStack,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size
    ) {
        return jobQueryService.listar(new JobQueryService.Filtro(
                source, search, seniority, workplaceType, state, days, sort,
                onlyNew, onlySeen, onlyInteressado, onlyApplied, onlyInProgress, onlyRejected, onlyExpired, onlyArchived,
                techStack, page, size
        ));
    }

    /**
     * Reativa uma vaga arquivada (Fase 7.3+8.4) — tira ela do "porão" e
     * devolve pro funil normal (volta a aparecer em Novas/Já vistas
     * dependendo de `seen`). Reversível de propósito: arquivamento nunca é
     * uma decisão definitiva, só uma limpeza de lista.
     * POST /api/jobs/{id}/reativar
     */
    @PostMapping("/{id}/reativar")
    public ResponseEntity<Void> reativarVaga(@PathVariable Long id) {
        int atualizadas = jobRepository.reativarVaga(id);
        return atualizadas > 0 ? ResponseEntity.noContent().build() : ResponseEntity.notFound().build();
    }

    /**
     * Lista os estados brasileiros presentes no banco (só vagas com state
     * preenchido, hoje só a fonte Gupy). Usado pra popular o filtro de estado.
     * GET /api/jobs/states
     */
    @GetMapping("/states")
    public List<String> getStates() {
        return jobRepository.findDistinctStates();
    }

    /**
     * Lista todas as fontes presentes no banco, incluindo fontes
     * personalizadas digitadas na adição manual de vaga (ex: "INFOJOBS").
     * Usado pra popular o filtro de fonte dinamicamente, sem precisar de
     * uma lista fixa no frontend.
     * GET /api/jobs/sources
     */
    @GetMapping("/sources")
    public List<String> getSources() {
        return jobRepository.findDistinctSources();
    }

    /**
     * Fase 3.1 — diz se já dá pra ordenar por "ranking pessoal" (dado
     * suficiente de interesse/aplicada/favoritada vs recusada-sem-aplicar).
     * O frontend usa isso pra decidir se mostra a opção no seletor de
     * ordenação, em vez de mostrar uma opção que hoje empataria tudo em 50.
     * GET /api/jobs/personal-ranking-status
     */
    @GetMapping("/personal-ranking-status")
    public Map<String, Object> getPersonalRankingStatus() {
        PersonalRankingService.Modelo modelo = personalRankingService.treinar();
        Map<String, Object> m = new HashMap<>();
        m.put("disponivel", modelo.disponivel());
        m.put("motivoIndisponivel", modelo.motivoIndisponivel());
        m.put("totalPositivas", modelo.totalPositivas());
        m.put("totalNegativas", modelo.totalNegativas());
        return m;
    }

    /**
     * Conta quantas vagas foram buscadas (fetchedAt) desde X minutos atrás.
     * Usado pelo frontend pra avisar "N vagas novas desde sua última visita"
     * ao abrir o app. Recebe minutos (não um timestamp absoluto do cliente)
     * de propósito — o cálculo de "agora - minutos" roda inteiramente no
     * servidor, então não tem risco de descompasso de fuso horário entre
     * o relógio do navegador e o do backend (já tivemos um bug assim com
     * a Eureca — ver EurecaService.parseDate).
     * GET /api/jobs/new-since?minutesAgo=180
     */
    @GetMapping("/new-since")
    public Map<String, Object> getNewSince(@RequestParam long minutesAgo) {
        long clamped = Math.max(0, Math.min(minutesAgo, 30 * 24 * 60)); // no máximo 30 dias
        LocalDateTime cutoff = LocalDateTime.now().minusMinutes(clamped);
        return Map.of("count", jobRepository.countByFetchedAtAfter(cutoff));
    }

    // Usado por normalizeCompany/titleWords (detecção de duplicatas) — a
    // versão usada pela listagem/busca textual mudou pra JobQueryService
    // (Fase 5.5), mas esse aqui continua local, mesmo precedente de
    // pequeno helper duplicado já registrado no projeto (ver statusDe em
    // JarvisWriteTools/JobStatusService).
    private String normalize(String text) {
        String decomposed = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "");
    }

    /**
     * Estatísticas para o painel superior do dashboard
     * GET /api/jobs/stats
     */
    @GetMapping("/stats")
    public Map<String, Object> getStats() {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", jobRepository.count());
        stats.put("novas", jobRepository.countBySeenFalse());
        stats.put("interessadas", jobRepository.countByInterestedTrue());
        stats.put("aplicadas", jobRepository.countByAppliedTrue());
        stats.put("emAndamento", jobRepository.countByAppliedTrueAndInProgressTrue());
        stats.put("recusadas", jobRepository.countByRejectedTrue());
        // Diferente de "recusadas" acima (TODA vaga recusada, aplicada ou
        // não) — esse é só a recusa de quem realmente tinha sido aplicada.
        // "aplicadas" - "recusadas" (o campo de cima) dava número negativo
        // quando a maioria das recusas nunca foi aplicada de verdade — usa
        // este campo pra qualquer conta que precise só da recusa "dentro"
        // do funil de aplicadas (bug reportado: "-21 aplicadas" na tela).
        stats.put("recusadasDeAplicadas", jobRepository.countByAppliedTrueAndRejectedTrue());
        // Fase 7.2 — alimenta o badge da aba "Vencidas".
        stats.put("vencidas", jobRepository.countByExpiresAtBeforeAndAppliedFalseAndRejectedFalse(LocalDate.now()));
        // Fase 7.3+8.4 — alimenta o badge da aba "Arquivadas".
        stats.put("arquivadas", jobRepository.countByArchivedTrue());
        // Fase 12.6 — era findByFetchedAtAfter(...).size(): materializava as
        // ~635 entidades das últimas 24h inteiras (todos os campos, uma a
        // uma via Hibernate) só pra jogar fora e contar o tamanho da lista.
        // countByFetchedAtAfter já existe e já é usado 34 linhas acima
        // (getNewSince) — era literalmente a mesma pergunta respondida de
        // dois jeitos diferentes no mesmo arquivo.
        stats.put("hojeCount", jobRepository.countByFetchedAtAfter(LocalDateTime.now().minusHours(24)));
        // Fase 12.5 — eram 7 + 5 = 12 chamadas de countBySource/countBySeniority,
        // uma por valor FIXO escrito no código. Além de 12 queries virarem 2,
        // a lista fixa já estava desatualizada: Greenhouse e SINE Aberto
        // (Fase 2) nunca apareciam no painel porque não estavam nesse
        // Map.of() — a versão por GROUP BY inclui toda fonte que existir
        // no banco de verdade, sem precisar editar este método de novo.
        stats.put("porFonte", contagemPorChave(jobRepository.contagemPorSource()));
        stats.put("porSenioridade", contagemPorChave(jobRepository.contagemPorSenioridade()));
        return stats;
    }

    // Linha com chave nula (seniority nunca classificada, ex: vaga manual
    // antiga) é descartada — mesmo comportamento de antes, quando essas
    // linhas também não caíam em nenhuma das 5 chaves fixas do Map.of().
    private Map<String, Long> contagemPorChave(List<ChaveContagemProjection> linhas) {
        Map<String, Long> resultado = new HashMap<>();
        for (ChaveContagemProjection linha : linhas) {
            if (linha.getChave() != null) resultado.put(linha.getChave(), linha.getTotal());
        }
        return resultado;
    }

    /**
     * Métricas de funil de candidatura pro dashboard de métricas.
     * Só considera vagas que já foram aplicadas em algum momento
     * (aguardando/em andamento/recusadas), independente do estado atual.
     * GET /api/jobs/metrics
     */
    @GetMapping("/metrics")
    public Map<String, Object> getMetrics() {
        List<Job> aplicadas = jobRepository.findByAppliedTrue();

        long total = aplicadas.size();
        long emAndamento = aplicadas.stream().filter(Job::isInProgress).count();
        long recusadas = aplicadas.stream().filter(Job::isRejected).count();
        long aguardandoRetorno = Math.max(0, total - emAndamento - recusadas);
        Double taxaResposta = total == 0
                ? null
                : Math.round((emAndamento + recusadas) * 1000.0 / total) / 10.0;

        // aplicações por semana (últimas 8 semanas, segunda-feira como início de cada uma)
        LocalDate inicioSemanaAtual = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        List<Map<String, Object>> porSemana = new ArrayList<>();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd/MM");
        for (int i = 7; i >= 0; i--) {
            LocalDate segunda = inicioSemanaAtual.minusWeeks(i);
            LocalDate proximaSegunda = segunda.plusDays(7);
            long count = aplicadas.stream()
                    .filter(j -> j.getAppliedAt() != null)
                    .map(j -> j.getAppliedAt().toLocalDate())
                    .filter(d -> !d.isBefore(segunda) && d.isBefore(proximaSegunda))
                    .count();
            porSemana.add(Map.of("semana", segunda.format(fmt), "count", count));
        }

        // tempo médio até avançar pra "em andamento" / até ser recusada — só
        // conta vagas com os dois timestamps disponíveis (appliedAt é campo novo,
        // então vagas antigas ficam de fora até serem reaplicadas)
        OptionalDouble avgAndamento = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getInProgressAt() != null)
                .mapToLong(j -> Duration.between(j.getAppliedAt(), j.getInProgressAt()).toDays())
                .average();
        OptionalDouble avgRecusa = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getRejectedAt() != null)
                .mapToLong(j -> Duration.between(j.getAppliedAt(), j.getRejectedAt()).toDays())
                .average();

        Map<String, Object> metrics = new HashMap<>();
        metrics.put("totalAplicadas", total);
        metrics.put("emAndamento", emAndamento);
        metrics.put("recusadas", recusadas);
        metrics.put("aguardandoRetorno", aguardandoRetorno);
        metrics.put("taxaResposta", taxaResposta);
        metrics.put("aplicacoesPorSemana", porSemana);
        metrics.put("tempoMedioAteAndamentoDias", avgAndamento.isPresent() ? Math.round(avgAndamento.getAsDouble() * 10) / 10.0 : null);
        metrics.put("tempoMedioAteRecusaDias", avgRecusa.isPresent() ? Math.round(avgRecusa.getAsDouble() * 10) / 10.0 : null);
        return metrics;
    }

    /**
     * Detecta possíveis vagas duplicadas publicadas em fontes diferentes —
     * mesma empresa (normalizada) + título com alta similaridade de palavras.
     * Só sinaliza pra revisão manual: não deleta nem mescla nada sozinho.
     * GET /api/jobs/duplicates
     *
     * Fase 11.1 — a verificação por IA saiu daqui. Antes o endpoint fazia até
     * {@code MAX_VERIFICACOES_IA} chamadas SÍNCRONAS ao Gemini dentro do
     * request — com uma key recusada no pool (ver GeminiService), cada uma
     * dessas chamadas percorria as 29 keys de novo, e o endpoint media mais
     * de 120s sem responder (confirmado: dois timeouts reais, um de 60s e
     * um de 120s). O Jaccard sozinho (agrupamento por similaridade de
     * palavras) é local e rápido — devolve na hora. A segunda opinião da IA
     * virou sob demanda, grupo a grupo, ver {@link #verificarDuplicataComIa}.
     */
    @GetMapping("/duplicates")
    public List<Map<String, Object>> getDuplicates() {
        // Fase 1.6 — filtro "não recusada" empurrado pro WHERE do SQL (via
        // JobSpecifications), em vez de carregar TODA vaga (incluindo
        // recusada, que esse endpoint nunca precisa) só pra descartar em
        // Java logo em seguida.
        List<Job> ativos = jobRepository.findAll(JobSpecifications.notRejected());

        Map<String, List<Job>> porEmpresa = new HashMap<>();
        for (Job j : ativos) {
            String key = normalizeCompany(j.getCompany());
            if (key.isBlank()) continue;
            porEmpresa.computeIfAbsent(key, k -> new ArrayList<>()).add(j);
        }

        List<Map<String, Object>> grupos = new ArrayList<>();
        for (List<Job> candidatos : porEmpresa.values()) {
            if (candidatos.size() < 2) continue;

            // Fase 10 — complete-linkage, não mais BFS de single-linkage.
            // Achado real ao coletar dado de treino pro Hunter-Dup: o BFS
            // antigo conectava A-D transitivamente via uma cadeia A-B-C-D
            // (cada elo só precisava bater jaccard>=0.6 com o VIZINHO
            // imediato), mesmo que A e D não se parecessem nada entre si.
            // Medido contra ~500 grupos reais verificados pelo Gemini: 57%
            // dos pares dentro de grupo "mesma vaga" tinham jaccard < 0.6
            // entre si — o critério antigo deixava grupo "esticar" longe
            // demais. Agora um candidato só entra se bater jaccard>=0.6 com
            // TODO mundo que já está confirmado no grupo (o "elo mais
            // fraco" do grupo inteiro decide, não só o último vizinho).
            // Medido contra o veredito real do Gemini nos mesmos ~500
            // grupos: 94% de precisão (quando o grupo bate esse critério,
            // quase sempre É mesma vaga de verdade) — não elimina a
            // necessidade da segunda opinião da IA (ver
            // verificarDuplicataComIa), só entrega um candidato mais limpo
            // pra ela analisar. Loop até estabilizar porque adicionar um
            // membro pode habilitar outro que antes não batia sozinho.
            boolean[] visitado = new boolean[candidatos.size()];
            for (int i = 0; i < candidatos.size(); i++) {
                if (visitado[i]) continue;
                List<Job> componente = new ArrayList<>();
                List<Set<String>> palavrasComponente = new ArrayList<>();
                componente.add(candidatos.get(i));
                palavrasComponente.add(titleWords(candidatos.get(i).getTitle()));
                visitado[i] = true;

                boolean mudou = true;
                while (mudou) {
                    mudou = false;
                    for (int j = 0; j < candidatos.size(); j++) {
                        if (visitado[j]) continue;
                        Job jobCandidato = candidatos.get(j);
                        Set<String> palavrasCandidato = titleWords(jobCandidato.getTitle());
                        // senioridade precisa bater com TODO o grupo — "Dev Pleno" e "Dev
                        // Sênior" da mesma empresa são vagas diferentes, não duplicata.
                        boolean mesmaSenioridade = componente.stream()
                                .allMatch(m -> java.util.Objects.equals(m.getSeniority(), jobCandidato.getSeniority()));
                        boolean pareceComTodoOGrupo = palavrasComponente.stream()
                                .allMatch(palavrasMembro -> jaccard(palavrasMembro, palavrasCandidato) >= 0.6);
                        if (mesmaSenioridade && pareceComTodoOGrupo) {
                            componente.add(jobCandidato);
                            palavrasComponente.add(palavrasCandidato);
                            visitado[j] = true;
                            mudou = true;
                        }
                    }
                }
                if (componente.size() < 2) continue;

                List<Map<String, Object>> vagas = componente.stream()
                        .map(j -> {
                            Map<String, Object> m = new HashMap<>();
                            m.put("id", j.getId());
                            m.put("title", j.getTitle());
                            m.put("source", j.getSource());
                            m.put("url", j.getUrl());
                            m.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
                            return m;
                        })
                        .toList();
                Map<String, Object> grupo = new HashMap<>();
                grupo.put("company", componente.get(0).getCompany());
                grupo.put("jobs", vagas);
                grupo.put("aiVerificado", false); // sempre false aqui agora — ver verificarDuplicataComIa
                grupos.add(grupo);
            }
        }

        return grupos;
    }

    public record VerificarDuplicataRequest(String company, List<String> titles) {}

    /**
     * Segunda opinião da IA sobre UM grupo específico já sinalizado pelo
     * Jaccard — chamada sob demanda pelo frontend (botão por grupo), nunca
     * em laço automático dentro de {@link #getDuplicates}. 503 sem IA
     * configurada; 200 com {@code mesmaVaga: false} quando a IA identifica
     * que são vagas genuinamente diferentes (o frontend remove o grupo da
     * lista nesse caso).
     * POST /api/jobs/duplicates/verify  Body: { "company": "...", "titles": ["...", "..."] }
     */
    @PostMapping("/duplicates/verify")
    public ResponseEntity<Map<String, Object>> verificarDuplicataComIa(@RequestBody VerificarDuplicataRequest req) {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        if (req == null || req.titles() == null || req.titles().size() < 2) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe pelo menos 2 títulos pra comparar."));
        }
        boolean mesmaVaga = aiDuplicateVerifierService.confirmar(req.company(), req.titles());
        return ResponseEntity.ok(Map.of("mesmaVaga", mesmaVaga));
    }

    // Fase 7.6 — delega pro CompanyNormalizer compartilhado (agora também
    // usado no fetch pra persistir Job.companyNormalized) em vez de manter
    // uma segunda cópia da mesma lógica aqui — as duas precisam concordar
    // sempre, ou o dedup em memória deste endpoint e o campo salvo divergem.
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

    /**
     * Marca uma vaga como vista
     * PATCH /api/jobs/{id}/seen
     */
    @PatchMapping("/{id}/seen")
    public ResponseEntity<Map<String, Object>> markSeen(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            job.setSeen(true);
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Marca uma vaga como aplicada (e como vista). Também tira do "em
     * andamento" — usado tanto pra marcar como aplicada pela primeira vez
     * quanto pra mover de volta de "Em Andamento" pra "Aplicadas".
     * PATCH /api/jobs/{id}/applied
     */
    @PatchMapping("/{id}/applied")
    public ResponseEntity<Map<String, Object>> markApplied(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            aplicarStatus(job, "APLICADA");
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Marca uma vaga como "em processo seletivo ativo" (entrevistas etc).
     * Implica aplicada+vista. Usado ao mover/arrastar de "Aplicadas" pra
     * "Em Andamento".
     * PATCH /api/jobs/{id}/in-progress
     */
    @PatchMapping("/{id}/in-progress")
    public ResponseEntity<Map<String, Object>> markInProgress(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            aplicarStatus(job, "ANDAMENTO");
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    private static final List<String> VALID_STATUSES =
            List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA");

    /**
     * Move a vaga diretamente pra um status específico — usado pelo menu "⋮"
     * do card, que permite pular pra qualquer aba independente da atual
     * (alternativa ao drag-and-drop, que nem sempre funciona ao iniciar o
     * arrasto a partir de um link dentro do card).
     * PATCH /api/jobs/{id}/status?value=NOVA|VISTA|APLICADA|ANDAMENTO|RECUSADA
     * motivo (opcional, só usado com value=RECUSADA) → Fase 8.7:
     * SALARIO|LOCALIDADE|SENIORIDADE|STACK|EMPRESA|OUTRO
     */
    @PatchMapping("/{id}/status")
    public ResponseEntity<Map<String, Object>> setStatus(
            @PathVariable Long id, @RequestParam String value,
            @RequestParam(required = false) String motivo) {
        String status = value == null ? "" : value.toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().build();
        }

        return jobRepository.findById(id).map(job -> {
            jobStatusService.aplicarStatus(job, status, motivo);
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    // Lógica movida pra JobStatusService (agora reaproveitada também pela
    // ferramenta marcarStatusDeVaga do Hunter, ver JarvisChatService) — esse
    // wrapper só existe pra não precisar trocar "aplicarStatus(...)" em toda
    // chamada já existente abaixo.
    private void aplicarStatus(Job job, String status) {
        jobStatusService.aplicarStatus(job, status);
    }

    /**
     * Dispara um fetch manual (útil para testar sem esperar o agendamento).
     * Como o fetch inicial agora roda em background ao subir o app (não
     * bloqueia mais o Tomcat, ver JobAggregatorService), dá pra essa chamada
     * chegar enquanto ele (ou o do cron de 2h) ainda está em andamento —
     * nesse caso devolve status "already-running" em vez de rodar tudo de
     * novo em paralelo à toa.
     * POST /api/jobs/fetch
     */
    @PostMapping("/fetch")
    public Map<String, Object> triggerFetch() {
        int novos = aggregatorService.fetchAllJobs();
        if (novos == JobAggregatorService.FETCH_JA_EM_ANDAMENTO) {
            return Map.of(
                    "status", "already-running",
                    "novasVagas", 0,
                    "timestamp", LocalDateTime.now().toString()
            );
        }
        return Map.of(
                "status", "ok",
                "novasVagas", novos,
                "timestamp", LocalDateTime.now().toString()
        );
    }

    /**
     * Adiciona manualmente uma vaga que não veio de nenhuma fonte automática
     * — ex: uma vaga achada no Glassdoor, LinkedIn ou indicação, que você
     * quer acompanhar no mesmo lugar que as outras.
     *
     * Se a URL já existir, atualiza a vaga existente em vez de duplicar.
     * POST /api/jobs/manual
     */
    @PostMapping("/manual")
    public ResponseEntity<Map<String, Object>> addManual(@RequestBody ManualJobRequest req) {
        if (req.title() == null || req.title().isBlank()
                || req.company() == null || req.company().isBlank()
                || req.url() == null || req.url().isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        String status = req.status() == null || req.status().isBlank()
                ? "APLICADA" : req.status().toUpperCase();
        if (!VALID_STATUSES.contains(status)) {
            return ResponseEntity.badRequest().build();
        }

        Job job = jobRepository.findByUrl(req.url()).orElseGet(Job::new);
        job.setTitle(req.title());
        job.setCompany(req.company());
        job.setUrl(req.url());
        job.setSource(req.source() == null || req.source().isBlank() ? "MANUAL" : req.source().toUpperCase());
        job.setSeniority(seniorityClassifier.classify(req.title(), req.tags()));
        job.setSalary(req.salary());
        job.setWorkplaceType(req.workplaceType());
        job.setState(req.state());
        job.setCity(req.city());
        job.setTags(req.tags());
        job.setExpiresAt(req.expiresAt());
        job.setPostedAt(req.postedAt() != null ? req.postedAt() : LocalDateTime.now());
        job.setFetchedAt(LocalDateTime.now());
        aplicarStatus(job, status);

        return ResponseEntity.ok(toDto(jobRepository.save(job)));
    }

    public record ManualJobRequest(
            String title,
            String company,
            String url,
            String source,
            String salary,
            String workplaceType,
            String state,
            String city,
            String tags,
            LocalDate expiresAt,
            LocalDateTime postedAt,
            String status
    ) {}

    // Converte entity -> DTO com tags como List<String>
    private Map<String, Object> toDto(Job job) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", job.getId());
        dto.put("title", job.getTitle());
        dto.put("company", job.getCompany());
        dto.put("url", job.getUrl());
        dto.put("source", job.getSource());
        dto.put("seniority", job.getSeniority() != null
                ? job.getSeniority()
                : SeniorityClassifier.NAO_INFORMADO);
        dto.put("salary", job.getSalary());
        dto.put("workplaceType", job.getWorkplaceType());
        dto.put("state", job.getState());
        dto.put("city", job.getCity());
        dto.put("postedAt", job.getPostedAt() != null ? job.getPostedAt().toString() : null);
        dto.put("expiresAt", job.getExpiresAt() != null ? job.getExpiresAt().toString() : null);
        dto.put("fetchedAt", job.getFetchedAt() != null ? job.getFetchedAt().toString() : null);
        dto.put("seen", job.isSeen());
        dto.put("interested", job.isInterested());
        dto.put("applied", job.isApplied());
        dto.put("appliedAt", job.getAppliedAt() != null ? job.getAppliedAt().toString() : null);
        dto.put("inProgress", job.isInProgress());
        dto.put("inProgressAt", job.getInProgressAt() != null ? job.getInProgressAt().toString() : null);
        dto.put("rejected", job.isRejected());
        dto.put("rejectedAt", job.getRejectedAt() != null ? job.getRejectedAt().toString() : null);
        dto.put("rejectedReason", job.getRejectedReason());
        dto.put("tags", job.getTags() != null
                ? Arrays.asList(job.getTags().split(","))
                : List.of());
        dto.put("companyLogoUrl", job.getCompanyLogoUrl());
        dto.put("pcd", job.getPcd() != null && job.getPcd());
        dto.put("pinned", job.getFavorited() != null && job.getFavorited());
        dto.put("notes", job.getNotes());
        dto.put("classifiedByAi", job.getClassifiedByAi() != null && job.getClassifiedByAi());
        return dto;
    }

    /**
     * Backup/export de todas as vagas — GET /api/jobs/export?format=json|csv
     * (padrão json). Sem filtro nenhum: é o banco inteiro, pensado pra
     * "salva tudo antes de mexer" ou levar os dados pra outra ferramenta,
     * não pra visualização filtrada (isso já é o GET / normal).
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false, defaultValue = "json") String format) {
        List<Map<String, Object>> vagas = jobRepository.findAll().stream()
                .sorted(Comparator.comparing(Job::getId))
                .map(this::toDto)
                .toList();
        String stamp = LocalDate.now().toString();

        if ("csv".equalsIgnoreCase(format)) {
            String[] colunas = {
                    "id", "title", "company", "url", "source", "seniority", "salary", "workplaceType",
                    "state", "city", "postedAt", "seen", "interested", "applied", "inProgress", "rejected",
                    "pinned", "notes", "tags"
            };
            StringBuilder sb = new StringBuilder();
            sb.append(String.join(",", colunas)).append('\n');
            for (Map<String, Object> v : vagas) {
                for (int i = 0; i < colunas.length; i++) {
                    if (i > 0) sb.append(',');
                    Object val = v.get(colunas[i]);
                    String texto = val instanceof List<?> lista ? String.join(";", lista.stream().map(String::valueOf).toList())
                            : (val != null ? String.valueOf(val) : "");
                    sb.append('"').append(texto.replace("\"", "\"\"")).append('"');
                }
                sb.append('\n');
            }
            byte[] bytes = sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return ResponseEntity.ok()
                    .header("Content-Type", "text/csv; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"job-radar-export-" + stamp + ".csv\"")
                    .body(bytes);
        }

        try {
            byte[] bytes = new com.fasterxml.jackson.databind.ObjectMapper()
                    .writerWithDefaultPrettyPrinter()
                    .writeValueAsBytes(vagas);
            return ResponseEntity.ok()
                    .header("Content-Type", "application/json; charset=UTF-8")
                    .header("Content-Disposition", "attachment; filename=\"job-radar-export-" + stamp + ".json\"")
                    .body(bytes);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Timeline de status de uma vaga (ver model JobEvent) — "vista em 3/8 →
     * interesse em 5/8 → aplicada em 6/8", em vez de só os 3 timestamps
     * soltos que já existiam (appliedAt/inProgressAt/rejectedAt). Só tem
     * evento a partir de quando essa tabela foi criada — vaga antiga não
     * ganha histórico retroativo, começa a acumular dali pra frente.
     * GET /api/jobs/{id}/events
     */
    @GetMapping("/{id}/events")
    public ResponseEntity<List<Map<String, Object>>> getEvents(@PathVariable Long id) {
        if (jobRepository.findById(id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        List<Map<String, Object>> eventos = jobEventRepository.findByJobIdOrderByOccurredAtAsc(id).stream()
                .map(e -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("status", e.getStatus());
                    m.put("occurredAt", e.getOccurredAt().toString());
                    return m;
                })
                .toList();
        return ResponseEntity.ok(eventos);
    }

    /**
     * Pontuação heurística (sem IA, sobreposição de tags do perfil) de todas
     * as vagas NÃO VISTAS — poder pro modo "Triagem rápida" (ordena as mais
     * prováveis primeiro) e pro badge "provável match" nos cards. Não é
     * IA de verdade, é o mesmo pré-filtro barato que já existia em
     * JarvisAssistantService#scanCompatibilidade, só que devolvido cru em
     * vez de gastar chamada de IA em cima.
     * POST /api/jobs/quick-match-scores  body: {"profile": "..."}
     */
    @PostMapping("/quick-match-scores")
    public Map<String, Object> quickMatchScores(@RequestBody Map<String, String> body) {
        String profile = body.get("profile");
        List<Job> naoVistas = jobRepository.findBySeenFalse();
        Map<Long, Integer> percentPorId = jarvisAssistantService.heuristicMatchPercents(naoVistas, profile);
        Map<String, Object> out = new HashMap<>();
        Map<String, Integer> scores = new HashMap<>();
        percentPorId.forEach((id, pct) -> scores.put(String.valueOf(id), pct));
        out.put("scores", scores);
        return out;
    }

    public record SemanticSearchResult(Job job, int similaridadePercent) {}

    /**
     * Fase 9.2 — busca semântica NA TELA PRINCIPAL, fora do chat do Hunter.
     * A busca em si (JobEmbeddingService.buscar) já existia desde a Fase 6
     * e o chat já usava (ferramenta buscarVagasPorSignificado) — faltava só
     * um jeito de chegar nela sem precisar conversar. Não gasta cota do
     * Gemini: o embedding é calculado pelo Hunter-Embed local (ver
     * EmbeddingProvider/HunterEmbeddingProvider, Fase 8/9 da migração).
     *
     * <p>Candidatas = mesma regra de "vaga ativa" que {@link #getDuplicates},
     * não recusada e não arquivada — não faz sentido a busca por significado
     * devolver vaga que o usuário já descartou ou que saiu do funil ativo.</p>
     * GET /api/jobs/semantic-search?consulta=...&limite=20
     */
    @GetMapping("/semantic-search")
    public ResponseEntity<Map<String, Object>> semanticSearch(
            @RequestParam String consulta,
            @RequestParam(required = false) Integer limite) {
        if (consulta == null || consulta.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Informe o que procurar."));
        }
        int limiteFinal = limite != null ? Math.min(30, Math.max(1, limite)) : 15;

        List<Job> candidatas = jobRepository.findAll(
                JobSpecifications.combine(JobSpecifications.notRejected(), JobSpecifications.notArchived()));
        List<JobEmbeddingService.Match> matches = jobEmbeddingService.buscar(consulta, candidatas, limiteFinal);

        List<SemanticSearchResult> resultados = matches.stream()
                .map(m -> new SemanticSearchResult(m.job(), (int) Math.round(m.similaridade() * 100)))
                .toList();

        Map<String, Object> out = new HashMap<>();
        out.put("consulta", consulta);
        out.put("resultados", resultados);
        return ResponseEntity.ok(out);
    }

    /**
     * Status da integração com IA (Gemini) — o frontend usa isso pra mostrar
     * se os recursos de IA (carta de apresentação, duplicatas, classificação)
     * estão ativos, sem nunca expor a key.
     * GET /api/jobs/ai-status
     */
    @GetMapping("/ai-status")
    public Map<String, Object> aiStatus() {
        Map<String, Object> status = new HashMap<>();
        boolean enabled = geminiService.isEnabled();
        status.put("enabled", enabled);
        status.put("model", enabled ? geminiService.getModel() : null);
        // Contagem aproximada (não é a oficial do Google) — só pra dar um
        // sinal antes do usuário esbarrar no limite do free tier.
        status.put("requestsToday", enabled ? geminiService.getRequestsToday() : null);
        if (enabled) {
            GeminiService.KeyPoolStatus pool = geminiService.getKeyPoolStatus();
            Map<String, Object> keyPool = new HashMap<>();
            keyPool.put("total", pool.total());
            keyPool.put("availableToday", pool.availableToday());
            keyPool.put("exhaustedToday", pool.exhaustedToday());
            // Fase 11.3 — separado de exhaustedToday: key rejeitada (401/403)
            // não é cota, não passa sozinho à meia-noite. Sem isso o painel
            // dizia "29 de 29 disponíveis" com o log cheio de 403.
            keyPool.put("rejectedNow", pool.rejectedNow());
            status.put("keyPool", keyPool);
        } else {
            status.put("keyPool", null);
        }
        // Fase 9.8 — orçamento diário por funcionalidade, separado do
        // keyPool acima (que é sobre a COTA das keys, não sobre quanto uma
        // funcionalidade específica já usou hoje). Sempre visível, mesmo
        // sem IA ativa (contador zerado é informação válida também).
        status.put("aiBudget", aiFeatureBudgetService.status());
        return status;
    }

    /**
     * Fixa ou desfixa uma vaga no topo da lista (toggle: fixada ↔ não fixada)
     * Vagas fixadas aparecem primeiro em qualquer aba/filtro.
     * PATCH /api/jobs/{id}/pin
     */
    @PatchMapping("/{id}/pin")
    public ResponseEntity<Map<String, Object>> togglePin(@PathVariable Long id) {
        return jobRepository.findById(id).map(job -> {
            job.setFavorited(job.getFavorited() == null || !job.getFavorited());
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }

    /**
     * Salva anotação pessoal do usuário sobre a vaga
     * PATCH /api/jobs/{id}/notes  Body: { "notes": "..." }
     */
    @PatchMapping("/{id}/notes")
    public ResponseEntity<Map<String, Object>> updateNotes(
            @PathVariable Long id, @RequestBody Map<String, String> body) {
        return jobRepository.findById(id).map(job -> {
            String text = body.getOrDefault("notes", "");
            job.setNotes(text.isBlank() ? null : text.trim());
            return ResponseEntity.ok(toDto(jobRepository.save(job)));
        }).orElse(ResponseEntity.notFound().build());
    }
}

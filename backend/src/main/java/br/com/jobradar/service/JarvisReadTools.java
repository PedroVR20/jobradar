package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobSpecifications;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

import br.com.jobradar.repository.JobRepository;

/**
 * Fase 14.3 — extraído de {@link JarvisChatService} (que tinha passado de
 * 1400 linhas). Agrupa as ferramentas do Hunter que só LEEM dado — o
 * dispatcher (executarFerramenta) e o loop de conversa continuam em
 * JarvisChatService, que agora só delega pra cá (leitura) ou pra
 * {@link JarvisWriteTools} (escrita). Zero mudança de comportamento —
 * métodos movidos verbatim, incluindo os comentários da Fase 14.1/14.4 que
 * explicam por que cada findAll() virou (ou não) uma Specification.
 *
 * <p>Package-private, mesmo padrão de {@link JarvisWriteTools} — não é API
 * pública do módulo, só existe pra dividir o arquivo gigante em pedaços
 * testáveis.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
class JarvisReadTools {

    private final JobRepository jobRepository;
    private final JarvisAssistantService jarvisAssistantService;
    private final MatchScoreService matchScoreService;
    private final SalaryPredictionService salaryPredictionService;
    private final CoverLetterService coverLetterService;
    private final JobEmbeddingService jobEmbeddingService;
    private final GmailService gmailService;

    // Fase 3.4 — cache curto pras ferramentas determinísticas que varrem o
    // catálogo inteiro (resumoFunil, metricasDeDesempenho,
    // desempenhoPorFonte). Ver SimpleTtlCache pra motivação do TTL em vez
    // de invalidação amarrada a cada escrita.
    private final SimpleTtlCache cache = new SimpleTtlCache();
    private static final int CACHE_TTL_SEGUNDOS = 20;

    // compatibilidadeComVagasDoFunil analisa DIRETO (sem pré-filtro), porque
    // o grupo já vem pequeno por natureza (é o funil curado do próprio
    // usuário) — mas ainda precisa de um teto rígido: um status como "NOVA"
    // pode ter milhares de vagas, e sem isso um pedido mal-entendido
    // estouraria a cota de IA analisando tudo. Ver bug real reportado: pediu
    // pra analisar 4 vagas da aba Interessado, o modelo usou a ferramenta
    // errada (a de vagas recentes do feed geral) e varreu 3279 vagas.
    private static final int MAX_COMPAT_FUNIL = 15;
    private static final int DEFAULT_COMPAT_FUNIL = 10;

    // estimativaSalarialDeVagas NÃO gasta IA (é o modelo de regressão puro
    // Java, ver SalaryPredictionService) — pode ter um teto bem mais folgado
    // que as ferramentas de compatibilidade sem risco de estourar cota.
    private static final int MAX_SALARIO_VAGAS = 25;
    private static final int DEFAULT_SALARIO_VAGAS = 15;

    // Mercado de vagas/salário muda com o tempo — um modelo treinado há mais
    // de 90 dias provavelmente já não reflete a faixa atual tão bem quanto
    // logo depois do treino, mesmo sem nada estar "quebrado" tecnicamente.
    private static final long MODELO_SALARIO_DIAS_DESATUALIZADO = 90;

    // Fase 14.4 — teto pras ferramentas que não tinham nenhum limite
    // explícito (historicoDaEmpresa, vagasComPrazoProximo, vagasParadas,
    // detectarDuplicatas): com o catálogo crescendo, "todas as vagas paradas
    // há mais de 10 dias" ou "todo grupo de duplicata" podia virar uma
    // resposta grande o bastante pra pesar no contexto que vai pro modelo —
    // sem estar filtrado por status/data como listarVagas, esse é o único
    // freio que sobrava. Quando corta, a ferramenta avisa quantas existiam
    // no total, o modelo sabe que houve corte.
    private static final int MAX_RESULTADOS_FERRAMENTA = 30;

    Object executarListarVagas(Map<String, Object> args) {
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        Integer dias = args.get("dias") instanceof Number n ? n.intValue() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        Integer salarioMinimo = args.get("salarioMinimo") instanceof Number n ? n.intValue() : null;
        int limite = args.get("limite") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;

        LocalDateTime postedAfter = dias != null && dias > 0 ? LocalDateTime.now().minusDays(dias) : null;

        // Fase 14.1 — status e dias viram WHERE de SQL (mesmas Specifications
        // que a listagem principal do app usa, ver specForStatus); busca e
        // salarioMinimo continuam em Java porque exigem normalização de
        // texto livre e o modelo de regressão de salário, respectivamente —
        // mas agora rodam sobre o subconjunto já filtrado pelo banco, não
        // sobre o catálogo inteiro carregado pra memória.
        Specification<Job> spec = JobSpecifications.combine(specForStatus(status), JobSpecifications.postedAfter(postedAfter));
        List<Job> filtradas = jobRepository.findAll(spec).stream()
                .filter(j -> busca == null || contemBusca(j, busca))
                .filter(j -> salarioMinimo == null || estimativaBate(j, salarioMinimo))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Map<String, Object>> resultado = filtradas.stream()
                .limit(limite)
                .map(j -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", j.getId());
                    m.put("title", j.getTitle());
                    m.put("company", j.getCompany());
                    m.put("status", statusDe(j));
                    m.put("url", j.getUrl());
                    m.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
                    // Anotação pessoal que o próprio usuário escreveu no card da vaga
                    // (campo "📝 Adicionar nota" no frontend) — é a fonte real de
                    // pendência/próximo passo de cada candidatura, não invente.
                    m.put("notes", j.getNotes());
                    return (Map<String, Object>) m;
                })
                .toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("totalEncontradas", filtradas.size());
        out.put("vagas", resultado);
        return out;
    }

    // Sem modificador de acesso (package-private) de propósito — permite
    // teste unitário direto de JarvisReadToolsTest sem precisar expor isso
    // como API pública nem recorrer a reflection.
    boolean statusBate(Job j, String status) {
        if (status == null) return true;
        return switch (status) {
            case "NOVA" -> !j.isSeen() && !j.isRejected();
            case "VISTA" -> j.isSeen() && !j.isInterested() && !j.isApplied() && !j.isRejected();
            case "INTERESSADO" -> j.isInterested() && !j.isApplied() && !j.isRejected();
            case "APLICADA" -> j.isApplied() && !j.isInProgress() && !j.isRejected();
            case "ANDAMENTO" -> j.isApplied() && j.isInProgress() && !j.isRejected();
            case "RECUSADA" -> j.isRejected();
            default -> true;
        };
    }

    // Fase 14.1 — equivalente em SQL do que statusBate() decide em Java,
    // reaproveitando as mesmas Specifications que JobQueryService usa pra
    // listagem principal (mesmos 6 buckets, mesma regra). null (status não
    // informado ou desconhecido) devolve null, que JobSpecifications.combine
    // já sabe ignorar.
    private Specification<Job> specForStatus(String status) {
        if (status == null) return null;
        return switch (status) {
            case "NOVA" -> JobSpecifications.onlyNew();
            case "VISTA" -> JobSpecifications.onlySeen();
            case "INTERESSADO" -> JobSpecifications.onlyInteressado();
            case "APLICADA" -> JobSpecifications.onlyApplied();
            case "ANDAMENTO" -> JobSpecifications.onlyInProgress();
            case "RECUSADA" -> JobSpecifications.onlyRejected();
            default -> null;
        };
    }

    private String statusDe(Job j) {
        if (j.isRejected()) return "RECUSADA";
        if (j.isInProgress()) return "ANDAMENTO";
        if (j.isApplied()) return "APLICADA";
        if (j.isInterested()) return "INTERESSADO";
        if (j.isSeen()) return "VISTA";
        return "NOVA";
    }

    boolean contemBusca(Job j, String busca) {
        String haystack = (j.getTitle() + " " + j.getCompany() + " " + (j.getTags() != null ? j.getTags() : "")).toLowerCase();
        return haystack.contains(busca.toLowerCase());
    }

    // Mesmo modelo de regressão puro Java das outras ferramentas de salário
    // (SalaryPredictionService) — vaga sem estimativa disponível nunca bate
    // um filtro de salário mínimo (não dá pra confirmar, não assume).
    private boolean estimativaBate(Job j, int salarioMinimo) {
        List<String> tags = j.getTags() == null || j.getTags().isBlank()
                ? List.of() : Arrays.asList(j.getTags().split(","));
        return salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState())
                .map(estimativa -> estimativa >= salarioMinimo)
                .orElse(false);
    }

    Object executarResumoFunil() {
        return cache.getOuCalcula("resumoFunil", CACHE_TTL_SEGUNDOS, this::calcularResumoFunil);
    }

    private Object calcularResumoFunil() {
        // Cada vaga cai em EXATAMENTE um bucket (statusDe), o mesmo critério
        // que listarVagas usa e que as abas do funil no frontend usam pra
        // filtrar — por isso os números aqui batem com o que o usuário vê
        // clicando em cada aba. Antes essa contagem usava countByAppliedTrue()
        // (bruto, conta toda vaga que já foi aplicada alguma vez, incluindo as
        // que hoje estão "em andamento" ou foram recusadas depois) — o usuário
        // reportou exatamente essa confusão: Jarvis dizia "46 aplicadas" com a
        // aba "Aplicadas" mostrando só 1 (as outras 45 já migraram pra "em
        // andamento" ou "recusada"). Mantemos o bruto num campo à parte,
        // explicitamente rotulado, pra quem perguntar "quantas vezes já
        // apliquei no total".
        //
        // Fase 14.1 — deixado como findAll() de propósito, diferente das
        // outras ferramentas desta fase: bucket por vaga (statusDe) usa os
        // mesmos 6 critérios de JobSpecifications, mas trocar por 6 count()
        // separados (um por bucket) tornaria esse método bem mais difícil
        // de testar com repositório mockado (Specification não é
        // comparável por valor no Mockito) pra um ganho pequeno — já roda
        // atrás do cache de 20s (CACHE_TTL_SEGUNDOS), então o custo real é
        // "uma vez a cada 20s", não por request.
        List<Job> todas = jobRepository.findAll();
        Map<String, Long> porBucket = new LinkedHashMap<>();
        for (String bucket : List.of("NOVA", "VISTA", "INTERESSADO", "APLICADA", "ANDAMENTO", "RECUSADA")) {
            porBucket.put(bucket, 0L);
        }
        for (Job j : todas) {
            porBucket.merge(statusDe(j), 1L, Long::sum);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("total", (long) todas.size());
        m.put("novas", porBucket.get("NOVA"));
        m.put("vistas", porBucket.get("VISTA"));
        m.put("interessadas", porBucket.get("INTERESSADO"));
        m.put("aplicadas", porBucket.get("APLICADA"));
        m.put("emAndamento", porBucket.get("ANDAMENTO"));
        m.put("recusadas", porBucket.get("RECUSADA"));
        m.put("totalHistoricoAplicadas", todas.stream().filter(Job::isApplied).count());
        return m;
    }

    Object executarCompatibilidade(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        int dias = args.get("dias") instanceof Number n ? Math.max(1, n.intValue()) : 1;
        JarvisAssistantService.CompatibilityResult r = jarvisAssistantService.scanCompatibilidade(candidateProfile, dias, feedbackContext);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", r.available());
        m.put("totalConsiderados", r.totalConsiderados());
        m.put("totalAnalisadosPorIa", r.totalAnalisadosPorIa());
        // pontosFortes/pontosFaltando entravam só no resultado da versão "do
        // funil" — a interface não conseguia montar o mesmo card com "✅
        // pontos fortes" / "⚠️ pontos a desenvolver" + botão de plano de ação
        // pra esse caminho, mesmo o CompatibilityHit já carregando os dois
        // campos (só não eram repassados pro mapa que volta pro chat).
        m.put("hits", r.hits().stream().map(h -> {
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", h.job().getId());
            hit.put("titulo", h.job().getTitle());
            hit.put("empresa", h.job().getCompany());
            hit.put("url", h.job().getUrl());
            hit.put("score", h.score());
            hit.put("resumo", h.resumo());
            hit.put("pontosFortes", h.pontosFortes());
            hit.put("pontosFaltando", h.pontosFaltando());
            return (Map<String, Object>) hit;
        }).toList());
        if (r.errorMessage() != null) m.put("erro", r.errorMessage());
        return m;
    }

    // Diferente de executarCompatibilidade (que faz um pré-filtro sem IA
    // antes de escolher as 5 mais promissoras num feed potencialmente
    // gigante), aqui o conjunto já vem filtrado por status — pequeno e
    // curado pelo próprio usuário por natureza — então analisa TODAS as que
    // passarem no filtro, direto, sem pré-seleção. O teto (MAX_COMPAT_FUNIL)
    // é só uma trava de segurança contra pedir isso num status que por
    // acaso tenha muitas vagas (ex: "NOVA" pode ter milhares).
    Object executarCompatibilidadeFunil(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        if (candidateProfile == null || candidateProfile.isBlank()) {
            return Map.of("erro", "Salve seu perfil/currículo em ⚙️ Configurações primeiro, ou cole ele aqui na conversa.");
        }

        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        int limite = args.get("limite") instanceof Number n
                ? Math.min(MAX_COMPAT_FUNIL, Math.max(1, n.intValue()))
                : DEFAULT_COMPAT_FUNIL;

        // Fase 14.1 — status vira WHERE de SQL (ver specForStatus).
        List<Job> filtradas = jobRepository.findAll(specForStatus(status)).stream()
                .filter(j -> busca == null || contemBusca(j, busca))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();

        List<Job> analisar = filtradas.stream().limit(limite).toList();

        List<Map<String, Object>> hits = new ArrayList<>();
        for (Job j : analisar) {
            MatchScoreService.MatchOutcome outcome = matchScoreService.calcular(j, candidateProfile, feedbackContext);
            if (!outcome.ok()) {
                log.warn("Jarvis: falha ao calcular compatibilidade da vaga {} pro funil: {}", j.getId(), outcome.errorMessage());
                continue;
            }
            MatchScoreService.MatchResult r = outcome.result();
            Map<String, Object> hit = new LinkedHashMap<>();
            hit.put("id", j.getId());
            hit.put("titulo", j.getTitle());
            hit.put("empresa", j.getCompany());
            hit.put("url", j.getUrl());
            hit.put("score", r.score());
            hit.put("resumo", r.resumo());
            hit.put("pontosFortes", r.pontosFortes());
            hit.put("pontosFaltando", r.pontosFaltando());
            hits.add(hit);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("available", true);
        m.put("totalConsiderados", filtradas.size());
        m.put("totalAnalisadosPorIa", hits.size());
        m.put("hits", hits);
        if (filtradas.size() > analisar.size()) {
            m.put("erro", "Só analisei as " + analisar.size() + " mais recentes — tinha " + filtradas.size() + " vagas nesse status no total.");
        }
        return m;
    }

    // Não usa IA generativa — reaproveita o mesmo modelo de regressão puro
    // Java que já roda no botão 💰 de cada card (SalaryPredictionService).
    // Sem custo de cota, então o teto aqui é só sobre "quantidade razoável
    // pra caber num dashboard", não sobre economizar chamada de IA.
    Object executarEstimativaSalarial(Map<String, Object> args) {
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String busca = args.get("busca") instanceof String s && !s.isBlank() ? s : null;
        int limite = args.get("limite") instanceof Number n
                ? Math.min(MAX_SALARIO_VAGAS, Math.max(1, n.intValue()))
                : DEFAULT_SALARIO_VAGAS;

        // Fase 14.1 — status vira WHERE de SQL (ver specForStatus).
        List<Job> filtradas = jobRepository.findAll(specForStatus(status)).stream()
                .filter(j -> busca == null || contemBusca(j, busca))
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limite)
                .toList();

        List<Map<String, Object>> vagas = new ArrayList<>();
        for (Job j : filtradas) {
            List<String> tags = j.getTags() == null || j.getTags().isBlank()
                    ? List.of() : Arrays.asList(j.getTags().split(","));
            Optional<Long> estimativa = salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState());

            Map<String, Object> vaga = new LinkedHashMap<>();
            vaga.put("id", j.getId());
            vaga.put("titulo", j.getTitle());
            vaga.put("empresa", j.getCompany());
            vaga.put("url", j.getUrl());
            vaga.put("estimativa", estimativa.orElse(null));
            vaga.put("salarioInformado", j.getSalary());
            vagas.add(vaga);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("modeloDisponivel", salaryPredictionService.isLoaded());
        m.put("totalEncontradas", filtradas.size());
        m.put("vagas", vagas);

        // Honestidade sobre a incerteza: erro médio real do modelo (ver
        // comentário na classe do serviço — ~43%, bem longe de exato) e
        // avisa se o modelo tá velho (nunca foi retreinado desde que os
        // dados do feed provavelmente mudaram de mercado/faixa salarial).
        SalaryPredictionService.ModelInfo info = salaryPredictionService.getModelInfo();
        if (info != null) {
            m.put("margemErroPercent", Math.round(info.maePercent()));
            m.put("modeloTreinadoEm", info.trainedAt());
            if (info.trainedAt() != null) {
                try {
                    long dias = java.time.temporal.ChronoUnit.DAYS.between(
                            java.time.LocalDate.parse(info.trainedAt()), java.time.LocalDate.now());
                    m.put("modeloDesatualizado", dias > MODELO_SALARIO_DIAS_DESATUALIZADO);
                    m.put("modeloDiasDesdeTreino", dias);
                } catch (Exception ignored) {
                    // trainedAt em formato inesperado — não trava a resposta por isso
                }
            }
        }
        return m;
    }

    // Usa IA de verdade (1 chamada) — mesmo serviço do botão "Carta" de cada
    // card. candidateProfile/feedbackContext já chegam prontos (mesmo
    // caminho que compatibilidade/carta usam nos outros lugares do app).
    Object executarGerarCarta(Map<String, Object> args, String candidateProfile, String feedbackContext) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String contextoExtra = args.get("contextoExtra") instanceof String s ? s : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga — chame listarVagas primeiro se só tiver título/empresa.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String extra = contextoExtra != null && candidateProfile != null
                ? candidateProfile + "\n\n" + contextoExtra
                : (contextoExtra != null ? contextoExtra : candidateProfile);
        GeminiService.GeminiResult resultado = coverLetterService.gerar(job, extra, feedbackContext);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagaId", vagaId);
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        if (!resultado.ok()) {
            m.put("erro", resultado.errorMessage());
        } else {
            m.put("carta", resultado.text());
        }
        return m;
    }

    // Não usa IA — mesma conta que /api/jobs/metrics já faz pro dashboard de
    // métricas, só reimplementada aqui porque esse cálculo vive dentro do
    // controller (não dá pra injetar controller num service). Fica curto o
    // bastante pra não valer a pena um refactor de extrair um serviço à
    // parte só por causa disso.
    Object executarMetricasDeDesempenho() {
        return cache.getOuCalcula("metricasDeDesempenho", CACHE_TTL_SEGUNDOS, this::calcularMetricasDeDesempenho);
    }

    private Object calcularMetricasDeDesempenho() {
        List<Job> aplicadas = jobRepository.findByAppliedTrue();
        long total = aplicadas.size();
        long emAndamento = aplicadas.stream().filter(Job::isInProgress).count();
        long recusadas = aplicadas.stream().filter(Job::isRejected).count();
        long aguardandoRetorno = Math.max(0, total - emAndamento - recusadas);
        Double taxaResposta = total == 0 ? null : Math.round((emAndamento + recusadas) * 1000.0 / total) / 10.0;

        OptionalDouble avgAndamento = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getInProgressAt() != null)
                .mapToLong(j -> java.time.Duration.between(j.getAppliedAt(), j.getInProgressAt()).toDays())
                .average();
        OptionalDouble avgRecusa = aplicadas.stream()
                .filter(j -> j.getAppliedAt() != null && j.getRejectedAt() != null)
                .mapToLong(j -> java.time.Duration.between(j.getAppliedAt(), j.getRejectedAt()).toDays())
                .average();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalAplicadas", total);
        m.put("emAndamento", emAndamento);
        m.put("recusadas", recusadas);
        m.put("aguardandoRetorno", aguardandoRetorno);
        m.put("taxaRespostaPercent", taxaResposta);
        m.put("tempoMedioAteAndamentoDias", avgAndamento.isPresent() ? Math.round(avgAndamento.getAsDouble() * 10) / 10.0 : null);
        m.put("tempoMedioAteRecusaDias", avgRecusa.isPresent() ? Math.round(avgRecusa.getAsDouble() * 10) / 10.0 : null);
        return m;
    }

    // Não usa IA — expiresAt é LocalDate (data, sem hora), já existe no
    // model e aparece nos cards ("Fecha em Xd"), só nunca tinha ferramenta
    // pro Hunter enxergar.
    Object executarVagasComPrazoProximo(Map<String, Object> args) {
        int diasMaximo = args.get("diasMaximo") instanceof Number n ? Math.max(1, n.intValue()) : 7;
        java.time.LocalDate hoje = java.time.LocalDate.now();
        java.time.LocalDate limite = hoje.plusDays(diasMaximo);

        // Fase 14.1 — não recusada + prazo no intervalo vira WHERE de SQL
        // (ver JobSpecifications.notRejected/expiraEntre).
        Specification<Job> spec = JobSpecifications.combine(JobSpecifications.notRejected(), JobSpecifications.expiraEntre(hoje, limite));
        List<Job> comPrazo = jobRepository.findAll(spec).stream()
                .sorted(Comparator.comparing(Job::getExpiresAt))
                .toList();

        List<Map<String, Object>> vagas = comPrazo.stream().limit(MAX_RESULTADOS_FERRAMENTA).map(j -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("url", j.getUrl());
            v.put("status", statusDe(j));
            v.put("fechaEm", j.getExpiresAt().toString());
            v.put("diasRestantes", java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.now(), j.getExpiresAt()));
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diasMaximo", diasMaximo);
        m.put("totalEncontradas", comPrazo.size());
        m.put("vagas", vagas);
        // Fase 14.4
        if (comPrazo.size() > vagas.size()) {
            m.put("erro", "Só listei as " + vagas.size() + " com prazo mais próximo — tinha " + comPrazo.size() + " no total.");
        }
        return m;
    }

    // Não usa IA generativa de propósito (diferente do endpoint /duplicates
    // da UI, que faz uma segunda checagem via Gemini) — versão enxuta só de
    // similaridade de texto pra não gastar cota numa ferramenta de chat que
    // o modelo pode decidir chamar sem querer. Falso positivo ocasional é
    // aceitável aqui — o resultado já avisa que é só indício.
    Object executarDetectarDuplicatas() {
        // Fase 1.6 — filtro empurrado pro SQL em vez de Java (ver JobSpecifications.notRejected).
        List<Job> ativas = jobRepository.findAll(JobSpecifications.notRejected());
        Map<String, List<Job>> porEmpresa = new LinkedHashMap<>();
        for (Job j : ativas) {
            String chave = j.getCompany() == null ? "" : j.getCompany().trim().toLowerCase();
            if (chave.isBlank()) continue;
            porEmpresa.computeIfAbsent(chave, k -> new ArrayList<>()).add(j);
        }

        List<Map<String, Object>> grupos = new ArrayList<>();
        for (List<Job> candidatas : porEmpresa.values()) {
            if (candidatas.size() < 2) continue;
            for (int i = 0; i < candidatas.size(); i++) {
                for (int k = i + 1; k < candidatas.size(); k++) {
                    Job a = candidatas.get(i);
                    Job b = candidatas.get(k);
                    if (!Objects.equals(a.getSeniority(), b.getSeniority())) continue;
                    double sim = jaccardSimples(a.getTitle(), b.getTitle());
                    if (sim < 0.6) continue;
                    Map<String, Object> grupo = new LinkedHashMap<>();
                    grupo.put("empresa", a.getCompany());
                    grupo.put("vagas", List.of(
                            Map.of("id", a.getId(), "titulo", a.getTitle(), "fonte", a.getSource(), "url", a.getUrl()),
                            Map.of("id", b.getId(), "titulo", b.getTitle(), "fonte", b.getSource(), "url", b.getUrl())
                    ));
                    grupos.add(grupo);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<>();
        List<Map<String, Object>> gruposLimitados = grupos.stream().limit(MAX_RESULTADOS_FERRAMENTA).toList();
        m.put("grupos", gruposLimitados);
        // Fase 14.4
        if (grupos.size() > gruposLimitados.size()) {
            m.put("erro", "Só listei os " + gruposLimitados.size() + " primeiros grupos — achei " + grupos.size() + " no total.");
        }
        return m;
    }

    private double jaccardSimples(String tituloA, String tituloB) {
        Set<String> a = new HashSet<>(Arrays.asList((tituloA == null ? "" : tituloA.toLowerCase()).split("\\s+")));
        Set<String> b = new HashSet<>(Arrays.asList((tituloB == null ? "" : tituloB.toLowerCase()).split("\\s+")));
        if (a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> uniao = new HashSet<>(a);
        uniao.addAll(b);
        return (double) inter.size() / uniao.size();
    }

    // Não usa IA — cruza fonte × status pra ver de onde vêm as vagas que
    // realmente avançam (aplicadas e, dentro dessas, quantas viraram
    // andamento) — não é só "quantas vagas cada fonte tem".
    Object executarDesempenhoPorFonte() {
        return cache.getOuCalcula("desempenhoPorFonte", CACHE_TTL_SEGUNDOS, this::calcularDesempenhoPorFonte);
    }

    // Fase 14.1 — era findAll() do catálogo inteiro (todas as ~6000 vagas)
    // pra agrupar em Java; um GROUP BY (ver JobRepository.desempenhoPorFonte)
    // responde os mesmos 3 agregados numa query só.
    private Object calcularDesempenhoPorFonte() {
        List<Map<String, Object>> fontes = jobRepository.desempenhoPorFonte().stream()
                .map(p -> {
                    Map<String, Object> f = new LinkedHashMap<>();
                    f.put("fonte", p.getFonte() == null ? "DESCONHECIDA" : p.getFonte());
                    f.put("totalVagas", p.getTotal());
                    f.put("aplicadas", p.getAplicadas());
                    f.put("emAndamento", p.getEmAndamento());
                    return (Map<String, Object>) f;
                })
                .sorted((a, b) -> Long.compare((long) b.get("emAndamento"), (long) a.get("emAndamento")))
                .toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fontes", fontes);
        return m;
    }

    // Não usa IA — busca livre por nome de empresa (contains, case-insensitive),
    // qualquer status inclusive recusada, pra dar contexto de histórico.
    Object executarHistoricoDaEmpresa(Map<String, Object> args) {
        String empresa = args.get("empresa") instanceof String s && !s.isBlank() ? s.toLowerCase() : null;
        if (empresa == null) {
            return Map.of("erro", "Preciso do nome da empresa.");
        }
        // Fase 14.1 — LIKE vira WHERE de SQL (ver JobSpecifications.byCompanyContainsIgnoreCase).
        List<Job> encontradas = jobRepository.findAll(JobSpecifications.byCompanyContainsIgnoreCase(empresa)).stream()
                .sorted(Comparator.comparing(Job::getPostedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        List<Map<String, Object>> vagas = encontradas.stream().limit(MAX_RESULTADOS_FERRAMENTA).map(j -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("status", statusDe(j));
            v.put("url", j.getUrl());
            v.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
            return (Map<String, Object>) v;
        }).toList();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("totalEncontradas", encontradas.size());
        m.put("vagas", vagas);
        // Fase 14.4
        if (encontradas.size() > vagas.size()) {
            m.put("erro", "Só listei as " + vagas.size() + " mais recentes — tinha " + encontradas.size() + " no total.");
        }
        return m;
    }

    // Não decide sozinho a "prioridade" com uma fórmula — devolve os 3 sinais
    // crus (parada há mais tempo, prazo mais próximo, maior match heurístico
    // entre as não vistas) e deixa o próprio modelo sintetizar em texto qual
    // vale mais a pena agora, seguindo a orientação da SYSTEM_INSTRUCTION.
    // Reaproveita executarVagasParadas/executarVagasComPrazoProximo (mesmos
    // dados que essas ferramentas já expõem individualmente) em vez de
    // duplicar a lógica de filtro.
    @SuppressWarnings("unchecked")
    Object executarOQueFazerAgora(String candidateProfile) {
        Map<String, Object> paradas = (Map<String, Object>) executarVagasParadas(Map.of());
        Map<String, Object> prazos = (Map<String, Object>) executarVagasComPrazoProximo(Map.of());

        List<Map<String, Object>> paradasTop = ((List<Map<String, Object>>) paradas.get("vagas")).stream()
                .limit(3).toList();
        List<Map<String, Object>> prazosTop = ((List<Map<String, Object>>) prazos.get("vagas")).stream()
                .limit(3).toList();

        List<Job> naoVistas = jobRepository.findBySeenFalse();
        Map<Long, Integer> scores = jarvisAssistantService.heuristicMatchPercents(naoVistas, candidateProfile);
        List<Map<String, Object>> matchesTop = naoVistas.stream()
                .filter(j -> scores.getOrDefault(j.getId(), 0) >= 50)
                .sorted(Comparator.comparingInt((Job j) -> scores.getOrDefault(j.getId(), 0)).reversed())
                .limit(3)
                .map(j -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("id", j.getId());
                    v.put("titulo", j.getTitle());
                    v.put("empresa", j.getCompany());
                    v.put("url", j.getUrl());
                    v.put("matchPercent", scores.get(j.getId()));
                    return (Map<String, Object>) v;
                })
                .toList();

        // Fase 14.4 — totalEncontradas (não o tamanho de "vagas") porque as
        // duas ferramentas reaproveitadas aqui agora podem vir cortadas
        // pelo teto de resultado; usar vagas.size() sub-contaria o total
        // real quando isso acontece.
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("candidaturasParadas", Map.of("total", paradas.get("totalEncontradas"), "top", paradasTop));
        m.put("prazosProximos", Map.of("total", prazos.get("totalEncontradas"), "top", prazosTop));
        m.put("vagasNovasComBomMatch", Map.of(
                "perfilDisponivel", candidateProfile != null && !candidateProfile.isBlank(),
                "top", matchesTop
        ));
        return m;
    }

    // Não usa IA — sobreposição de tags (SalaryPredictionService.
    // extractTagsFromText, o mesmo vocabulário do modelo de salário) entre o
    // perfil salvo e o feed inteiro de vagas ativas. É um indício simples de
    // demanda de mercado, não uma pesquisa de verdade — a descrição da
    // ferramenta já avisa o modelo disso.
    Object executarCompararComMercado(String candidateProfile) {
        if (candidateProfile == null || candidateProfile.isBlank()) {
            return Map.of("erro", "Preciso do perfil/currículo salvo em Configurações pra comparar com o mercado.");
        }
        Set<String> perfilTags = salaryPredictionService.extractTagsFromText(candidateProfile);
        if (perfilTags.isEmpty()) {
            return Map.of("erro", "Não reconheci nenhuma tecnologia conhecida no seu perfil salvo.");
        }

        // Fase 14.1 — "não recusada" vira WHERE de SQL em vez de filtro em
        // Java depois de carregar tudo (mesmo padrão de detectarDuplicatas/
        // buscarVagasPorSignificado).
        Map<String, Long> contagem = new HashMap<>();
        for (Job j : jobRepository.findAll(JobSpecifications.notRejected())) {
            if (j.getTags() == null || j.getTags().isBlank()) continue;
            for (String tagBruta : j.getTags().split(",")) {
                String tag = tagBruta.trim().toLowerCase();
                if (!tag.isBlank()) contagem.merge(tag, 1L, Long::sum);
            }
        }

        List<Map<String, Object>> tagsNoPerfilEPedidas = perfilTags.stream()
                .filter(contagem::containsKey)
                .sorted(Comparator.comparingLong((String t) -> contagem.getOrDefault(t, 0L)).reversed())
                .limit(10)
                .map(t -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("tag", t);
                    v.put("vagasComEssaTag", contagem.get(t));
                    return v;
                })
                .toList();

        List<Map<String, Object>> tagsMaisPedidasFaltando = contagem.entrySet().stream()
                .filter(e -> !perfilTags.contains(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(10)
                .map(e -> {
                    Map<String, Object> v = new LinkedHashMap<>();
                    v.put("tag", e.getKey());
                    v.put("vagasComEssaTag", e.getValue());
                    return v;
                })
                .toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tagsDoPerfilReconhecidas", perfilTags.size());
        m.put("tagsDoPerfilQueBatemComOMercado", tagsNoPerfilEPedidas);
        m.put("tagsMaisPedidasQueFaltamNoPerfil", tagsMaisPedidasFaltando);
        return m;
    }

    // Não usa IA — só devolve tudo que já está salvo sobre cada vaga (mais a
    // estimativa salarial gratuita, mesmo modelo do botão 💰). Cobre tanto
    // "detalha essa vaga" (1 id) quanto "compara essas vagas" (2+ ids) — o
    // frontend decide como desenhar com base em quantos itens vieram.
    @SuppressWarnings("unchecked")
    Object executarDetalharVagas(Map<String, Object> args) {
        List<Number> idsRaw = args.get("vagaIds") instanceof List<?> l
                ? (List<Number>) l.stream().filter(Number.class::isInstance).toList()
                : List.of();
        if (idsRaw.isEmpty()) {
            return Map.of("erro", "Preciso do id de pelo menos uma vaga — chame listarVagas primeiro se só tiver título/empresa.");
        }
        List<Long> ids = idsRaw.stream().map(Number::longValue).distinct().limit(10).toList();

        List<Map<String, Object>> vagas = new ArrayList<>();
        List<Long> naoEncontradas = new ArrayList<>();
        for (Long id : ids) {
            Optional<Job> jobOpt = jobRepository.findById(id);
            if (jobOpt.isEmpty()) {
                naoEncontradas.add(id);
                continue;
            }
            Job j = jobOpt.get();
            List<String> tags = j.getTags() == null || j.getTags().isBlank()
                    ? List.of() : Arrays.asList(j.getTags().split(","));
            Optional<Long> estimativa = salaryPredictionService.predict(j.getSeniority(), tags, j.getWorkplaceType(), j.getState());

            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("url", j.getUrl());
            v.put("status", statusDe(j));
            v.put("fonte", j.getSource());
            v.put("senioridade", j.getSeniority());
            v.put("modalidade", j.getWorkplaceType());
            v.put("estado", j.getState());
            v.put("cidade", j.getCity());
            v.put("tags", tags);
            v.put("salarioInformado", j.getSalary());
            v.put("salarioEstimado", estimativa.orElse(null));
            v.put("postedAt", j.getPostedAt() != null ? j.getPostedAt().toString() : null);
            v.put("expiraEm", j.getExpiresAt() != null ? j.getExpiresAt().toString() : null);
            v.put("notas", j.getNotes());
            vagas.add(v);
        }

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagas", vagas);
        m.put("modo", vagas.size() >= 2 ? "comparacao" : "detalhe");
        if (!naoEncontradas.isEmpty()) {
            m.put("erro", "Não achei a(s) vaga(s) de id " + naoEncontradas + " — pode ter sido apagada.");
        }
        return m;
    }

    // Não usa IA — pontua outras vagas do feed geral por quantas tags a vaga
    // de referência tem em comum (Jaccard simples), com bônus se senioridade
    // e modalidade também baterem. Não filtra por status do funil de
    // propósito: a ideia é achar OUTRAS vagas ainda não vistas/curadas,
    // parecidas com uma que o usuário já gostou.
    Object executarVagasParecidas(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga de referência — chame listarVagas primeiro se só tiver título/empresa.");
        }
        Optional<Job> refOpt = jobRepository.findById(vagaId);
        if (refOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job ref = refOpt.get();
        Set<String> tagsRef = tagsDe(ref);
        int limite = args.get("limite") instanceof Number n
                ? Math.min(15, Math.max(1, n.intValue()))
                : 8;

        record Candidata(Job job, int score, Set<String> tagsComuns) {}

        // Fase 14.1 — deixado como findAll() de propósito: a pontuação por
        // sobreposição de tags precisa comparar a vaga de referência com
        // TODA vaga do catálogo (inclusive recusada — ver comentário da
        // ferramenta acima, "não filtra por status de propósito"),
        // diferente das outras ferramentas desta fase, onde reduzir por
        // status/data não muda o que a ferramenta promete devolver.
        List<Candidata> candidatas = jobRepository.findAll().stream()
                .filter(j -> !j.getId().equals(ref.getId()))
                .map(j -> {
                    Set<String> tagsJ = tagsDe(j);
                    Set<String> comuns = new LinkedHashSet<>(tagsRef);
                    comuns.retainAll(tagsJ);
                    int score = comuns.size() * 2;
                    if (Objects.equals(j.getSeniority(), ref.getSeniority())) score += 1;
                    if (Objects.equals(j.getWorkplaceType(), ref.getWorkplaceType())) score += 1;
                    return new Candidata(j, score, comuns);
                })
                .filter(c -> c.score() > 0)
                .sorted(Comparator.comparingInt(Candidata::score).reversed()
                        .thenComparing(c -> c.job().getPostedAt(), Comparator.nullsLast(Comparator.reverseOrder())))
                .limit(limite)
                .toList();

        List<Map<String, Object>> vagas = candidatas.stream().map(c -> {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", c.job().getId());
            v.put("titulo", c.job().getTitle());
            v.put("empresa", c.job().getCompany());
            v.put("status", statusDe(c.job()));
            v.put("url", c.job().getUrl());
            v.put("tagsEmComum", c.tagsComuns());
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("vagaReferencia", Map.of("id", ref.getId(), "titulo", ref.getTitle(), "empresa", ref.getCompany()));
        m.put("vagas", vagas);
        if (vagas.isEmpty()) {
            m.put("erro", "Não achei nenhuma vaga parecida — essa vaga tem poucas tags salvas pra comparar.");
        }
        return m;
    }

    private Set<String> tagsDe(Job j) {
        if (j.getTags() == null || j.getTags().isBlank()) return Set.of();
        return Arrays.stream(j.getTags().split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    // Não usa IA — só compara appliedAt/inProgressAt com hoje. Só olha vaga
    // ATIVA (aplicada ou em andamento, nunca recusada): "parada" só faz
    // sentido pra candidatura que ainda tá em jogo, não pra uma que já
    // terminou de um jeito ou de outro. INTERESSADO fica de fora de
    // propósito — não tem timestamp próprio (Job não guarda "interestedAt"),
    // e "interessado há muito tempo" não é bem o mesmo problema de "apliquei
    // e não veio resposta".
    // Package-private de propósito, mesmo motivo de statusBate — testável
    // direto por JarvisReadToolsTest.
    Object executarVagasParadas(Map<String, Object> args) {
        int diasMinimo = args.get("diasMinimo") instanceof Number n ? Math.max(1, n.intValue()) : 10;
        LocalDateTime limite = LocalDateTime.now().minusDays(diasMinimo);

        record Parada(Job job, LocalDateTime referencia, String status) {}

        // Fase 14.1 — "aplicada e não recusada" vira WHERE de SQL (ver
        // JobSpecifications.appliedNaoRejeitada) — cobre os dois buckets
        // (APLICADA e ANDAMENTO) porque o modelo só marca inProgress=true
        // quando applied já é true.
        List<Parada> paradas = new ArrayList<>();
        for (Job j : jobRepository.findAll(JobSpecifications.appliedNaoRejeitada())) {
            if (j.isApplied() && !j.isInProgress() && j.getAppliedAt() != null && j.getAppliedAt().isBefore(limite)) {
                paradas.add(new Parada(j, j.getAppliedAt(), "APLICADA"));
            } else if (j.isInProgress() && j.getInProgressAt() != null && j.getInProgressAt().isBefore(limite)) {
                paradas.add(new Parada(j, j.getInProgressAt(), "ANDAMENTO"));
            }
        }
        paradas.sort(Comparator.comparing(Parada::referencia));

        List<Map<String, Object>> vagas = paradas.stream().limit(MAX_RESULTADOS_FERRAMENTA).map(p -> {
            long dias = java.time.temporal.ChronoUnit.DAYS.between(p.referencia(), LocalDateTime.now());
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", p.job().getId());
            v.put("titulo", p.job().getTitle());
            v.put("empresa", p.job().getCompany());
            v.put("url", p.job().getUrl());
            v.put("status", p.status());
            v.put("diasParada", dias);
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("diasMinimo", diasMinimo);
        m.put("totalEncontradas", paradas.size());
        m.put("vagas", vagas);
        // Fase 14.4
        if (paradas.size() > vagas.size()) {
            m.put("erro", "Só listei as " + vagas.size() + " paradas há mais tempo — tinha " + paradas.size() + " no total.");
        }
        return m;
    }

    // Não usa a IA generativa do chat — o modelo de embedding tem quota
    // separada e bem mais folgada (ver comentário em GeminiService.
    // embedContent). Candidatas = todas as vagas não recusadas com embedding
    // já salvo (vaga sem embedding simplesmente não concorre, não quebra a
    // busca — ver JobEmbeddingService.buscar).
    Object executarBuscaSemantica(Map<String, Object> args) {
        String consulta = args.get("consulta") instanceof String s && !s.isBlank() ? s : null;
        if (consulta == null) {
            return Map.of("erro", "Preciso saber o que procurar.");
        }
        int limite = args.get("limite") instanceof Number n ? Math.min(20, Math.max(1, n.intValue())) : 10;

        // Fase 1.6 — filtro empurrado pro SQL: vaga recusada nunca entra na
        // busca semântica de qualquer forma, então nem carrega da memória
        // (economiza justo a coluna mais pesada da tabela, o embedding).
        List<Job> candidatas = jobRepository.findAll(JobSpecifications.notRejected());
        List<JobEmbeddingService.Match> matches = jobEmbeddingService.buscar(consulta, candidatas, limite);

        List<Map<String, Object>> vagas = matches.stream().map(match -> {
            Job j = match.job();
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", j.getId());
            v.put("titulo", j.getTitle());
            v.put("empresa", j.getCompany());
            v.put("url", j.getUrl());
            v.put("status", statusDe(j));
            v.put("similaridadePercent", Math.round(match.similaridade() * 100));
            return (Map<String, Object>) v;
        }).toList();

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("consulta", consulta);
        m.put("vagas", vagas);
        return m;
    }

    Object executarVerificarEmailsDeVagas(Map<String, Object> args) {
        int dias = args.get("dias") instanceof Number n ? n.intValue() : 7;
        GmailService.BuscaResultado resultado = gmailService.buscarVagasNosEmails(dias);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("conectado", resultado.conectado());
        m.put("vagas", resultado.vagas());
        if (resultado.erro() != null) m.put("erro", resultado.erro());
        return m;
    }
}

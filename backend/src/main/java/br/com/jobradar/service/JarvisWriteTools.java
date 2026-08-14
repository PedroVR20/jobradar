package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Fase 3.6 — extraído de {@link JarvisChatService} (que tinha passado de
 * 2000 linhas). Agrupa as ÚNICAS ferramentas do Hunter que mudam dado de
 * verdade: fixarVaga, adicionarVagaManual, apagarVaga, marcarStatusDeVaga,
 * atualizarNotaDeVaga, criarLembreteNaAgenda (essa não escreve no banco do
 * Job Radar — só monta a proposta que a Agenda Pessoal recebe do
 * frontend) e lembrarPreferencia (não persiste nada aqui, quem guarda é o
 * frontend). Zero mudança de comportamento — métodos movidos verbatim.
 *
 * <p>{@code statusDe(Job)} é duplicado aqui em vez de compartilhado — a
 * mesma derivação de 5 linhas já existe em JobController e
 * JobStatusService.statusAtual(), com um comentário explícito lá dizendo
 * que não compensa extrair só por causa de mais um uso. Mantendo o
 * precedente já estabelecido no projeto.</p>
 */
@Service
@RequiredArgsConstructor
class JarvisWriteTools {

    private final JobRepository jobRepository;
    private final JobStatusService jobStatusService;
    private final SeniorityClassifier seniorityClassifier;

    private String statusDe(Job j) {
        if (j.isRejected()) return "RECUSADA";
        if (j.isInProgress()) return "ANDAMENTO";
        if (j.isApplied()) return "APLICADA";
        if (j.isInterested()) return "INTERESSADO";
        if (j.isSeen()) return "VISTA";
        return "NOVA";
    }

    Object executarCriarLembreteNaAgenda(Map<String, Object> args) {
        String titulo = args.get("titulo") instanceof String s && !s.isBlank() ? s : null;
        if (titulo == null) {
            return Map.of("erro", "Preciso de um título pro lembrete.");
        }
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String descricao = args.get("descricao") instanceof String s ? s : null;
        String dueAt = args.get("dueAt") instanceof String s && !s.isBlank() ? s : null;
        String prioridade = args.get("prioridade") instanceof String s && !s.isBlank() ? s.toUpperCase() : "NORMAL";

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("titulo", titulo);
        m.put("descricao", descricao);
        m.put("dueAt", dueAt);
        m.put("prioridade", prioridade);
        if (vagaId != null) {
            Optional<Job> jobOpt = jobRepository.findById(vagaId);
            if (jobOpt.isPresent()) {
                Job job = jobOpt.get();
                m.put("vagaId", vagaId);
                m.put("tituloVaga", job.getTitle());
                m.put("empresaVaga", job.getCompany());
                m.put("urlVaga", job.getUrl());
            }
        }
        return m;
    }

    Object executarFixarVaga(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        Boolean fixar = args.get("fixar") instanceof Boolean b ? b : null;
        if (vagaId == null || fixar == null) {
            return Map.of("erro", "Preciso do id da vaga e se é pra fixar (true) ou desafixar (false).");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        job.setFavorited(fixar);
        jobRepository.save(job);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        m.put("fixada", fixar);
        return m;
    }

    // Mesma lógica de POST /api/jobs/manual — cria vaga nova ou atualiza se
    // já existir uma com a mesma URL. Status padrão APLICADA (fluxo normal:
    // "achei essa vaga fora do Job Radar e já apliquei"), igual o botão
    // "➕ Adicionar vaga" do frontend.
    Object executarAdicionarVagaManual(Map<String, Object> args) {
        String titulo = args.get("titulo") instanceof String s && !s.isBlank() ? s : null;
        String empresa = args.get("empresa") instanceof String s && !s.isBlank() ? s : null;
        String url = args.get("url") instanceof String s && !s.isBlank() ? s : null;
        if (titulo == null || empresa == null || url == null) {
            return Map.of("erro", "Preciso de título, empresa e link da vaga.");
        }
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : "APLICADA";
        if (!JobStatusService.VALID_STATUSES.contains(status)) {
            return Map.of("erro", "Status inválido (" + JobStatusService.VALID_STATUSES + ").");
        }
        String salario = args.get("salario") instanceof String s ? s : null;
        String modalidade = args.get("modalidade") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        String estado = args.get("estado") instanceof String s ? s : null;

        Job job = jobRepository.findByUrl(url).orElseGet(Job::new);
        job.setTitle(titulo);
        job.setCompany(empresa);
        job.setUrl(url);
        job.setSource(job.getSource() == null ? "MANUAL" : job.getSource());
        job.setSeniority(seniorityClassifier.classify(titulo, null));
        job.setSalary(salario);
        job.setWorkplaceType(modalidade);
        job.setState(estado);
        job.setPostedAt(job.getPostedAt() != null ? job.getPostedAt() : LocalDateTime.now());
        job.setFetchedAt(LocalDateTime.now());
        jobStatusService.aplicarEsalvar(job, status);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", job.getId());
        m.put("titulo", job.getTitle());
        m.put("empresa", job.getCompany());
        m.put("status", status);
        return m;
    }

    // Não persiste nada aqui de verdade — quem guarda é o frontend
    // (localStorage, ver useHunterMemory), o backend só devolve o texto pro
    // JarvisPanel saber o que salvar. Ver comentário no parâmetro
    // memoryContext de conversar() sobre por que é assim.
    Object executarLembrarPreferencia(Map<String, Object> args) {
        String texto = args.get("texto") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (texto == null) {
            return Map.of("erro", "Preciso do texto da preferência a lembrar.");
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("texto", texto);
        return m;
    }

    // Irreversível — a SYSTEM_INSTRUCTION obriga o modelo a confirmar com
    // perguntarUsuario antes de chamar essa (não dá pra impor isso aqui no
    // backend sem estado de sessão entre chamadas, ver comentário no record
    // PendingQuestion sobre por que o histórico já basta pra tudo mais).
    Object executarApagarVaga(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        if (vagaId == null) {
            return Map.of("erro", "Preciso do id da vaga.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode já ter sido apagada.");
        }
        Job job = jobOpt.get();
        String titulo = job.getTitle();
        String empresa = job.getCompany();
        jobRepository.delete(job);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", titulo);
        m.put("empresa", empresa);
        return m;
    }

    // ÚNICA ferramenta de MUDANÇA DE STATUS — as outras que escrevem
    // (fixarVaga, atualizarNotaDeVaga, adicionarVagaManual) mexem em campos
    // diferentes. Ver guidance na SYSTEM_INSTRUCTION e na descrição da
    // ferramenta sobre só chamar com a vaga claramente identificada, nunca
    // "no escuro".
    Object executarMarcarStatus(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String status = args.get("status") instanceof String s && !s.isBlank() ? s.toUpperCase() : null;
        if (vagaId == null || status == null || !JobStatusService.VALID_STATUSES.contains(status)) {
            return Map.of("erro", "Preciso do id da vaga e um status válido (" + JobStatusService.VALID_STATUSES + ").");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String statusAntes = statusDe(job);
        String tituloAntes = job.getTitle();
        String empresaAntes = job.getCompany();
        jobStatusService.aplicarEsalvar(job, status);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", tituloAntes);
        m.put("empresa", empresaAntes);
        m.put("statusAntes", statusAntes);
        m.put("statusNovo", status);
        return m;
    }

    // Segunda ferramenta que escreve (ver marcarStatusDeVaga) — mesmo campo
    // que o botão "📝 Adicionar nota" de cada card mexe. SUBSTITUI o texto
    // inteiro (é só um campo de texto simples no banco, não uma lista de
    // itens) — a SYSTEM_INSTRUCTION já orienta o modelo a compor o texto
    // final antes de chamar quando o pedido for "adicionar" a uma nota
    // que já existe.
    Object executarAtualizarNota(Map<String, Object> args) {
        Long vagaId = args.get("vagaId") instanceof Number n ? n.longValue() : null;
        String nota = args.get("nota") instanceof String s ? s : null;
        if (vagaId == null || nota == null) {
            return Map.of("erro", "Preciso do id da vaga e o texto da nota.");
        }
        Optional<Job> jobOpt = jobRepository.findById(vagaId);
        if (jobOpt.isEmpty()) {
            return Map.of("erro", "Não achei a vaga de id " + vagaId + " — pode ter sido apagada.");
        }
        Job job = jobOpt.get();
        String notaAntes = job.getNotes();
        String tituloAntes = job.getTitle();
        String empresaAntes = job.getCompany();
        job.setNotes(nota.isBlank() ? null : nota);
        jobRepository.save(job);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("sucesso", true);
        m.put("vagaId", vagaId);
        m.put("titulo", tituloAntes);
        m.put("empresa", empresaAntes);
        m.put("notaAntes", notaAntes);
        m.put("notaNova", job.getNotes());
        return m;
    }
}

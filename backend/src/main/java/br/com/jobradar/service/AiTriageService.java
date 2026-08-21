package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fase 9.1 — pré-triagem assistida EM LOTE. A "Triagem rápida" (Fase 8.1,
 * ver TriageModal) já decide vaga a vaga, uma de cada vez — funciona, mas
 * com milhares de vagas NOVAS acumuladas o gargalo real é o VOLUME, não a
 * velocidade de decisão individual. Isso aqui pede pro Gemini uma opinião
 * pra um LOTE inteiro de vagas de uma vez só (1 chamada de IA cobre até
 * {@value #MAX_LOTE} vagas, não 1 chamada por vaga) — o usuário ainda decide
 * cada uma manualmente na Triagem rápida, só que agora com um sinal a mais
 * (veredito + motivo da IA) ao lado do pré-filtro heurístico que já existia.
 *
 * <p>Deliberadamente NÃO decide nada sozinho — sem isso o app entraria em
 * território de "IA recusa vaga por você sem perguntar", que nunca foi o
 * padrão adotado aqui (nem o ranking pessoal, nem o heurístico, decidem
 * status sozinhos — só sugerem/ordenam). Ver AiFeatureBudgetService.BATCH_TRIAGE.</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiTriageService {

    // Lote pequeno o bastante pra caber num prompt só e a resposta do Gemini
    // não estourar (1 JSON por vaga) nem virar lenta/instável; grande o
    // bastante pra valer a pena versus decidir vaga a vaga.
    public static final int MAX_LOTE = 20;

    private final GeminiService geminiService;
    private final AiFeatureBudgetService aiFeatureBudgetService;
    private final ObjectMapper mapper = new ObjectMapper();

    public enum Veredito { RECOMENDADA, TALVEZ, DESCARTAR }

    public record TriageVerdict(Long jobId, Veredito veredito, String motivo) {}

    public record TriageOutcome(List<TriageVerdict> veredictos, String errorMessage, boolean rateLimited) {
        public boolean ok() {
            return veredictos != null;
        }
    }

    public TriageOutcome triarLote(List<Job> jobs, String perfilCandidato) {
        if (perfilCandidato == null || perfilCandidato.isBlank()) {
            return new TriageOutcome(null,
                    "Salve seu perfil/currículo em ⚙️ Configurações primeiro, pra IA ter o que comparar.", false);
        }
        if (jobs.isEmpty()) {
            return new TriageOutcome(List.of(), null, false);
        }
        List<Job> lote = jobs.size() > MAX_LOTE ? jobs.subList(0, MAX_LOTE) : jobs;

        // Fase 9.8 — 1 unidade de orçamento por CHAMADA (não por vaga do
        // lote) — é isso que faz o "em lote" valer a pena versus gastar uma
        // unidade por vaga como as outras ferramentas (carta, match-score).
        if (!aiFeatureBudgetService.permitir(AiFeatureBudgetService.BATCH_TRIAGE)) {
            return new TriageOutcome(null, aiFeatureBudgetService.mensagemLimiteAtingido(AiFeatureBudgetService.BATCH_TRIAGE), false);
        }

        StringBuilder listaVagas = new StringBuilder();
        for (Job job : lote) {
            listaVagas.append("- id=").append(job.getId())
                    .append(" | ").append(job.getTitle())
                    .append(" @ ").append(job.getCompany());
            if (job.getSeniority() != null) listaVagas.append(" | nível: ").append(job.getSeniority());
            if (job.getWorkplaceType() != null) listaVagas.append(" | modalidade: ").append(job.getWorkplaceType());
            if (job.getSalary() != null && !job.getSalary().isBlank()) listaVagas.append(" | salário: ").append(job.getSalary());
            if (job.getTags() != null && !job.getTags().isBlank()) listaVagas.append(" | tags: ").append(job.getTags());
            listaVagas.append("\n");
        }

        String prompt = """
                Você vai fazer uma PRÉ-TRIAGEM em lote de vagas de emprego pro
                candidato abaixo. Pra CADA vaga da lista, dê um veredito rápido —
                não é uma análise profunda por vaga (isso já existe em outra
                ferramenta), é um filtro rápido pra economizar o tempo de revisão
                manual do candidato.

                Perfil do candidato:
                %s

                Vagas (uma por linha, "id=N" é o identificador que você deve usar
                na resposta):
                %s

                Pra cada vaga, decida entre RECOMENDADA (bate bem com o perfil),
                TALVEZ (compatibilidade incerta/parcial) ou DESCARTAR (claramente
                não bate — nível muito diferente, stack sem nenhuma sobreposição,
                etc). Seja honesto e conservador: na dúvida real, use TALVEZ, não
                RECOMENDADA nem DESCARTAR — o candidato decide de qualquer forma,
                o objetivo é economizar tempo dele, não decidir por ele.

                Devolva APENAS um JSON válido, sem markdown e sem texto fora do
                JSON, um array com um objeto por vaga, no formato exato:
                [{"id": 123, "veredito": "RECOMENDADA|TALVEZ|DESCARTAR", "motivo": "1 frase curta"}]
                """.formatted(perfilCandidato, listaVagas);

        GeminiService.GeminiResult resultado = geminiService.generate(prompt);
        if (!resultado.ok()) {
            return new TriageOutcome(null, resultado.errorMessage(), resultado.rateLimited());
        }

        try {
            JsonNode arr = mapper.readTree(GeminiService.stripMarkdownFences(resultado.text()));
            if (!arr.isArray()) throw new IllegalStateException("resposta não é um array JSON");

            Map<Long, TriageVerdict> porId = new HashMap<>();
            arr.forEach(node -> {
                long id = node.path("id").asLong(-1);
                if (id < 0) return;
                Veredito veredito = parseVeredito(node.path("veredito").asText(""));
                porId.put(id, new TriageVerdict(id, veredito, node.path("motivo").asText("")));
            });

            // Preserva a ORDEM do lote pedido (não a ordem que o Gemini devolveu
            // — modelos generativos não garantem preservar ordem de array) e
            // cobre o caso de o Gemini pular alguma vaga sem quebrar o resto.
            List<TriageVerdict> ordenado = lote.stream()
                    .map(job -> porId.getOrDefault(job.getId(),
                            new TriageVerdict(job.getId(), Veredito.TALVEZ, "IA não avaliou essa vaga (resposta incompleta)")))
                    .toList();
            return new TriageOutcome(ordenado, null, false);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini pra pré-triagem em lote: {}", e.getMessage());
            return new TriageOutcome(null, "A IA devolveu uma resposta inesperada. Tente de novo.", false);
        }
    }

    private Veredito parseVeredito(String raw) {
        try {
            return Veredito.valueOf(raw.trim().toUpperCase());
        } catch (Exception e) {
            return Veredito.TALVEZ; // resposta fora do enum esperado — trata como incerto, não quebra o lote inteiro
        }
    }
}

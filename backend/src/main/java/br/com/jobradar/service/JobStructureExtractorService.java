package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fase 9.4 + 9.6 — extrai estrutura da descrição crua da vaga (requisitos
 * obrigatórios vs desejáveis, anos de experiência mínimos, escolaridade,
 * benefícios — Fase 9.4) E sinais de alerta no texto (Fase 9.6) em vez de
 * deixar o usuário garimpar isso lendo um texto corrido às vezes com
 * centenas de linhas. As duas fases viram UMA chamada de Gemini só (mesma
 * descrição, mesmo texto de entrada — separar dobraria o custo de IA à toa
 * pra ler a mesma vaga duas vezes). Sob demanda (não roda pra todo o
 * catálogo no fetch) e CACHEADO PARA SEMPRE por vaga (ver
 * {@link Job#getEstruturaExtraidaEm()}), diferente de carta/match-score que
 * são recalculados a cada clique — a descrição de uma vaga não muda, então
 * só vale a pena extrair uma vez.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobStructureExtractorService {

    private final GeminiService geminiService;
    private final JobDescriptionService jobDescriptionService;
    private final AiFeatureBudgetService aiFeatureBudgetService;
    private final JobRepository jobRepository;
    private final ObjectMapper mapper = new ObjectMapper();

    public record EstruturaDescricao(
            List<String> requisitosObrigatorios,
            List<String> requisitosDesejaveis,
            Integer anosExperienciaMin,
            String escolaridadeRequerida,
            List<String> beneficios,
            List<String> sinaisAlerta
    ) {}

    public record ExtractOutcome(EstruturaDescricao estrutura, String errorMessage, boolean rateLimited, boolean cacheHit) {
        public boolean ok() {
            return estrutura != null;
        }
    }

    /**
     * Extrai (ou devolve do cache) a estrutura da descrição dessa vaga.
     * Persiste o resultado no próprio {@link Job} — chamador não precisa
     * salvar nada, esse método já faz o save.
     */
    public ExtractOutcome extrair(Job job) {
        if (job.getEstruturaExtraidaEm() != null) {
            return new ExtractOutcome(fromJob(job), null, false, true);
        }

        String descricao = jobDescriptionService.fetchDescription(job.getUrl());
        if (descricao == null || descricao.isBlank()) {
            return new ExtractOutcome(null, "Não consegui buscar a descrição completa dessa vaga (link indisponível ou formato não suportado).", false, false);
        }

        if (!aiFeatureBudgetService.permitir(AiFeatureBudgetService.STRUCTURE_EXTRACT)) {
            return new ExtractOutcome(null, aiFeatureBudgetService.mensagemLimiteAtingido(AiFeatureBudgetService.STRUCTURE_EXTRACT), false, false);
        }

        String prompt = """
                Analise a descrição de vaga abaixo em DUAS partes.

                PARTE 1 — ESTRUTURA: extraia requisitos obrigatórios/desejáveis,
                anos de experiência mínimos, escolaridade e benefícios. Não
                invente nada que não esteja escrito — se uma informação não
                aparece na descrição, devolva null (pro campo escalar) ou array
                vazio (pra lista).

                PARTE 2 — SINAIS DE ALERTA: aponte sinais CONCRETOS no TEXTO
                (não especulação sobre a empresa, só o que está escrito) que
                merecem atenção antes de aplicar — exemplos do tipo de sinal
                (não é lista fechada): salário omitido apesar do título sugerir
                senioridade/liderança, escopo de responsabilidades claramente
                maior que o nível pedido no título, linguagem tipo "hora extra
                é normal aqui"/"disponibilidade total"/"vestir a camisa"/
                "trabalhar sob pressão constante", exigência de requisitos
                desproporcionais pro nível anunciado (ex: "júnior" pedindo 8
                anos de experiência), contrato ambíguo ou só PJ sem explicar o
                motivo. Se a descrição não tiver nenhum sinal desses, devolva
                array vazio — não force um alerta que não existe.

                Vaga: %s @ %s

                Descrição completa:
                %s

                Devolva APENAS um JSON válido, sem markdown e sem texto fora do
                JSON, no formato exato:
                {
                  "requisitosObrigatorios": ["...", "..."],
                  "requisitosDesejaveis": ["...", "..."],
                  "anosExperienciaMin": 0,
                  "escolaridadeRequerida": "..." ou null,
                  "beneficios": ["...", "..."],
                  "sinaisAlerta": ["...", "..."]
                }
                """.formatted(job.getTitle(), job.getCompany(), descricao);

        GeminiService.GeminiResult resultado = geminiService.generate(prompt);
        if (!resultado.ok()) {
            return new ExtractOutcome(null, resultado.errorMessage(), resultado.rateLimited(), false);
        }

        try {
            JsonNode node = mapper.readTree(GeminiService.stripMarkdownFences(resultado.text()));
            List<String> obrigatorios = toList(node.path("requisitosObrigatorios"));
            List<String> desejaveis = toList(node.path("requisitosDesejaveis"));
            List<String> beneficios = toList(node.path("beneficios"));
            List<String> sinaisAlerta = toList(node.path("sinaisAlerta"));
            Integer anos = node.path("anosExperienciaMin").isNull() || !node.hasNonNull("anosExperienciaMin")
                    ? null : node.path("anosExperienciaMin").asInt();
            String escolaridade = node.path("escolaridadeRequerida").isNull() ? null : node.path("escolaridadeRequerida").asText(null);

            // Persiste no cache — TODA chamada (mesmo com listas vazias) marca
            // estruturaExtraidaEm, pra nunca reprocessar a mesma vaga de novo
            // só porque a descrição genuinamente não tinha requisito/alerta claro.
            job.setRequisitosObrigatorios(join(obrigatorios));
            job.setRequisitosDesejaveis(join(desejaveis));
            job.setAnosExperienciaMin(anos);
            job.setEscolaridadeRequerida(escolaridade);
            job.setBeneficios(join(beneficios));
            job.setSinaisAlerta(join(sinaisAlerta));
            job.setEstruturaExtraidaEm(LocalDateTime.now());
            jobRepository.save(job);

            return new ExtractOutcome(new EstruturaDescricao(obrigatorios, desejaveis, anos, escolaridade, beneficios, sinaisAlerta), null, false, false);
        } catch (Exception e) {
            log.warn("Resposta inesperada do Gemini pra extração de estrutura: {}", e.getMessage());
            return new ExtractOutcome(null, "A IA devolveu uma resposta inesperada. Tente de novo.", false, false);
        }
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr.isArray()) arr.forEach(n -> { if (!n.asText().isBlank()) out.add(n.asText()); });
        return out;
    }

    private EstruturaDescricao fromJob(Job job) {
        return new EstruturaDescricao(
                splitOrEmpty(job.getRequisitosObrigatorios()),
                splitOrEmpty(job.getRequisitosDesejaveis()),
                job.getAnosExperienciaMin(),
                job.getEscolaridadeRequerida(),
                splitOrEmpty(job.getBeneficios()),
                splitOrEmpty(job.getSinaisAlerta())
        );
    }

    // Separador " ||| " (não uma vírgula simples, ao contrário de `tags`) —
    // esses campos vêm de frases geradas pelo Gemini (ex: "Conhecimento em
    // SQL, PL/SQL" ou um sinal de alerta como frase inteira), que PODEM
    // conter vírgula de verdade. Vírgula como separador corromperia o
    // round-trip (split reconstruiria itens errados); " ||| " é improvável
    // de aparecer dentro de uma frase natural.
    private static final String SEPARADOR = " ||| ";

    private String join(List<String> items) {
        return String.join(SEPARADOR, items);
    }

    private List<String> splitOrEmpty(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return List.of(raw.split(java.util.regex.Pattern.quote(SEPARADOR)));
    }
}

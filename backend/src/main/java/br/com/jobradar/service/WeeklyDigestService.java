package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.model.WeeklyDigest;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.repository.WeeklyDigestRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Fase 3.5 — resumo semanal automático (o que entrou, o que está parado,
 * prazos fechando), UMA chamada de IA por semana em vez do usuário
 * precisar lembrar de perguntar isso pro Hunter toda vez. Roda sozinho
 * (ver {@link #gerarESalvarDigest()} com {@code @Scheduled}), mas também
 * pode ser disparado na mão (botão em Configurações, mesmo padrão do
 * backfill de embeddings) via {@link WeeklyDigestController}.
 *
 * <p>Reaproveita a MESMA definição de "parada" e "prazo próximo" que as
 * ferramentas do chat já usam (ver JarvisChatService.executarVagasParadas
 * / executarVagasComPrazoProximo) — reimplementada aqui de propósito em
 * vez de compartilhada: esses métodos são privados dentro de
 * JarvisChatService e não vale abrir a classe inteira só por causa de
 * duas queries (mesmo precedente já registrado pra statusDe(), ver
 * JarvisWriteTools e JobStatusService.statusAtual()).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeeklyDigestService {

    private final JobRepository jobRepository;
    private final WeeklyDigestRepository weeklyDigestRepository;
    private final GeminiService geminiService;
    private final AiFeatureBudgetService aiFeatureBudgetService;

    private static final Long DIGEST_ID = 1L;
    private static final int DIAS_PARADA = 10;
    private static final int DIAS_PRAZO = 7;

    /** Toda segunda-feira às 8h (horário de Brasília). */
    @Scheduled(cron = "0 0 8 * * MON", zone = "America/Sao_Paulo")
    public void gerarDigestSemanalAgendado() {
        log.info("=== Digest semanal: geração agendada iniciada ===");
        gerarESalvarDigest();
    }

    public Optional<WeeklyDigest> buscarUltimo() {
        return weeklyDigestRepository.findById(DIGEST_ID);
    }

    public WeeklyDigest gerarESalvarDigest() {
        LocalDateTime seteDiasAtras = LocalDateTime.now().minusDays(7);
        List<Job> todas = jobRepository.findAll();

        List<Job> novasEstaSemana = todas.stream()
                .filter(j -> j.getFetchedAt() != null && j.getFetchedAt().isAfter(seteDiasAtras))
                .toList();

        List<Job> paradas = todas.stream()
                .filter(j -> j.isApplied() && !j.isRejected())
                .filter(j -> {
                    LocalDateTime ultimaMudanca = j.isInProgress()
                            ? (j.getInProgressAt() != null ? j.getInProgressAt() : j.getAppliedAt())
                            : j.getAppliedAt();
                    return ultimaMudanca != null && ultimaMudanca.isBefore(LocalDateTime.now().minusDays(DIAS_PARADA));
                })
                .toList();

        LocalDate limitePrazo = LocalDate.now().plusDays(DIAS_PRAZO);
        List<Job> prazosProximos = todas.stream()
                .filter(j -> !j.isRejected())
                .filter(j -> j.getExpiresAt() != null
                        && !j.getExpiresAt().isAfter(limitePrazo)
                        && !j.getExpiresAt().isBefore(LocalDate.now()))
                .sorted(Comparator.comparing(Job::getExpiresAt))
                .toList();

        String prompt = montarPrompt(novasEstaSemana, paradas, prazosProximos);
        // Fase 9.8 — orçamento diário (folgado, 10/dia — isso aqui já é
        // 1x/semana automático + eventual clique manual, o teto é só rede
        // de segurança contra clique repetido acidental no botão).
        GeminiService.GeminiResult resultado = aiFeatureBudgetService.permitir(AiFeatureBudgetService.WEEKLY_DIGEST)
                ? geminiService.generate(prompt)
                : new GeminiService.GeminiResult(null, aiFeatureBudgetService.mensagemLimiteAtingido(AiFeatureBudgetService.WEEKLY_DIGEST), false);

        String conteudo = resultado.ok()
                ? resultado.text()
                // Falha honesta em vez de inventar um resumo — a IA pode estar
                // fora do ar (ver histórico de 403 do pool de keys) sem que
                // isso trave o resto do app. O frontend mostra esse erro como
                // está, sem fingir que é um digest de verdade.
                : "Não consegui gerar o resumo desta semana: " + resultado.errorMessage();

        WeeklyDigest digest = WeeklyDigest.builder()
                .id(DIGEST_ID)
                .conteudo(conteudo)
                .geradoEm(LocalDateTime.now())
                .vagasNovas(novasEstaSemana.size())
                .vagasParadas(paradas.size())
                .prazosProximos(prazosProximos.size())
                .build();
        weeklyDigestRepository.save(digest);
        log.info("=== Digest semanal salvo: {} novas, {} paradas, {} com prazo próximo (ia ok={}) ===",
                novasEstaSemana.size(), paradas.size(), prazosProximos.size(), resultado.ok());
        return digest;
    }

    private String montarPrompt(List<Job> novas, List<Job> paradas, List<Job> prazos) {
        StringBuilder sb = new StringBuilder();
        sb.append("Você é o Hunter, assistente pessoal de busca de emprego do usuário do Job Radar. ")
                .append("Escreva um resumo semanal curto (4-6 frases, tom direto e prático, sem enrolação, ")
                .append("em português do Brasil) cobrindo os pontos abaixo. Não invente números além dos ")
                .append("fornecidos, não repita a lista inteira (o app já mostra os dados crus em outro lugar) — ")
                .append("sintetize o que importa e, se fizer sentido, sugira UMA ação concreta pra essa semana.\n\n");

        sb.append("Vagas novas encontradas nos últimos 7 dias: ").append(novas.size()).append("\n");
        if (!novas.isEmpty()) {
            sb.append("Algumas delas: ");
            novas.stream().limit(8).forEach(j -> sb.append(j.getTitle()).append(" @ ").append(j.getCompany()).append("; "));
            sb.append("\n");
        }

        sb.append("\nCandidaturas paradas há mais de ").append(DIAS_PARADA).append(" dias sem mudança: ").append(paradas.size()).append("\n");
        if (!paradas.isEmpty()) {
            paradas.stream().limit(8).forEach(j -> sb.append("- ").append(j.getTitle()).append(" @ ").append(j.getCompany()).append("\n"));
        }

        sb.append("\nPrazos de candidatura fechando nos próximos ").append(DIAS_PRAZO).append(" dias: ").append(prazos.size()).append("\n");
        if (!prazos.isEmpty()) {
            prazos.stream().limit(8).forEach(j -> sb.append("- ").append(j.getTitle()).append(" @ ").append(j.getCompany())
                    .append(" (fecha ").append(j.getExpiresAt()).append(")\n"));
        }

        return sb.toString();
    }
}

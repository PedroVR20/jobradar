package br.com.jobradar.controller;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.FonteSaudeProjection;
import br.com.jobradar.repository.JobRepository;
import br.com.jobradar.service.GeminiService;
import br.com.jobradar.service.JobEmbeddingService;
import br.com.jobradar.service.SalaryEstimateService;
import br.com.jobradar.service.SalaryModelTrainerService;
import br.com.jobradar.service.SalaryPredictionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fase 5.1 — parte "admin/manutenção" que morava dentro de JobController
 * (1420 linhas antes do split): backfill de embedding, painel de saúde das
 * fontes, e o fluxo de retreino do modelo de salário protegido por código.
 * Nada aqui é chamado pelo fluxo normal do app — são botões de
 * Configurações ou scripts de manutenção.
 */
@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
@Slf4j
public class JobAdminController {

    private final JobRepository jobRepository;
    private final GeminiService geminiService;
    private final JobEmbeddingService jobEmbeddingService;
    private final SalaryEstimateService salaryEstimateService;
    private final SalaryPredictionService salaryPredictionService;
    private final SalaryModelTrainerService salaryModelTrainerService;

    // Gate simples (não é segurança de verdade — app pessoal local) pra não
    // ter um botão de "retreinar" clicável sem querer. Vazio == recurso
    // desativado (retorna 503 em vez de aceitar qualquer código).
    @Value("${retrain.secret-code:}")
    private String retrainSecretCode;
    private final AtomicBoolean retreinoEmAndamento = new AtomicBoolean(false);
    private final AtomicBoolean backfillEmbeddingsEmAndamento = new AtomicBoolean(false);

    // Só pro backfill rodar em background e devolver o POST na hora — mesmo
    // padrão do sseExecutor em AssistantController, mas dedicado (nenhuma
    // relação entre os dois usos).
    private final ExecutorService backfillExecutor = Executors.newCachedThreadPool();

    /**
     * Embedda (busca semântica, ver JobEmbeddingService) todas as vagas que
     * ainda não têm vetor salvo — vagas novas já são embeddadas sozinhas no
     * fetch periódico (ver JobAggregatorService); esse endpoint é só pro
     * catálogo que já existia ANTES dessa feature. Roda em background (o POST
     * devolve na hora), protegido de disparo duplo com a mesma flag simples
     * que /admin/retrain-salary-model já usa.
     * POST /api/jobs/admin/backfill-embeddings
     */
    @PostMapping("/admin/backfill-embeddings")
    public ResponseEntity<Map<String, Object>> backfillEmbeddings() {
        if (!geminiService.isEnabled()) {
            return ResponseEntity.status(503).body(Map.of("error", "Recurso de IA não configurado — defina GEMINI_API_KEY no .env"));
        }
        if (!backfillEmbeddingsEmAndamento.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("error", "Backfill de embeddings já em andamento."));
        }
        long faltam = jobEmbeddingService.contarSemEmbedding();
        backfillExecutor.execute(() -> {
            try {
                List<Job> pendentes = jobEmbeddingService.semEmbedding();
                int ok = 0;
                for (Job job : pendentes) {
                    // Fase 6.1 — embedESalvar já persiste em job_embeddings sozinho.
                    if (jobEmbeddingService.embedESalvar(job)) {
                        ok++;
                    }
                }
                log.info("=== Backfill de embeddings concluído: {} de {} vagas embeddadas ===", ok, pendentes.size());
            } finally {
                backfillEmbeddingsEmAndamento.set(false);
            }
        });
        return ResponseEntity.accepted().body(Map.of("iniciado", true, "vagasSemEmbedding", faltam));
    }

    /**
     * Exporta as vagas com salário parseável e limpo (mesma lógica do
     * salary-estimate) pra treinar um modelo real fora do backend — uso
     * interno/manutenção, não é chamado pelo frontend. Ver
     * scripts/train_salary_model.py e SalaryPredictionService.
     * GET /api/jobs/admin/salary-training-data
     */
    @GetMapping("/admin/salary-training-data")
    public List<SalaryEstimateService.TrainingRow> exportSalaryTrainingData() {
        return salaryEstimateService.exportTrainingData();
    }

    /**
     * Fase 2.7 — painel de saúde das fontes (Configurações). Um por fonte,
     * calculado dinamicamente via GROUP BY (ver
     * {@link JobRepository#saudeDasFontes()}) — fonte nova aparece sozinha,
     * sem precisar editar esse endpoint.
     *
     * <p>Não existe rastreio de "último fetch bem-sucedido" por fonte no
     * banco (isso exigiria uma tabela nova, fora do escopo desta fase) —
     * {@code diasSemVagaNova} é a métrica honesta disponível hoje: quantos
     * dias desde a vaga mais recente daquela fonte. Um número alto é um
     * SINAL de que a fonte pode ter parado de trazer vaga nova (scraping
     * quebrado, API fora do ar), não uma confirmação — pode ser só uma
     * fonte que realmente posta pouco. O frontend decide o que fazer com
     * esse sinal (ex: badge de alerta acima de N dias).</p>
     * GET /api/jobs/admin/fontes-saude
     */
    @GetMapping("/admin/fontes-saude")
    public List<Map<String, Object>> getSaudeDasFontes() {
        LocalDateTime agora = LocalDateTime.now();
        List<Map<String, Object>> resultado = new ArrayList<>();
        for (FonteSaudeProjection p : jobRepository.saudeDasFontes()) {
            Map<String, Object> item = new HashMap<>();
            item.put("fonte", p.getSource());
            long total = p.getTotal() != null ? p.getTotal() : 0;
            long comSalario = p.getComSalario() != null ? p.getComSalario() : 0;
            long comEstado = p.getComEstado() != null ? p.getComEstado() : 0;
            item.put("total", total);
            item.put("pctComSalario", total == 0 ? 0.0 : Math.round(comSalario * 1000.0 / total) / 10.0);
            item.put("pctComEstado", total == 0 ? 0.0 : Math.round(comEstado * 1000.0 / total) / 10.0);
            item.put("vagaMaisRecente", p.getVagaMaisRecente());
            item.put("ultimoFetch", p.getUltimoFetch());
            item.put("diasSemVagaNova", p.getVagaMaisRecente() == null
                    ? null
                    : Duration.between(p.getVagaMaisRecente(), agora).toDays());
            resultado.add(item);
        }
        return resultado;
    }

    public record RetrainCodeRequest(String code, Boolean force) {}

    private boolean codigoRetreinoBate(String code) {
        return retrainSecretCode != null && !retrainSecretCode.isBlank()
                && code != null && code.equals(retrainSecretCode);
    }

    /**
     * Passo 1 do fluxo de retreino do frontend: só confirma se o código
     * digitado bate, sem disparar o treino de verdade — o botão "Retreinar"
     * só aparece na tela depois de um {@code valid:true} aqui.
     * POST /api/jobs/admin/verify-retrain-code  Body: { "code": "..." }
     */
    @PostMapping("/admin/verify-retrain-code")
    public ResponseEntity<Map<String, Object>> verificarCodigoRetreino(@RequestBody(required = false) RetrainCodeRequest req) {
        return ResponseEntity.ok(Map.of("valid", codigoRetreinoBate(req != null ? req.code() : null)));
    }

    /**
     * Retreina o modelo de salário na hora (Ridge regression em Java puro,
     * ver SalaryModelTrainerService) e já troca o modelo em uso — sem
     * precisar rodar o script Python nem reconstruir o container. O código
     * é validado de novo aqui (não confia só na checagem do passo 1).
     * POST /api/jobs/admin/retrain-salary-model  Body: { "code": "..." }
     */
    @PostMapping("/admin/retrain-salary-model")
    public ResponseEntity<Map<String, Object>> retreinarModeloSalario(@RequestBody(required = false) RetrainCodeRequest req) {
        if (retrainSecretCode == null || retrainSecretCode.isBlank()) {
            return ResponseEntity.status(503).body(Map.of("error", "RETRAIN_SECRET_CODE não configurado no .env — recurso desativado."));
        }
        if (!codigoRetreinoBate(req != null ? req.code() : null)) {
            return ResponseEntity.status(403).body(Map.of("error", "Código incorreto."));
        }
        if (!retreinoEmAndamento.compareAndSet(false, true)) {
            return ResponseEntity.status(409).body(Map.of("error", "Já tem um retreino em andamento — espera terminar."));
        }
        try {
            SalaryPredictionService.ModelInfo anterior = salaryPredictionService.getModelInfo();
            SalaryModelTrainerService.TrainResult resultado = salaryModelTrainerService.treinar();

            // Erro % (maePercent) é o número que a UI destaca em todo lugar
            // como "margem de erro típica" — é o que o usuário realmente
            // olha pra saber se piorou ou melhorou. Um retreino que piora
            // esse número não substitui o modelo em produção sozinho: o
            // treino em si sempre roda (não tem como saber se piorou sem
            // treinar), mas só troca o modelo em uso se não piorou, ou se o
            // usuário mandou forçar mesmo assim depois de ver a comparação.
            boolean force = req != null && Boolean.TRUE.equals(req.force());
            boolean piorou = anterior != null && resultado.maePercent() > anterior.maePercent();
            boolean aplicado = force || !piorou;
            if (aplicado) {
                salaryPredictionService.reload(resultado.modelJson());
            }

            Map<String, Object> body = new HashMap<>();
            body.put("previous", anterior == null ? null : Map.of(
                    "nSamples", anterior.nSamples(), "r2", anterior.r2(),
                    "maeBrl", anterior.maeBrl(), "maePercent", anterior.maePercent()));
            body.put("updated", Map.of(
                    "nSamples", resultado.nSamples(), "r2", resultado.r2(),
                    "maeBrl", resultado.maeBrl(), "maePercent", resultado.maePercent()));
            body.put("applied", aplicado);
            return ResponseEntity.ok(body);
        } catch (IllegalStateException e) {
            return ResponseEntity.status(400).body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            log.error("Falha ao retreinar modelo de salário", e);
            return ResponseEntity.status(500).body(Map.of("error", "Erro interno ao retreinar: " + e.getMessage()));
        } finally {
            retreinoEmAndamento.set(false);
        }
    }
}

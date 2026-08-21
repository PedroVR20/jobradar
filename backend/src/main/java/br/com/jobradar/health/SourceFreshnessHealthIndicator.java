package br.com.jobradar.health;

import br.com.jobradar.repository.FonteSaudeProjection;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Fase 15.5 — alerta de degradação silenciosa. Uma fonte de vaga (Gupy,
 * Nerdin, Greenhouse...) que quebra (scraping mudou, API saiu do ar) não
 * lança exceção pro usuário perceber — {@link br.com.jobradar.service.JobSource}
 * NUNCA lança por contrato (ver seu Javadoc), só loga e devolve lista vazia.
 * Sem isso, uma fonte parada só seria descoberta se alguém abrisse
 * Configurações e reparasse na tabela "Saúde das fontes" (Fase 2.7) por
 * conta própria — nada avisava ativamente.
 *
 * <p>Reaproveita o MESMO dado que a Fase 2.7 já calcula (dias desde a vaga
 * mais recente por fonte, ver {@link JobRepository#saudeDasFontes()}) — não
 * duplica lógica nova, só pluga esse sinal existente no canal de
 * observabilidade que a Fase 15.4 acabou de abrir (/actuator/health), pra
 * ficar visível pra quem/o que monitora o backend de fora, não só pra quem
 * abrir Configurações manualmente.</p>
 *
 * <p>Limiar (10 dias) igual ao usado no badge visual do frontend
 * (DIAS_ALERTA_FONTE_PARADA em SettingsModal.tsx) — os dois lados
 * concordam hoje; se um mudar, o outro precisa acompanhar manualmente
 * (não há um endpoint compartilhado de config ainda).</p>
 *
 * <p>Usa o status customizado "DEGRADED", NÃO {@code Health.down()|Status.DOWN}
 * de propósito: DOWN mapeia pra HTTP 503 por padrão, o que faria o
 * healthcheck do Docker (wget --spider em /actuator/health, ver
 * docker-compose.yml) marcar o container inteiro como "unhealthy" — errado
 * aqui, porque uma fonte de vaga parada é um sinal de NEGÓCIO (scraping
 * quebrou, precisa de atenção humana), não uma falha de INFRAESTRUTURA
 * (o processo Java/a conexão com o banco continuam de pé). Reiniciar o
 * container não conserta um scraper quebrado. Status desconhecido pro
 * Spring mapeia pra HTTP 200 por padrão — o alerta fica visível no corpo
 * JSON pra quem monitora, sem derrubar o healthcheck.</p>
 */
@Component
@RequiredArgsConstructor
public class SourceFreshnessHealthIndicator implements HealthIndicator {

    private static final int DIAS_ALERTA_FONTE_PARADA = 10;
    private static final Status DEGRADED = new Status("DEGRADED");

    private final JobRepository jobRepository;

    @Override
    public Health health() {
        LocalDateTime agora = LocalDateTime.now();
        List<Map<String, Object>> degradadas = new ArrayList<>();
        int totalFontes = 0;

        for (FonteSaudeProjection p : jobRepository.saudeDasFontes()) {
            totalFontes++;
            if (p.getVagaMaisRecente() == null) continue; // fonte sem NENHUMA vaga ainda — não é "parou", é "nunca trouxe"
            long dias = Duration.between(p.getVagaMaisRecente(), agora).toDays();
            if (dias > DIAS_ALERTA_FONTE_PARADA) {
                degradadas.add(Map.of(
                        "fonte", p.getSource(),
                        "diasSemVagaNova", dias,
                        "ultimoFetch", p.getUltimoFetch() != null ? p.getUltimoFetch().toString() : "nunca"
                ));
            }
        }

        Health.Builder builder = degradadas.isEmpty() ? Health.up() : Health.status(DEGRADED);
        return builder
                .withDetail("totalFontes", totalFontes)
                .withDetail("fontesDegradadas", degradadas)
                .withDetail("limiarDias", DIAS_ALERTA_FONTE_PARADA)
                .build();
    }
}

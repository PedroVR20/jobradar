package br.com.jobradar.controller;

import br.com.jobradar.model.WeeklyDigest;
import br.com.jobradar.service.WeeklyDigestService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Fase 3.5 — resumo semanal automático. GET devolve o último gerado
 * (agendado toda segunda 8h, ver WeeklyDigestService); POST /gerar-agora
 * dispara na hora, mesmo padrão do botão de backfill de embeddings em
 * Configurações — útil pra não esperar a próxima segunda-feira só pra ver
 * a feature funcionando.
 */
@RestController
@RequestMapping("/api/jobs/admin/digest-semanal")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class WeeklyDigestController {

    private final WeeklyDigestService weeklyDigestService;

    @GetMapping
    public Map<String, Object> getUltimo() {
        return weeklyDigestService.buscarUltimo()
                .map(this::toDto)
                .orElseGet(() -> Map.of("existe", false));
    }

    @PostMapping("/gerar-agora")
    public Map<String, Object> gerarAgora() {
        return toDto(weeklyDigestService.gerarESalvarDigest());
    }

    private Map<String, Object> toDto(WeeklyDigest d) {
        return Map.of(
                "existe", true,
                "conteudo", d.getConteudo(),
                "geradoEm", d.getGeradoEm(),
                "vagasNovas", d.getVagasNovas(),
                "vagasParadas", d.getVagasParadas(),
                "prazosProximos", d.getPrazosProximos()
        );
    }
}

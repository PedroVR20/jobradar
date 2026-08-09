package br.com.jobradar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;

/**
 * Cotação EUR→BRL e USD→BRL, usada pra converter salários de fontes
 * internacionais (Arbeitnow, Remotive, WWR — todas em €/$) pra reais, tanto
 * na estimativa salarial ao vivo quanto no treino do modelo (mais dados
 * elegíveis = modelo melhor, ver scripts/README.md).
 *
 * <p>Fonte: <a href="https://frankfurter.dev">frankfurter.dev</a> — API
 * gratuita sem key, dados do Banco Central Europeu, atualizados dias úteis.
 * Busca uma vez por dia (cache em memória) e sempre tem um valor de fallback
 * pra nunca quebrar a estimativa de salário só porque a API de câmbio caiu.</p>
 */
@Service
@Slf4j
public class ExchangeRateService {

    private static final String URL = "https://api.frankfurter.dev/v1/latest?from=EUR&to=BRL,USD";

    // Fallback conservador (cotação aproximada de referência) — só usado se
    // a API estiver fora do ar E o app nunca tiver conseguido buscar antes.
    private volatile double eurToBrl = 6.0;
    private volatile double usdToBrl = 5.3;
    private volatile LocalDate ultimaAtualizacao = null;

    private final RestTemplate restTemplate = new RestTemplate();
    private final ObjectMapper mapper = new ObjectMapper();

    @PostConstruct
    void carregarNaInicializacao() {
        atualizarSeNecessario();
    }

    public double getEurToBrl() {
        atualizarSeNecessario();
        return eurToBrl;
    }

    public double getUsdToBrl() {
        atualizarSeNecessario();
        return usdToBrl;
    }

    private synchronized void atualizarSeNecessario() {
        LocalDate hoje = LocalDate.now();
        if (hoje.equals(ultimaAtualizacao)) return;
        try {
            String resposta = restTemplate.getForObject(URL, String.class);
            JsonNode root = mapper.readTree(resposta);
            double eurBrl = root.path("rates").path("BRL").asDouble(0);
            double eurUsd = root.path("rates").path("USD").asDouble(0);
            if (eurBrl > 0 && eurUsd > 0) {
                eurToBrl = eurBrl;
                usdToBrl = eurBrl / eurUsd;
                ultimaAtualizacao = hoje;
                log.info("=== Câmbio atualizado: 1 EUR = R$ {}, 1 USD = R$ {} ===",
                        String.format("%.2f", eurToBrl), String.format("%.2f", usdToBrl));
            }
        } catch (Exception e) {
            log.warn("Não foi possível atualizar câmbio (usando último valor conhecido: 1 EUR=R$ {}, 1 USD=R$ {}): {}",
                    eurToBrl, usdToBrl, e.getMessage());
        }
    }
}

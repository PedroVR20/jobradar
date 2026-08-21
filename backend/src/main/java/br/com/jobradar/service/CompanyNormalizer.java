package br.com.jobradar.service;

import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.Set;

/**
 * Fase 7.6 — nome de empresa "canônico" pra agrupar (dedup, histórico por
 * empresa, painel de fontes) sem depender de bater caractere por caractere.
 * "Start Recrutamento e Treinamento LTDA" e "Start Recrutamento" hoje
 * contam como empregadores diferentes em qualquer agregação por
 * {@code company} — essa normalização (minúsculo, sem acento, sem sufixo
 * societário) resolve o caso comum sem precisar de fuzzy matching de
 * verdade.
 *
 * <p>Extraído como componente próprio (em vez de ficar privado dentro de
 * {@code JobController.getDuplicates}, onde já existia uma versão) porque
 * agora o resultado é PERSISTIDO em {@code Job.companyNormalized}
 * (Fase 7.4/7.6) — as duas versões precisam ser sempre a mesma lógica, ou o
 * dedup em memória e o campo salvo divergem silenciosamente.</p>
 */
@Component
public class CompanyNormalizer {

    private static final Set<String> SUFIXOS_SOCIETARIOS = Set.of(
            "sa", "s a", "ltda", "me", "eireli", "inc", "llc", "corp", "corporation", "co"
    );

    public String normalizar(String company) {
        if (company == null) return "";
        String semAcento = removerAcentos(company).replaceAll("[^a-z0-9 ]", " ").trim();
        StringBuilder sb = new StringBuilder();
        for (String w : semAcento.split("\\s+")) {
            if (SUFIXOS_SOCIETARIOS.contains(w)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(w);
        }
        return sb.toString().trim();
    }

    private String removerAcentos(String text) {
        String decomposto = Normalizer.normalize(text.toLowerCase(), Normalizer.Form.NFD);
        return decomposto.replaceAll("\\p{M}", "");
    }
}

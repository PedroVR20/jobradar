package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Fase 7.5 — nenhuma das fontes avisa quando uma vaga sai do ar (a Gupy tem
 * {@code expiresAt}, mas é o prazo ANUNCIADO, não uma confirmação de que a
 * vaga saiu — muita empresa fecha antes ou deixa aberto depois). O único
 * sinal disponível é bater na URL de verdade e ver o que volta.
 *
 * <p>Amostrado e limitado por ciclo ({@link #MAX_CHECADAS_POR_CICLO}) de
 * propósito — checar as ~6000 vagas do catálogo inteiro a cada ciclo seria
 * ~6000 requisições HTTP de saída por execução, batendo em dezenas de
 * domínios diferentes; mesmo padrão de "avança aos poucos, prioriza nunca
 * checada" já usado no backfill de salário da Gupy
 * ({@link JobAggregatorService#enriquecerSalariosGupyAntigas()}).</p>
 *
 * <p>Timeout curto e HEAD em vez de GET — só interessa o status HTTP, não o
 * corpo da página. Qualquer coisa que não seja um 404/410 claro é tratada
 * como "ok" (inclusive erro de rede/timeout): impossível diferenciar com
 * confiança "vaga saiu do ar" de "site lento agora" ou "bloqueou HEAD",
 * então o critério fica deliberadamente conservador — falso negativo (vaga
 * morta não detectada) é bem menos custoso que falso positivo (vaga viva
 * escondida da lista por engano).</p>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobLinkCheckerService {

    private final JobRepository jobRepository;

    private static final int MAX_CHECADAS_POR_CICLO = 40;
    private static final int CONNECT_TIMEOUT_MS = 5_000;
    private static final int READ_TIMEOUT_MS = 5_000;

    private final RestTemplate restTemplate = buildRestTemplate();

    private static RestTemplate buildRestTemplate() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT_MS);
        factory.setReadTimeout(READ_TIMEOUT_MS);
        return new RestTemplate(factory);
    }

    @Transactional
    public void checarLinksAntigos() {
        List<Job> candidatas = new ArrayList<>(jobRepository.findByLinkCheckedAtIsNullAndRejectedFalseAndArchivedFalse(
                PageRequest.of(0, MAX_CHECADAS_POR_CICLO)));
        int nuncaChecadas = candidatas.size();

        int faltam = MAX_CHECADAS_POR_CICLO - candidatas.size();
        if (faltam > 0) {
            candidatas.addAll(jobRepository.findByLinkCheckedAtIsNotNullAndRejectedFalseAndArchivedFalseOrderByLinkCheckedAtAsc(
                    PageRequest.of(0, faltam)));
        }
        if (candidatas.isEmpty()) return;

        int mortas = 0;
        for (Job job : candidatas) {
            Boolean morta = checarUmLink(job.getUrl());
            job.setLinkCheckedAt(LocalDateTime.now());
            // inconclusivo (null) preserva o veredito anterior — só
            // sobrescreve quando a checagem desta vez teve resposta clara.
            if (morta != null) job.setLinkMorto(morta);
            if (Boolean.TRUE.equals(job.getLinkMorto())) mortas++;
        }
        jobRepository.saveAll(candidatas);
        log.info("=== Checagem de links: {} vagas checadas ({} nunca checadas antes), {} com link morto detectado ===",
                candidatas.size(), nuncaChecadas, mortas);
    }

    // null = inconclusivo (mantém o veredito anterior — se nunca checou,
    // fica null; se já tinha veredito e agora deu erro de rede, também não
    // sobrescreve pra "ok" só porque a rede falhou dessa vez).
    private Boolean checarUmLink(String url) {
        if (url == null || url.isBlank()) return null;
        try {
            restTemplate.execute(url, HttpMethod.HEAD, null, resp -> null);
            return false;
        } catch (HttpClientErrorException e) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            if (status == HttpStatus.NOT_FOUND || status == HttpStatus.GONE) return true;
            return false; // outro 4xx (403 bloqueando bot, por exemplo) não é sinal confiável de vaga morta
        } catch (RestClientException e) {
            return null; // timeout, DNS, conexão recusada — inconclusivo, não sobrescreve
        }
    }
}

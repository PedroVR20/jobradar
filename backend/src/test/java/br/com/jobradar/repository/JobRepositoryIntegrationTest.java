package br.com.jobradar.repository;

import br.com.jobradar.model.Job;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 15.1 — os 54 testes que já existiam usam repositório mockado
 * (Mockito): nenhum roda uma query de verdade, nenhum exercita um índice,
 * nenhum aplica uma migração Flyway de verdade. Este é o primeiro teste do
 * projeto contra um Postgres real (via Testcontainers), o mesmo tipo de
 * verificação que até agora só acontecia manualmente — backup real, container
 * throwaway, boot da imagem nova — antes de cada fase que mexia em schema
 * (ver protocolo usado nas Fases 5.3, 6.5, 12 e 7+8).
 *
 * <p>{@code @DataJpaTest} sobe só a fatia JPA do contexto (repositórios +
 * EntityManager), não a aplicação inteira — evita disparar
 * {@code JobAggregatorService.fetchNaInicializacao()} (que faria chamadas de
 * rede reais pras fontes de vaga a cada execução de teste).
 * {@code @AutoConfigureTestDatabase(replace = NONE)} impede o Spring Boot de
 * trocar o datasource por um banco embutido (H2) — sem isso o
 * {@code @ServiceConnection} do container seria ignorado silenciosamente.
 * Com Flyway habilitado (herda de application.yml) e o container postgres:16
 * (mesma versão de produção), as migrações V1-V3 rodam de verdade aqui,
 * incluindo a extensão {@code unaccent} e a função
 * {@code immutable_unaccent} que só existem em SQL real — nenhum mock
 * pegaria a query de {@link JobSpecifications#byState} quebrada.</p>
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class JobRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JobRepository jobRepository;

    private Job.JobBuilder novaJob(String url, String title) {
        return Job.builder()
                .title(title)
                .company("Empresa Teste")
                .url(url)
                .source("GUPY");
    }

    @Test
    void contextoSobeEMigracoesFlywayAplicamLimpo() {
        // Se o contexto subiu até aqui, Flyway já rodou V1-V3 contra o
        // Postgres real e o Hibernate (ddl-auto: validate) já conferiu que
        // as entidades batem com o schema criado pelas migrações — é a
        // própria inicialização do teste que é a verificação.
        assertThat(jobRepository.count()).isZero();
    }

    @Test
    void salvaEBuscaVagaPeloRepositorio() {
        Job salva = jobRepository.save(novaJob("https://exemplo.com/vaga-1", "Desenvolvedor Backend").build());

        Job encontrada = jobRepository.findById(salva.getId()).orElseThrow();
        assertThat(encontrada.getTitle()).isEqualTo("Desenvolvedor Backend");
        assertThat(encontrada.getCompany()).isEqualTo("Empresa Teste");
        assertThat(encontrada.isSeen()).isFalse(); // valor default do @Builder.Default
    }

    // Fase 12.1 — byState() usa immutable_unaccent (função SQL criada em
    // V2__indices_fase_12.sql, que por sua vez depende da extensão unaccent
    // do Postgres) pra achar "São Paulo" buscando "sao paulo" sem acento.
    // Esse é exatamente o tipo de bug (função/extensão ausente, nome errado)
    // que um repositório mockado nunca detectaria — só um Postgres de
    // verdade tem unaccent instalável.
    @Test
    void byStateIgnoraAcentuacao() {
        jobRepository.save(novaJob("https://exemplo.com/vaga-sp", "Vaga em SP").state("São Paulo").build());
        jobRepository.save(novaJob("https://exemplo.com/vaga-rj", "Vaga no RJ").state("Rio de Janeiro").build());

        List<Job> resultado = jobRepository.findAll(JobSpecifications.byState("sao paulo"));

        assertThat(resultado).hasSize(1);
        assertThat(resultado.get(0).getState()).isEqualTo("São Paulo");
    }

    // Fase 7.2 — vaga vencida (expiresAt no passado) só deve aparecer em
    // onlyExpired() se ainda não foi aplicada nem recusada; notExpired()
    // some com ela das abas de decisão.
    @Test
    void filtroDeVagaVencidaRespeitaAplicadaERecusada() {
        LocalDate ontem = LocalDate.now().minusDays(1);
        LocalDate semanaQueVem = LocalDate.now().plusDays(7);

        Job vencidaAtiva = novaJob("https://exemplo.com/vencida-ativa", "Vencida ativa")
                .expiresAt(ontem).build();
        Job vencidaMasAplicada = novaJob("https://exemplo.com/vencida-aplicada", "Vencida mas aplicada")
                .expiresAt(ontem).applied(true).build();
        Job aindaValida = novaJob("https://exemplo.com/valida", "Ainda válida")
                .expiresAt(semanaQueVem).build();
        Job semPrazo = novaJob("https://exemplo.com/sem-prazo", "Sem prazo informado").build();

        jobRepository.saveAll(List.of(vencidaAtiva, vencidaMasAplicada, aindaValida, semPrazo));

        List<Job> vencidasAtivas = jobRepository.findAll(JobSpecifications.onlyExpired());
        assertThat(vencidasAtivas).extracting(Job::getTitle).containsExactly("Vencida ativa");

        List<Job> naoVencidas = jobRepository.findAll(JobSpecifications.notExpired());
        assertThat(naoVencidas).extracting(Job::getTitle)
                .containsExactlyInAnyOrder("Vencida mas aplicada", "Ainda válida", "Sem prazo informado");
    }

    // Fase 7.3+8.4 — arquivada some de toda aba por padrão; só aparece com
    // onlyArchived() explícito.
    @Test
    void filtroDeArquivamentoSeparaPorFlagArchived() {
        Job ativa = novaJob("https://exemplo.com/ativa", "Vaga ativa").build();
        Job arquivada = novaJob("https://exemplo.com/arquivada", "Vaga arquivada")
                .archived(true).archivedAt(LocalDateTime.now()).archivedReason("Sem interação há mais de 30 dias")
                .build();
        jobRepository.saveAll(List.of(ativa, arquivada));

        assertThat(jobRepository.findAll(JobSpecifications.notArchived()))
                .extracting(Job::getTitle).containsExactly("Vaga ativa");
        assertThat(jobRepository.findAll(JobSpecifications.onlyArchived()))
                .extracting(Job::getTitle).containsExactly("Vaga arquivada");
        assertThat(jobRepository.countByArchivedTrue()).isEqualTo(1);
    }
}

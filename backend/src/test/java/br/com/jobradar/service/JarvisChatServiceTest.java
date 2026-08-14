package br.com.jobradar.service;

import br.com.jobradar.model.Job;
import br.com.jobradar.repository.JobEmbeddingRepository;
import br.com.jobradar.repository.JobEventRepository;
import br.com.jobradar.repository.JobRepository;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Fase 14.3 — statusBate/contemBusca/executarVagasParadas migraram pra
 * JarvisReadToolsTest junto com a implementação (ver JarvisReadTools). Este
 * arquivo cobre só o que continua em JarvisChatService de verdade: o
 * fast-path sem IA e o DISPATCH (executarFerramenta chegando no handler
 * certo — leitura via readTools, escrita via writeTools).
 */
class JarvisChatServiceTest {

    private final JobRepository jobRepository = mock(JobRepository.class);
    private final JobEventRepository jobEventRepository = mock(JobEventRepository.class);
    // Fase 6.1 — apagarVaga limpa job_embeddings também; mock simples, só
    // precisa não estourar NPE se algum teste futuro exercitar esse caminho.
    private final JobEmbeddingRepository jobEmbeddingRepository = mock(JobEmbeddingRepository.class);
    // Nenhum teste aqui toca nos outros colaboradores de leitura
    // (compatibilidade/salário/carta/embedding/email) — null é seguro.
    private final JarvisReadTools readTools =
            new JarvisReadTools(jobRepository, null, null, null, null, null, null);
    // JarvisWriteTools precisa de instâncias reais de JobStatusService e
    // SeniorityClassifier (não null) pros testes de dispatch de
    // marcarStatusDeVaga/adicionarVagaManual (Fase 3.7) — os dois chamam
    // esses serviços de verdade. jobEventRepository é mock simples (só
    // grava timeline, não precisa de comportamento real pro teste).
    private final JarvisWriteTools writeTools = new JarvisWriteTools(
            jobRepository, new JobStatusService(jobRepository, jobEventRepository), new SeniorityClassifier(), jobEmbeddingRepository);
    private final JarvisChatService service = new JarvisChatService(null, readTools, writeTools);

    private Job job(String status) {
        Job j = Job.builder().title("Dev Java").company("Acme").url("https://x/" + status).source("MANUAL").build();
        switch (status) {
            case "NOVA" -> { }
            case "VISTA" -> j.setSeen(true);
            case "INTERESSADO" -> { j.setSeen(true); j.setInterested(true); }
            case "APLICADA" -> { j.setSeen(true); j.setApplied(true); }
            case "ANDAMENTO" -> { j.setSeen(true); j.setApplied(true); j.setInProgress(true); }
            case "RECUSADA" -> j.setRejected(true);
            default -> throw new IllegalArgumentException(status);
        }
        return j;
    }

    @SuppressWarnings("unchecked")
    @Test
    void executarFerramenta_dispatchaApagarVagaEDevolveErroSemQuebrar() {
        when(jobRepository.findById(999L)).thenReturn(java.util.Optional.empty());

        var chamada = new GeminiService.FunctionCallRequest("apagarVaga", Map.of("vagaId", 999), null);
        Object resultado = service.executarFerramenta(chamada, null, null);

        assertThat(resultado).isInstanceOf(Map.class);
        Map<String, Object> mapa = (Map<String, Object>) resultado;
        assertThat(mapa).containsKey("erro");
    }

    @Test
    void tentarFastPath_respondeResumoDoFunilSemChamarGemini() {
        when(jobRepository.findAll()).thenReturn(List.of(job("NOVA"), job("APLICADA"), job("RECUSADA")));

        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("Quantas vagas eu tenho?", toolName -> { });

        assertThat(resultado).isNotNull();
        assertThat(resultado.ok()).isTrue();
        assertThat(resultado.reply()).contains("3 vagas no total");
        assertThat(resultado.toolResults()).hasSize(1);
        assertThat(resultado.toolResults().get(0).tool()).isEqualTo("resumoFunil");
    }

    @Test
    void tentarFastPath_naoDisparaEmPerguntaComContextoExtra() {
        // "quantas vagas eu tenho no Itaú" não é a mesma pergunta que
        // "quantas vagas eu tenho" — precisa cair no LLM normal, não no
        // fast-path (que só bate em correspondência EXATA da frase inteira).
        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("quantas vagas eu tenho no Itaú", toolName -> { });

        assertThat(resultado).isNull();
    }

    @Test
    void tentarFastPath_ignoraAcentuacaoEPontuacao() {
        when(jobRepository.findAll()).thenReturn(List.of(job("NOVA")));

        JarvisChatService.ChatOutcome resultado = service.tentarFastPath("Resumo do meu funil!", toolName -> { });

        assertThat(resultado).isNotNull();
        assertThat(resultado.reply()).contains("1 vagas no total");
    }

    // ===================== Fase 3.7 — roteamento do dispatcher =====================
    // Cobre o mesmo tipo de bug real já visto nessa área nesta sessão: a
    // ferramenta ERRADA sendo chamada pro nome certo (ex: um case do switch
    // apontando pro método de outra ferramenta por engano). Cada teste aqui
    // não valida a lógica interna da ferramenta (isso já é coberto nos
    // testes específicos de cada uma, onde existem) — só confirma que
    // executarFerramenta(nome, ...) chega no HANDLER certo, verificando uma
    // "impressão digital" do resultado (uma chave/formato que só aquele
    // método devolve).

    @SuppressWarnings("unchecked")
    private Map<String, Object> dispatch(String nome, Map<String, Object> args) {
        var chamada = new GeminiService.FunctionCallRequest(nome, args, null);
        Object resultado = service.executarFerramenta(chamada, null, null);
        assertThat(resultado).as("resultado de '%s' deveria ser um Map", nome).isInstanceOf(Map.class);
        return (Map<String, Object>) resultado;
    }

    @Test
    void dispatch_listarVagas_devolveListaDeVagas() {
        // Fase 14.1 — status/dias viram WHERE de SQL (ver specForStatus).
        when(jobRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(job("NOVA")));

        Map<String, Object> resultado = dispatch("listarVagas", Map.of());

        assertThat(resultado).containsKey("vagas");
        assertThat((List<?>) resultado.get("vagas")).hasSize(1);
        assertThat(resultado).doesNotContainKey("fontes"); // essa chave é de desempenhoPorFonte
    }

    @Test
    void dispatch_resumoFunil_naoDesempenhoPorFonte() {
        when(jobRepository.findAll()).thenReturn(List.of(job("NOVA"), job("APLICADA")));

        Map<String, Object> resultado = dispatch("resumoFunil", Map.of());

        assertThat(resultado).containsKeys("novas", "aplicadas", "totalHistoricoAplicadas");
        assertThat(resultado).doesNotContainKey("fontes"); // essa chave é de desempenhoPorFonte
    }

    @Test
    void dispatch_metricasDeDesempenho_naoResumoFunil() {
        when(jobRepository.findByAppliedTrue()).thenReturn(List.of());

        Map<String, Object> resultado = dispatch("metricasDeDesempenho", Map.of());

        assertThat(resultado).containsKey("taxaRespostaPercent");
        assertThat(resultado).doesNotContainKey("novas"); // essa chave é de resumoFunil
    }

    @Test
    void dispatch_vagasComPrazoProximo_naoVagasParadas() {
        // Fase 14.1 — não recusada + prazo no intervalo viram WHERE de SQL.
        when(jobRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of());

        Map<String, Object> resultado = dispatch("vagasComPrazoProximo", Map.of());

        assertThat(resultado).containsKey("diasMaximo"); // só vagasComPrazoProximo tem esse parâmetro
    }

    @Test
    void dispatch_detectarDuplicatas_devolveGrupos() {
        when(jobRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class))).thenReturn(List.of());

        Map<String, Object> resultado = dispatch("detectarDuplicatas", Map.of());

        assertThat(resultado).containsKey("grupos");
    }

    @Test
    void dispatch_desempenhoPorFonte_naoResumoFunil() {
        // Fase 14.1 — era findAll() + agrupamento em Java, agora é um
        // GROUP BY (ver JobRepository.desempenhoPorFonte) — a projeção é
        // uma interface, mockável do mesmo jeito que o repositório.
        br.com.jobradar.repository.FonteDesempenhoProjection projecao =
                mock(br.com.jobradar.repository.FonteDesempenhoProjection.class);
        when(projecao.getFonte()).thenReturn("GUPY");
        when(projecao.getTotal()).thenReturn(1L);
        when(projecao.getAplicadas()).thenReturn(1L);
        when(projecao.getEmAndamento()).thenReturn(0L);
        when(jobRepository.desempenhoPorFonte()).thenReturn(List.of(projecao));

        Map<String, Object> resultado = dispatch("desempenhoPorFonte", Map.of());

        assertThat(resultado).containsKey("fontes");
        assertThat(resultado).doesNotContainKey("novas");
    }

    @Test
    void dispatch_historicoDaEmpresa_filtraPeloNomeDaEmpresaCorreto() {
        Job acme = job("NOVA"); // company = "Acme" no helper job()
        // Fase 14.1 — LIKE por empresa vira WHERE de SQL.
        when(jobRepository.findAll(any(org.springframework.data.jpa.domain.Specification.class)))
                .thenReturn(List.of(acme));

        Map<String, Object> resultado = dispatch("historicoDaEmpresa", Map.of("empresa", "acme"));

        assertThat(resultado.get("totalEncontradas")).isEqualTo(1);
    }

    @Test
    void dispatch_vagasParecidas_semVagaIdDevolveErroClaro() {
        Map<String, Object> resultado = dispatch("vagasParecidas", Map.of());

        assertThat(resultado).containsKey("erro");
        assertThat((String) resultado.get("erro")).contains("id da vaga");
    }

    @Test
    void dispatch_fixarVaga_naoMarcarStatus() {
        Job j = job("NOVA");
        j.setId(1L);
        when(jobRepository.findById(1L)).thenReturn(java.util.Optional.of(j));

        Map<String, Object> resultado = dispatch("fixarVaga", Map.of("vagaId", 1, "fixar", true));

        assertThat(resultado).containsEntry("fixada", true);
        assertThat(resultado).doesNotContainKey("statusNovo"); // essa chave é de marcarStatusDeVaga
        assertThat(j.getFavorited()).isTrue();
    }

    @Test
    void dispatch_marcarStatusDeVaga_naoAtualizarNota() {
        Job j = job("NOVA");
        j.setId(2L);
        when(jobRepository.findById(2L)).thenReturn(java.util.Optional.of(j));

        Map<String, Object> resultado = dispatch("marcarStatusDeVaga", Map.of("vagaId", 2, "status", "APLICADA"));

        assertThat(resultado).containsEntry("statusNovo", "APLICADA");
        assertThat(resultado).doesNotContainKey("notaNova"); // essa chave é de atualizarNotaDeVaga
    }

    @Test
    void dispatch_atualizarNotaDeVaga_naoMarcarStatus() {
        Job j = job("NOVA");
        j.setId(3L);
        when(jobRepository.findById(3L)).thenReturn(java.util.Optional.of(j));

        Map<String, Object> resultado = dispatch("atualizarNotaDeVaga", Map.of("vagaId", 3, "nota", "ligar amanhã"));

        assertThat(resultado).containsEntry("notaNova", "ligar amanhã");
        assertThat(resultado).doesNotContainKey("statusNovo");
    }

    @Test
    void dispatch_adicionarVagaManual_naoFixarVaga() {
        when(jobRepository.findByUrl("https://exemplo.com/vaga")).thenReturn(java.util.Optional.empty());

        Map<String, Object> resultado = dispatch("adicionarVagaManual", Map.of(
                "titulo", "Dev Backend", "empresa", "Nova Empresa", "url", "https://exemplo.com/vaga"));

        assertThat(resultado).containsEntry("titulo", "Dev Backend");
        assertThat(resultado).doesNotContainKey("fixada");
    }

    @Test
    void dispatch_lembrarPreferencia_devolveTextoRecebido() {
        Map<String, Object> resultado = dispatch("lembrarPreferencia", Map.of("texto", "só vaga remota"));

        assertThat(resultado).containsEntry("texto", "só vaga remota");
    }

    @Test
    void dispatch_criarLembreteNaAgenda_naoEscreveNoJobRepository() {
        Map<String, Object> resultado = dispatch("criarLembreteNaAgenda", Map.of("titulo", "Follow-up"));

        assertThat(resultado).containsEntry("titulo", "Follow-up");
        org.mockito.Mockito.verifyNoInteractions(jobRepository); // essa ferramenta não deve tocar no banco
    }
}

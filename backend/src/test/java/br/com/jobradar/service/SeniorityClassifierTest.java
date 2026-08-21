package br.com.jobradar.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 1.5 — zero cobertura antes deste arquivo, apesar de alimentar o
 * campo Job.seniority usado como FEATURE no modelo de estimativa salarial
 * (SalaryModelTrainerService) e em praticamente todo filtro do app. Cobre
 * a ordem de prioridade documentada na classe (ESTAGIO → SENIOR → JUNIOR →
 * PLENO) e os casos ambíguos que a motivaram.
 */
class SeniorityClassifierTest {

    private final SeniorityClassifier classifier = new SeniorityClassifier();

    @Test
    void classify_estagio_portugues() {
        assertThat(classifier.classify("Estágio em Desenvolvimento", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
        assertThat(classifier.classify("Estagiário de TI", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
        assertThat(classifier.classify("Programa de Trainee 2026", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
    }

    @Test
    void classify_estagio_ingles_alemao() {
        assertThat(classifier.classify("Software Engineering Intern", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
        assertThat(classifier.classify("Working Student - Backend", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
        assertThat(classifier.classify("Praktikant Softwareentwicklung", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
    }

    @Test
    void classify_senior() {
        assertThat(classifier.classify("Desenvolvedor Sênior Java", null)).isEqualTo(SeniorityClassifier.SENIOR);
        assertThat(classifier.classify("Senior Backend Engineer", null)).isEqualTo(SeniorityClassifier.SENIOR);
        assertThat(classifier.classify("Dev Sr.", null)).isEqualTo(SeniorityClassifier.SENIOR);
        assertThat(classifier.classify("Staff Software Engineer", null)).isEqualTo(SeniorityClassifier.SENIOR);
        assertThat(classifier.classify("Head of Engineering", null)).isEqualTo(SeniorityClassifier.SENIOR);
    }

    @Test
    void classify_seniorAssociate_naoCaiEmJunior() {
        // Motivo documentado na classe: SENIOR é checado ANTES de JUNIOR
        // justamente pra esse caso — "associate" sozinho bateria em JUNIOR,
        // mas "Senior Associate" tem que ganhar de SENIOR.
        assertThat(classifier.classify("Senior Associate - Product", null)).isEqualTo(SeniorityClassifier.SENIOR);
    }

    @Test
    void classify_junior() {
        assertThat(classifier.classify("Desenvolvedor Júnior", null)).isEqualTo(SeniorityClassifier.JUNIOR);
        assertThat(classifier.classify("Junior Frontend Developer", null)).isEqualTo(SeniorityClassifier.JUNIOR);
        assertThat(classifier.classify("Dev Jr", null)).isEqualTo(SeniorityClassifier.JUNIOR);
        assertThat(classifier.classify("Entry-level Software Engineer", null)).isEqualTo(SeniorityClassifier.JUNIOR);
        assertThat(classifier.classify("Associate Software Engineer", null)).isEqualTo(SeniorityClassifier.JUNIOR);
    }

    @Test
    void classify_pleno() {
        assertThat(classifier.classify("Desenvolvedor Pleno", null)).isEqualTo(SeniorityClassifier.PLENO);
        assertThat(classifier.classify("Mid-level Backend Developer", null)).isEqualTo(SeniorityClassifier.PLENO);
        assertThat(classifier.classify("Medior Software Engineer", null)).isEqualTo(SeniorityClassifier.PLENO);
    }

    @Test
    void classify_semTermoReconhecido_devolveNaoInformado() {
        assertThat(classifier.classify("Desenvolvedor Full Stack", null)).isEqualTo(SeniorityClassifier.NAO_INFORMADO);
        assertThat(classifier.classify("Analista de Sistemas", null)).isEqualTo(SeniorityClassifier.NAO_INFORMADO);
    }

    @Test
    void classify_usaTagsQuandoTituloNaoAjuda() {
        // Título sozinho não decide, mas a tag "senior" resolve.
        assertThat(classifier.classify("Desenvolvedor Full Stack", "java,senior,remoto")).isEqualTo(SeniorityClassifier.SENIOR);
    }

    @Test
    void classify_tituloNulo_naoQuebra() {
        assertThat(classifier.classify(null, "junior,java")).isEqualTo(SeniorityClassifier.JUNIOR);
        assertThat(classifier.classify(null, null)).isEqualTo(SeniorityClassifier.NAO_INFORMADO);
    }

    @Test
    void classify_aprendiz_caiEmEstagio() {
        // Fase 10 — achado auditando NAO_INFORMADO: "aprendiz" é categoria
        // real do mercado BR (Lei da Aprendizagem), 111 vagas batiam só
        // com esse termo faltando no padrão.
        assertThat(classifier.classify("Programa Jovem Aprendiz 2026", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
        assertThat(classifier.classify("Aprendiz Administrativo", null)).isEqualTo(SeniorityClassifier.ESTAGIO);
    }

    @Test
    void classify_especialista_caiEmSenior() {
        // Fase 10 — 180 vagas com "especialista" caíam em NAO_INFORMADO.
        assertThat(classifier.classify("Especialista Azure Virtual Desktop", null)).isEqualTo(SeniorityClassifier.SENIOR);
        assertThat(classifier.classify("Engenheiro de Dados Especialista Databricks", null)).isEqualTo(SeniorityClassifier.SENIOR);
    }

    @Test
    void classify_plAbreviado_caiEmPleno() {
        // Fase 10 — 137 vagas com "Pl"/"PL" isolado (abreviação comum de
        // "pleno" no mercado BR) caíam em NAO_INFORMADO.
        assertThat(classifier.classify("Desenvolvedor Java PL", null)).isEqualTo(SeniorityClassifier.PLENO);
        assertThat(classifier.classify("Analista de Dados Pl.", null)).isEqualTo(SeniorityClassifier.PLENO);
        assertThat(classifier.classify("ANALISTA DESENVOLVEDOR PL (001051)", null)).isEqualTo(SeniorityClassifier.PLENO);
    }

    @Test
    void classify_plSql_naoConfundeComPlenoAbreviado() {
        // Falso positivo real encontrado auditando as amostras: PL/SQL é a
        // linguagem do Oracle, não "pleno" abreviado — o negative lookahead
        // no PLENO_PATTERN existe especificamente por causa deste caso.
        assertThat(classifier.classify("Analista de Sistemas PL/SQL - Implantação", null)).isEqualTo(SeniorityClassifier.NAO_INFORMADO);
    }

    @Test
    void classify_naoConfundePalavraParcialComTermoDeSenioridade() {
        // \b (word boundary) no regex evita que "senioridade" (substantivo
        // genérico, não indica nível) ou nomes de empresa/produto que
        // contenham as letras por acaso disparem falso positivo.
        assertThat(classifier.classify("Consultor de Seniority Management", null)).isEqualTo(SeniorityClassifier.NAO_INFORMADO);
    }
}

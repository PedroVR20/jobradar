package br.com.jobradar.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fase 1.5 — zero cobertura antes deste arquivo, apesar de ser o parser que
 * alimenta o campo Job.salary usado pelo modelo de estimativa salarial
 * inteiro (ver SalaryModelTrainerService). Casos reais vistos no catálogo
 * de produção durante a investigação da Fase 1 (formatos por fonte,
 * sentinela "R$ 200,00" da Gupy que não é salário de verdade).
 */
class SalaryExtractorTest {

    private final SalaryExtractor extractor = new SalaryExtractor();

    @Test
    void extract_semTexto_devolveNull() {
        assertThat(extractor.extract(null)).isNull();
        assertThat(extractor.extract("")).isNull();
        assertThat(extractor.extract("   ")).isNull();
    }

    @Test
    void extract_semPalavraChaveDeRemuneracao_ignoraValorMonetarioSolto() {
        // "R$" aparece no texto, mas sem "salário"/"remuneração" por perto —
        // é claramente preço de produto, não deve ser confundido com salário.
        String desc = "Nosso produto custa R$ 29,90 por mês de assinatura.";
        assertThat(extractor.extract(desc)).isNull();
    }

    @Test
    void extract_formatoQueroVagasTech_virgulaComoMilhar() {
        // "R$ 5,500/mês" — vírgula como separador de milhar (formato QueroVagasTech)
        String desc = "Oferecemos salário de R$ 5,500/mês para essa posição.";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado).contains("5,500");
    }

    @Test
    void extract_formatoGupy_pontoComoMilharVirgulaComoDecimal() {
        // "R$ 4.400,00" — formato BR tradicional (ponto = milhar, vírgula = decimal)
        String desc = "A remuneração para o cargo é de R$ 4.400,00 mensais.";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado).contains("4.400");
    }

    @Test
    void extract_faixaSalarial() {
        String desc = "Faixa salarial: R$ 3.000 – R$ 4.500 conforme experiência.";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
    }

    @Test
    void extract_sentinelaGupyR200_naoEDescartadaAquiPorqueEhSoParsing() {
        // "R$ 200,00" É extraído com sucesso pelo parser (o texto realmente
        // tem "salário: R$ 200,00") — o filtro que descarta isso como
        // provável sentinela/placeholder é responsabilidade do TREINO
        // (SalaryModelTrainerService.SALARY_FLOOR), não do extractor. Esse
        // teste documenta a fronteira: o extractor só extrai o que está no
        // texto, não julga se o valor faz sentido como salário mensal real.
        String desc = "Salário: R$ 200,00";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado).contains("200");
    }

    @Test
    void extract_valorImplausivel_menorQue100_eDescartado() {
        // Mesmo com palavra-chave por perto, um valor tipo "R$ 5" não é um
        // salário plausível (provavelmente taxa/desconto mencionado perto).
        String desc = "Salário compatível com o mercado, taxa de inscrição R$ 5.";
        assertThat(extractor.extract(desc)).isNull();
    }

    @Test
    void extract_euro() {
        // Símbolo de moeda vem ANTES do valor no padrão que o regex cobre
        // (mesma convenção de R$/US$/$) — "45.000 €" com o símbolo DEPOIS
        // (comum em alemão) não bate, é uma limitação real e não coberta
        // ainda; esse teste documenta o caso que já funciona hoje.
        String desc = "Gehalt: € 45.000 brutto pro Jahr, verhandelbar.";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado).contains("45.000");
    }

    @Test
    void extract_dolarComK() {
        String desc = "Compensation: $80k - $100k depending on experience.";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado.toLowerCase()).contains("k");
    }

    @Test
    void extract_removeTagsHtmlEEntidadeNbsp() {
        // &nbsp;/&amp; são tratados explicitamente no código; entidades tipo
        // &aacute; não são — por isso a keyword aqui usa acento real, não
        // entidade, pra isolar o que está sendo testado: remoção de tag HTML
        // e de &nbsp;.
        String desc = "<p>Salário:&nbsp;<b>R$ 6.000,00</b> por mês</p>";
        String achado = extractor.extract(desc);
        assertThat(achado).isNotNull();
        assertThat(achado).contains("6.000");
    }

    @Test
    void extract_naoConfundeFaturamentoDaEmpresaComSalario() {
        String desc = "Nossa empresa fatura R$ 10 milhões por ano e está em expansão.";
        assertThat(extractor.extract(desc)).isNull();
    }
}

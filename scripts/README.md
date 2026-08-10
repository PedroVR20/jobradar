# Treino do modelo de faixa salarial

Script offline (Python, roda fora do backend em produção) que treina o
modelo usado por `SalaryPredictionService` pra estimar faixa salarial.
`SalaryEstimateService.java` também vive aqui do lado do backend — é ele
quem limpa/converte os dados antes de exportar (parsing de R$/€/$, filtro de
tags-ruído) — o Python só recebe dado já limpo e faz a parte de matemática.

## Como retreinar (toda vez que quiser)

Com o backend rodando (`docker compose up -d`):

```bash
# 1. Exporta os dados de treino mais recentes do banco
curl -s http://localhost:8080/api/jobs/admin/salary-training-data > scripts/training_data.json

# 2. Instala as dependências (só na primeira vez — se já rodou antes, pula esse passo)
py -m pip install numpy scikit-learn pandas

# 3. Treina — imprime as métricas de avaliação (R², erro médio) no terminal
cd scripts
py train_salary_model.py

# 4. Copia o modelo treinado pro backend e reconstrói
cp salary_model.json ../backend/src/main/resources/salary_model.json
cd ..
docker compose up -d --build backend
```

Repita isso sempre que quiser — não tem custo além do tempo de rodar (leva
uns 2-3 segundos), e não tem por que não retreinar de vez em quando conforme
o banco recebe vagas novas.

**Como saber se melhorou:** compare o que o script imprime no passo 3 com a
tabela de "Métricas do modelo atual" mais abaixo (ou com a saída da vez
anterior que você rodou). Os três números que importam:

| Métrica | O que significa | Quero que seja... |
|---|---|---|
| `R²` (escala log) | Quanto da variação real dos salários o modelo consegue explicar (0 a 1) | **Maior** |
| `MAE em R$` | Erro médio em reais, "chutando" pra cada vaga do teste | **Menor** |
| `Erro percentual médio` | O mesmo erro, mas em % do salário real (mais fácil de interpretar) | **Menor** |

**Importante**: se o banco ganhar vagas de faixas salariais muito diferentes
das que já existiam (ex: mais vagas internacionais bem pagas), é NORMAL o
erro em R$/% subir mesmo com o R² melhorando — não significa piora, significa
que o problema ficou mais difícil (faixa de valores mais larga pra acertar).
Isso aconteceu quando adicionamos conversão de câmbio (ver Changelog abaixo).
Compare sempre os três números juntos, não só um isolado.

## Como o treino funciona, por dentro

1. **Cada vaga com salário vira uma "linha" de treino**: senioridade, tags
   técnicas (filtradas — só termos reconhecidos, tipo "python", "aws"),
   modalidade (remoto/híbrido/presencial) e estado, tudo isso como
   **features de entrada**; o salário mensal em R$ (já convertido se veio em
   €/$) é o **alvo** que o modelo tenta prever.
2. **One-hot encoding**: como "senioridade=SENIOR" não é um número, o script
   transforma cada categoria numa coluna binária (0 ou 1) — ex: se a vaga é
   SENIOR, a coluna "seniority=SENIOR" vira 1 e as outras (JUNIOR, PLENO...)
   ficam 0. Tags técnicas funcionam parecido, mas "multi-hot" (uma vaga pode
   ter várias tags ligadas ao mesmo tempo: java=1 E spring=1 E aws=1).
3. **Regressão Ridge**: é uma regressão linear — o modelo aprende um peso
   (número) pra cada feature, tipo "SENIOR soma tanto no log do salário",
   "ter a tag aws soma tanto", etc. A previsão final é só a soma de todos
   esses pesos (mais um "ponto de partida" base, o intercepto). "Ridge"
   quer dizer que ele penaliza pesos muito grandes durante o treino — isso
   evita que o modelo "decore" padrões que só existem por acaso nos dados
   de treino (overfitting), especialmente importante aqui porque temos
   bastante mais features (46+ colunas) do que seria ideal pra só ~400
   exemplos.
4. **Por que `log(salário)` e não o salário direto**: salário é bem
   assimétrico — a maioria das vagas paga pouco, poucas pagam muito mais.
   Treinar em log deixa essa distribuição mais "bem-comportada" pra uma
   regressão linear, e garante que a previsão nunca vire um número negativo
   depois de converter de volta (`exp(previsão) - 1`).
5. **Separação treino/teste**: 80% das vagas treinam o modelo, 20% ficam de
   fora só pra medir o quão bem ele generaliza pra vaga que nunca viu —
   é esse conjunto de 20% que gera as métricas (R², MAE, erro %) impressas
   no final. Sem essa separação, o "erro" reportado enganaria (mediria só
   quão bem ele decorou o que já viu, não quão bem ele prevê coisa nova).
6. **Escolha automática da regularização**: o script testa 30 valores
   diferentes de força de regularização (o "alpha" impresso no terminal) via
   validação cruzada, e fica com o que generaliza melhor — não é um número
   fixo escolhido a dedo.

## Métricas do modelo atual

- **410 amostras de treino** (após filtrar outliers [R$300, R$60.000])
- R² (escala log): **0,78**
- Erro médio: **~34%** do valor real
- Cobertura: 100% (sempre dá uma estimativa, diferente da mediana por vagas
  parecidas que só cobre uma fração dos casos, a que exige amostra mínima)

Trate a estimativa sempre como ponto de partida pra negociação, nunca como
número final — a UI mostra essa margem de erro explicitamente, não esconde
a incerteza.

## Changelog

- **2026-08-10 (v3)**: retreino simples após um fetch novo (+15 vagas, 11
  delas elegíveis pro treino) — amostra 400 → **410**. Dessa vez melhorou
  nas três métricas ao mesmo tempo (sem o trade-off do v2): R² 0,68 → **0,78**,
  MAE R$3.089 → **R$2.747**, erro percentual 55% → **34%**. Confirma o padrão
  esperado: conforme o banco acumula vagas de verdade (não só conversão de
  moeda nova), o modelo tende a melhorar de forma direta.
- **2026-08-09 (v2)**: adicionada conversão de câmbio (€/$ → R$, cotação do
  dia via `ExchangeRateService`) — amostra foi de 359 pra 400, vocabulário
  de tags de 27 pra 33 termos (ganhou termos que só apareciam em vagas
  internacionais: backend, frontend, php, sql, typescript, crm). R² melhorou
  (0,57 → 0,68) mas o erro em R$/% subiu (a faixa de salários a prever ficou
  mais larga com vagas internacionais bem pagas incluídas) — ver explicação
  na seção "Como retreinar" acima.
- **2026-08-09 (v1)**: primeira versão, só R$, 359 amostras, R²=0,57.

## Limitações conhecidas (não implementadas ainda)

- **Sem tipo de contrato (PJ/CLT)**: não é um campo estruturado hoje, então
  o modelo não distingue os dois — PJ costuma pagar mais nominalmente por
  não ter encargos trabalhistas, e isso não está sendo capturado.
- **Sem tamanho de empresa**: mesma limitação — startup pequena vs empresa
  grande não entra na equação.
- Ambos exigiriam primeiro extrair esses dados do texto das vagas (regex
  sobre título/descrição, cobertura seria parcial) antes de conseguir
  treinar com eles.
- **Heurística de período pra €/$ sem marcador explícito** (ex: "€45.000"
  sem dizer se é mensal ou anual): assume anual só se o valor for >= R$15k
  equivalente, e descarta o resto por ser ambíguo demais — ver comentário em
  `parseMonthlyForeign()` no `SalaryEstimateService.java`.

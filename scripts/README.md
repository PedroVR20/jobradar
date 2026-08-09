# Treino do modelo de faixa salarial

Script offline (Python, roda fora do backend em produção) que treina o
modelo usado por `SalaryPredictionService` pra estimar faixa salarial —
regressão Ridge sobre `log(salário)`, usando senioridade, stack (tags
técnicas), modalidade e estado como features. Ver a explicação completa no
cabeçalho de `train_salary_model.py`.

## Quando retreinar

O modelo atual (`backend/src/main/resources/salary_model.json`) foi
treinado em 2026-08-09 com 359 vagas. Vale retreinar de vez em quando
conforme o banco cresce — mais dados tendem a melhorar a precisão,
especialmente pra combinações de senioridade+stack pouco representadas hoje.

## Como rodar

```bash
# 1. Com o backend rodando (docker compose up -d), exporta os dados de treino
curl -s http://localhost:8080/api/jobs/admin/salary-training-data > scripts/training_data.json

# 2. Instala as dependências (uma vez só)
py -m pip install numpy scikit-learn pandas

# 3. Treina — imprime as métricas de avaliação (R², erro médio) no terminal
cd scripts
py train_salary_model.py

# 4. Copia o modelo treinado pro backend e reconstrói
cp salary_model.json ../backend/src/main/resources/salary_model.json
cd ..
docker compose up -d --build backend
```

## Métricas do modelo atual

- 359 amostras de treino (após filtrar outliers de salário fora de [R$300, R$60.000])
- R² (escala log): 0,57
- Erro médio: ~43% do valor real
- Cobertura: 100% (sempre dá uma estimativa, diferente da mediana por vagas
  parecidas que só cobre ~68% dos casos)

Pra contexto: comparado à abordagem anterior (mediana de vagas com mesma
senioridade + 1 tag em comum), que só cobria 68% dos casos com erro médio de
~51% nesses casos, o modelo treinado é uma melhoria real — mas ~43% de erro
ainda é uma margem grande. A UI mostra essa margem explicitamente, nunca
esconde a incerteza; trate a estimativa como ponto de partida pra negociação,
não como número final.

## Limitações conhecidas (não implementadas ainda)

- **Sem tipo de contrato (PJ/CLT)**: não é um campo estruturado hoje, então
  o modelo não distingue os dois — PJ costuma pagar mais nominalmente por
  não ter encargos trabalhistas, e isso não está sendo capturado.
- **Sem tamanho de empresa**: mesma limitação — startup pequena vs empresa
  grande não entra na equação.
- Ambos exigiriam primeiro extrair esses dados do texto das vagas (regex
  sobre título/descrição, cobertura seria parcial) antes de conseguir
  treinar com eles.

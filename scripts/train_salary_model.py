# -*- coding: utf-8 -*-
"""
Treina um modelo de regressão (Ridge) pra estimar salário mensal em BRL a
partir de vagas reais já cadastradas no Job Radar — roda offline (nunca faz
parte do backend em produção), o resultado (coeficientes + vocabulário de
features) é exportado como JSON e carregado pelo Java em
SalaryPredictionService na inicialização.

Uso:
    1. GET /api/jobs/admin/salary-training-data > scripts/training_data.json
    2. py scripts/train_salary_model.py
    3. Copia scripts/salary_model.json pra
       backend/src/main/resources/salary_model.json

Por que Ridge (regressão linear regularizada) em vez de um modelo mais
"esperto" tipo gradient boosting: com ~400 amostras e várias dezenas de
features (one-hot + multi-hot), um modelo linear regularizado generaliza
melhor que árvores (que tendem a overfitar com poucos dados) — e o resultado
treinado é só um vetor de pesos, trivial de portar pro Java sem precisar de
runtime Python em produção.
"""
import json
from collections import Counter

import numpy as np
from sklearn.linear_model import RidgeCV
from sklearn.model_selection import train_test_split
from sklearn.metrics import r2_score, mean_absolute_error

# --- 1. Carrega os dados exportados pelo backend -----------------------
with open("training_data.json", encoding="utf-8") as f:
    raw = json.load(f)

print(f"Linhas brutas exportadas: {len(raw)}")

# --- 2. Filtro de outliers de salário -----------------------------------
# Achamos "R$ 200" literal em algumas vagas de estágio (provavelmente
# auxílio/vale-transporte mal categorizado como salário, não um salário
# mensal de verdade) — sem filtrar, esses valores puxam a mediana e os
# coeficientes pra baixo de forma irreal. Faixa plausível pra salário
# mensal de vaga de tecnologia no Brasil: R$300 (piso bem conservador,
# exclui só o que claramente não é salário) a R$60.000 (teto bem folgado).
SALARY_FLOOR = 300
SALARY_CEIL = 60000
rows = [r for r in raw if SALARY_FLOOR <= r["salaryMonthly"] <= SALARY_CEIL]
print(f"Linhas após filtro de outliers [{SALARY_FLOOR}, {SALARY_CEIL}]: {len(rows)}")

# --- 3. Allowlist de tags técnicas ---------------------------------------
# Vimos que várias fontes (QueroVagasTech) colocam cidade/estado junto das
# tags de tecnologia de verdade ("são paulo", "belo horizonte", "paraná"...)
# — pra salário, local já vem melhor do campo `state` estruturado, então
# aqui a gente restringe "tags" a um vocabulário de tecnologia/cargo
# conhecido, descartando ruído geográfico e frases de requisito genéricas
# tipo "ensino medio completo"/"boa comunicacao oral e escrita" que também
# apareceram misturadas.
TECH_ALLOWLIST = {
    "python", "java", "javascript", "typescript", "csharp", "c#", "c++", ".net",
    "aws", "azure", "gcp", "cloud", "docker", "kubernetes", "devops",
    "postgres", "postgresql", "mysql", "sql", "oracle", "mongodb",
    "power bi", "business intelligence", "analytics", "dados", "data science",
    "machine learning", "inteligencia artificial", "inteligência artificial",
    "react", "angular", "vue", "node", "php", "spring", "django", "flask",
    "kotlin", "swift", "ruby", "go", "rust", "html", "css",
    "sap", "salesforce", "servicenow", "itil", "erp", "crm",
    "redes", "infraestrutura", "suporte", "telecomunicacoes",
    "seguranca da informacao", "cybersecurity", "qa", "testes", "automacao", "rpa",
    "full stack", "backend", "frontend", "mobile", "software", "tecnologia",
    "sistemas", "programador", "desenvolvedor", "analista", "engenheiro",
    "especialista", "arquiteto", "coordenador",
}


def norm(s):
    return s.strip().lower()


def clean_tags(tags):
    return sorted({norm(t) for t in tags if norm(t) in TECH_ALLOWLIST})


# --- 4. Distribuição de estados (bucket nos mais frequentes) ------------
state_counts = Counter(norm(r["state"]) for r in rows if r.get("state"))
TOP_STATES = [s for s, _ in state_counts.most_common(8)]
print("Estados mais frequentes (usados como categoria própria):", TOP_STATES)


def bucket_state(state):
    if not state:
        return "desconhecido"
    n = norm(state)
    return n if n in TOP_STATES else "outros"


# --- 5. Vocabulário de tags (só as que sobraram do allowlist, com uso mínimo) --
tag_counts = Counter()
for r in rows:
    for t in clean_tags(r["tags"]):
        tag_counts[t] += 1
MIN_TAG_FREQ = 3
TAG_VOCAB = sorted([t for t, c in tag_counts.items() if c >= MIN_TAG_FREQ])
print(f"Vocabulário de tags técnicas (freq >= {MIN_TAG_FREQ}): {len(TAG_VOCAB)} — {TAG_VOCAB}")

SENIORITY_VOCAB = ["ESTAGIO", "JUNIOR", "PLENO", "SENIOR", "NAO_INFORMADO"]
WORKPLACE_VOCAB = ["REMOTO", "HIBRIDO", "PRESENCIAL", "DESCONHECIDO"]
STATE_VOCAB = TOP_STATES + ["outros", "desconhecido"]

FEATURE_NAMES = (
    [f"seniority={s}" for s in SENIORITY_VOCAB]
    + [f"workplace={w}" for w in WORKPLACE_VOCAB]
    + [f"state={s}" for s in STATE_VOCAB]
    + [f"tag={t}" for t in TAG_VOCAB]
)
print(f"Total de features: {len(FEATURE_NAMES)}")


def build_features(row):
    vec = np.zeros(len(FEATURE_NAMES))
    idx = {name: i for i, name in enumerate(FEATURE_NAMES)}

    sen = row["seniority"] if row["seniority"] in SENIORITY_VOCAB else "NAO_INFORMADO"
    vec[idx[f"seniority={sen}"]] = 1.0

    wp = row.get("workplaceType") or "DESCONHECIDO"
    if wp not in WORKPLACE_VOCAB:
        wp = "DESCONHECIDO"
    vec[idx[f"workplace={wp}"]] = 1.0

    st = bucket_state(row.get("state"))
    vec[idx[f"state={st}"]] = 1.0

    for t in clean_tags(row["tags"]):
        if t in TAG_VOCAB:
            vec[idx[f"tag={t}"]] = 1.0

    return vec


X = np.array([build_features(r) for r in rows])
y_raw = np.array([r["salaryMonthly"] for r in rows], dtype=float)
# treina em escala log — salário é bem assimétrico (poucos valores muito
# altos puxando a média), log deixa a regressão mais estável e a predição
# nunca sai negativa depois de exponenciar de volta.
y = np.log1p(y_raw)

# --- 6. Treino com validação cruzada pra escolher a regularização --------
X_train, X_test, y_train, y_test, y_train_raw, y_test_raw = train_test_split(
    X, y, y_raw, test_size=0.2, random_state=42
)

model = RidgeCV(alphas=np.logspace(-2, 3, 30), cv=5)
model.fit(X_train, y_train)
print(f"Melhor alpha (regularização): {model.alpha_:.3f}")

# --- 7. Avaliação honesta -------------------------------------------------
pred_log = model.predict(X_test)
pred_brl = np.expm1(pred_log)
r2 = r2_score(y_test, pred_log)
mae_brl = mean_absolute_error(y_test_raw, pred_brl)
mae_pct = float(np.mean(np.abs(pred_brl - y_test_raw) / y_test_raw))

print(f"\n=== Avaliação (conjunto de teste, {len(y_test)} amostras) ===")
print(f"R² (escala log): {r2:.3f}")
print(f"MAE em R$: {mae_brl:,.0f}")
print(f"Erro percentual médio: {mae_pct * 100:.1f}%")

# --- 8. Exporta coeficientes + vocabulário pro Java carregar -------------
export = {
    "trainedAt": "2026-08-09",
    "nSamples": len(rows),
    "salaryFloor": SALARY_FLOOR,
    "salaryCeil": SALARY_CEIL,
    "logTarget": True,
    "intercept": float(model.intercept_),
    "featureNames": FEATURE_NAMES,
    "coefficients": [float(c) for c in model.coef_],
    "seniorityVocab": SENIORITY_VOCAB,
    "workplaceVocab": WORKPLACE_VOCAB,
    "stateVocab": STATE_VOCAB,
    "tagVocab": TAG_VOCAB,
    "metrics": {
        "r2LogScale": round(float(r2), 4),
        "maeBrl": round(float(mae_brl), 2),
        "maePercent": round(mae_pct * 100, 1),
        "testSamples": len(y_test),
    },
}

with open("salary_model.json", "w", encoding="utf-8") as f:
    json.dump(export, f, ensure_ascii=False, indent=2)

print("\nModelo exportado em scripts/salary_model.json")

-- Fase 12 — diagnóstico novo depois das Fases 5 e 6: a Fase 6.2 criou 5
-- índices sem medir se o planner realmente ia usá-los. pg_stat_user_indexes
-- mostrou dois com ZERO leituras desde que foram criados. Esta migração
-- corrige os dois e soma os que faltavam.

-- 12.1 — idx_jobs_state (índice antigo, sobre a coluna crua) nunca foi
-- usado pela aplicação: o filtro de estado sempre rodou em Java, ignorando
-- acento (Fase 5.5), então nenhuma query de verdade batia com um índice
-- sobre "state" cru. Decisão tomada (não presumida): mover o filtro pra
-- SQL via unaccent — medido com EXPLAIN antes de decidir, 0,8ms contra
-- 191 linhas via Bitmap Index Scan quando o índice bate com a expressão
-- real da query. Ver JobSpecifications.byState.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- unaccent() de fábrica é STABLE, não IMMUTABLE (depende em teoria do
-- dicionário de configuração de busca textual) — Postgres recusa
-- "functions in index expression must be marked IMMUTABLE" se tentar usar
-- direto num índice (confirmado testando esta migração ANTES de aplicar em
-- produção, contra uma cópia restaurada do banco real, não presumido).
-- Wrapper fixando o dicionário 'unaccent' explicitamente é o workaround
-- padrão documentado pelo próprio Postgres pra esse caso — o dicionário
-- não muda em runtime numa instância normal, então IMMUTABLE aqui é seguro.
CREATE OR REPLACE FUNCTION immutable_unaccent(text) RETURNS text AS
$$ SELECT unaccent('unaccent', $1) $$
LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT;

DROP INDEX IF EXISTS idx_jobs_state;
CREATE INDEX idx_jobs_state ON jobs (lower(immutable_unaccent(state)));

-- 12.2 — idx_jobs_seen_rejected (composto, sem condição) cobria uma
-- consulta que seleciona 97% da tabela (aba "Novas": seen=false), onde o
-- planner corretamente prefere seq scan — índice nunca lido. A fatia rara
-- é o oposto (seen=true, ~3% da tabela): índice PARCIAL só nesse lado,
-- que os specs onlySeen/onlyInteressado/onlyApplied/onlyInProgress podem
-- de fato aproveitar.
DROP INDEX IF EXISTS idx_jobs_seen_rejected;
CREATE INDEX idx_jobs_seen_true_rejected ON jobs (rejected) WHERE seen = true;

-- 12.3 — job_events.job_id (FK) não tinha índice, só a PK em id. Toda
-- timeline de vaga (GET /api/jobs/{id}/events) era seq scan — confirmado
-- no EXPLAIN. 49 linhas hoje, momento de criar é antes de doer.
CREATE INDEX idx_job_events_job_id ON job_events (job_id);

-- 12.4 — jobs.fetched_at esquecido na Fase 6.2 original. Usado em
-- GET /api/jobs/stats (hojeCount), GET /api/jobs/new-since, e na limpeza
-- periódica de vaga recusada há mais de 7 dias — seq scan confirmado nos
-- três antes desta migração.
CREATE INDEX idx_jobs_fetched_at ON jobs (fetched_at);

-- 12.7 — precisa de shared_preload_libraries=pg_stat_statements no boot
-- do Postgres (ver docker-compose.yml) ANTES desta linha rodar, senão
-- falha com "pg_stat_statements must be loaded via shared_preload_libraries".
-- Extensão "trusted" desde o Postgres 13 — não precisa de superuser,
-- CREATE no database já basta (o usuário jobradar é dono do banco).
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

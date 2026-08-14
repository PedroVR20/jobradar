-- Fase 7+8 — campos novos pra qualidade do catálogo e triagem. Um único
-- migration cobre os 4 campos porque todos entram na mesma tabela (jobs) e
-- foram desenhados juntos (ver Job.java pros comentários de cada um).

ALTER TABLE jobs ADD COLUMN fora_de_area boolean;

ALTER TABLE jobs ADD COLUMN archived boolean NOT NULL DEFAULT false;
ALTER TABLE jobs ADD COLUMN archived_at timestamp;
ALTER TABLE jobs ADD COLUMN archived_reason character varying(255);

ALTER TABLE jobs ADD COLUMN link_morto boolean;
ALTER TABLE jobs ADD COLUMN link_checked_at timestamp;

ALTER TABLE jobs ADD COLUMN company_normalized character varying(255);

ALTER TABLE jobs ADD COLUMN rejected_reason character varying(255);

-- Índices parciais nas colunas booleanas novas — mesma lógica da Fase 12.2
-- (idx_jobs_seen_true_rejected): a fatia marcada é sempre a minoria, então
-- um índice parcial no lado raro serve o filtro sem o custo de indexar
-- linha nenhuma no lado comum (false/null).
CREATE INDEX idx_jobs_archived_true ON jobs (id) WHERE archived = true;
CREATE INDEX idx_jobs_fora_de_area_true ON jobs (id) WHERE fora_de_area = true;

-- Usado pra agrupar por empresa em dedup retroativa (7.4) e no futuro
-- histórico por empresa — sem WHERE porque toda linha classificada tem
-- valor (preenchido no fetch e no backfill único ao aplicar esta migração).
CREATE INDEX idx_jobs_company_normalized ON jobs (company_normalized);

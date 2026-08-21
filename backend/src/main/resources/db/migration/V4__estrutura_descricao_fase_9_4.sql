-- Fase 9.4 — extração de estrutura da descrição via IA (ver
-- JobStructureExtractorService). Sob demanda e cacheada por vaga: sem
-- estrutura_extraida_em, o backend não teria como distinguir "nunca
-- tentou" de "tentou e não achou nada útil", e reprocessaria a mesma vaga
-- toda vez que o usuário abrisse o painel — gasto de cota de IA à toa.

ALTER TABLE jobs ADD COLUMN requisitos_obrigatorios TEXT;
ALTER TABLE jobs ADD COLUMN requisitos_desejaveis TEXT;
ALTER TABLE jobs ADD COLUMN anos_experiencia_min integer;
ALTER TABLE jobs ADD COLUMN escolaridade_requerida character varying(255);
ALTER TABLE jobs ADD COLUMN beneficios TEXT;
ALTER TABLE jobs ADD COLUMN estrutura_extraida_em timestamp;

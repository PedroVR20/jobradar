-- Fase 9.6 — sinais de alerta na descrição, extraídos JUNTO com a estrutura
-- da Fase 9.4 (mesma chamada de Gemini, mesma vaga, mesmo texto de entrada —
-- separar em duas chamadas dobraria o custo de IA à toa pra ler a mesma
-- descrição duas vezes). Reaproveita jobs.estrutura_extraida_em como
-- marcador de cache pra essa coluna também (ver JobStructureExtractorService).

ALTER TABLE jobs ADD COLUMN sinais_alerta TEXT;

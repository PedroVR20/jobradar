export type JobSource = 'REMOTIVE' | 'ARBEITNOW' | 'WWR' | 'GUPY' | 'EURECA' | 'GLASSDOOR' | 'MANUAL' | 'QUEROVAGASTECH' | 'NERDIN';

export type Seniority = 'ESTAGIO' | 'JUNIOR' | 'PLENO' | 'SENIOR' | 'NAO_INFORMADO';

export type WorkplaceType = 'REMOTO' | 'HIBRIDO' | 'PRESENCIAL';

export interface Job {
  id: number;
  title: string;
  company: string;
  url: string;
  source: JobSource;
  seniority: Seniority;
  salary: string | null;
  workplaceType: WorkplaceType | null;
  state: string | null;
  city: string | null;
  tags: string[];
  postedAt: string | null;
  expiresAt: string | null;
  fetchedAt: string | null;
  seen: boolean;
  interested: boolean;
  applied: boolean;
  appliedAt: string | null;
  inProgress: boolean;
  inProgressAt: string | null;
  rejected: boolean;
  rejectedAt: string | null;
  companyLogoUrl: string | null;
  pcd: boolean;
  pinned: boolean;
  notes: string | null;
  classifiedByAi: boolean;
}

// Status da integração com Gemini — GET /api/jobs/ai-status
export interface AiStatus {
  enabled: boolean;
  model: string | null;
  // Contagem aproximada de chamadas feitas hoje — não é a oficial do Google
  // (reseta se o backend reiniciar), só um sinal antes de bater no limite.
  requestsToday: number | null;
  // Presente só quando há mais de uma GEMINI_API_KEYS configurada — o
  // backend faz rodízio automático entre elas quando uma bate no limite.
  keyPool: { total: number; availableToday: number; exhaustedToday: number } | null;
}

// GET /api/jobs/{id}/salary-estimate — dado real do banco, não IA
export interface SalaryEstimate {
  available: boolean;
  predicted?: number;
  modelInfo?: { nSamples: number; r2: number; maePercent: number };
  similarJobs?: { sampleSize: number; min: number; max: number; median: number };
}

// POST /api/jobs/{id}/salary-estimate/personalized
export interface PersonalizedSalaryEstimate {
  available: boolean;
  predicted?: number;
  inferredSeniority?: string;
  inferredStack?: string[];
}

// POST /api/jobs/assistant/compatibility-scan — usado pelo painel 🤖 Jarvis
export interface CompatibilityHit {
  job: { id: number; title: string; company: string; url: string; source: string };
  score: number;
  pontosFortes: string[];
  pontosFaltando: string[];
  resumo: string;
}

export interface CompatibilityScanResult {
  available: boolean;
  totalConsiderados: number;
  totalAnalisadosPorIa: number;
  hits: CompatibilityHit[];
  errorMessage: string | null;
}

// POST /api/jobs/{id}/match-score
export interface MatchScoreResult {
  score: number;
  pontosFortes: string[];
  pontosFaltando: string[];
  resumo: string;
}

// POST /api/jobs/assistant/chat — chat livre do 🤖 Jarvis (function-calling
// de verdade: o Gemini decide sozinho quais ferramentas chamar a partir da
// mensagem em linguagem natural, sem roteamento por palavra-chave).
export interface JarvisVagaResumo {
  id: number;
  title: string;
  company: string;
  status: string;
  url: string;
  postedAt: string | null;
}

export interface JarvisListarVagasData {
  totalEncontradas: number;
  vagas: JarvisVagaResumo[];
}

export interface JarvisResumoFunilData {
  total: number;
  novas: number;
  interessadas: number;
  aplicadas: number;
  emAndamento: number;
  recusadas: number;
}

export interface JarvisCompatibilidadeHit {
  id: number;
  titulo: string;
  empresa: string;
  url: string;
  score: number;
  resumo: string;
}

export interface JarvisCompatibilidadeData {
  available: boolean;
  totalConsiderados: number;
  totalAnalisadosPorIa: number;
  hits: JarvisCompatibilidadeHit[];
  erro?: string;
}

export interface JarvisToolResult {
  tool: 'listarVagas' | 'resumoFunil' | 'compatibilidadeComVagasRecentes';
  data: JarvisListarVagasData | JarvisResumoFunilData | JarvisCompatibilidadeData;
}

export interface JarvisChatResponse {
  reply: string | null;
  toolResults: JarvisToolResult[];
}

export interface DuplicateJobRef {
  id: number;
  title: string;
  source: JobSource;
  url: string;
  postedAt: string | null;
}

export interface DuplicateGroup {
  company: string;
  jobs: DuplicateJobRef[];
  // true = a IA (Gemini) confirmou que são a mesma vaga, além do Jaccard por
  // palavras; false/omitido = só o veredito por similaridade de título (sem IA)
  aiVerificado?: boolean;
}

export interface Stats {
  total: number;
  novas: number;
  interessadas: number;
  aplicadas: number;
  emAndamento: number;
  recusadas: number;
  hojeCount: number;
  porFonte: {
    REMOTIVE: number;
    ARBEITNOW: number;
    WWR: number;
    GUPY: number;
    EURECA: number;
    QUEROVAGASTECH: number;
    NERDIN: number;
  };
  porSenioridade: Record<Seniority, number>;
}

export interface Metrics {
  totalAplicadas: number;
  emAndamento: number;
  recusadas: number;
  aguardandoRetorno: number;
  taxaResposta: number | null;
  aplicacoesPorSemana: { semana: string; count: number }[];
  tempoMedioAteAndamentoDias: number | null;
  tempoMedioAteRecusaDias: number | null;
}

export type SortOption = 'posted_desc' | 'posted_asc' | 'fetched_desc';

export type ViewMode = 'novas' | 'vistas' | 'interessado' | 'aplicadas' | 'andamento' | 'recusadas';

export type JobStatus = 'NOVA' | 'VISTA' | 'INTERESSADO' | 'APLICADA' | 'ANDAMENTO' | 'RECUSADA';

export const statusMeta: Record<JobStatus, string> = {
  NOVA: '🔴 Nova (não vista)',
  VISTA: '👁 Já vista',
  INTERESSADO: '⭐ Interessado',
  APLICADA: '✅ Aplicada',
  ANDAMENTO: '🔄 Em Andamento',
  RECUSADA: '❌ Recusada/congelada',
};

// Vagas recusadas somem sozinhas depois de tantos dias (espelha o backend)
export const DIAS_PARA_EXCLUIR_RECUSADAS = 7;

export interface Filters {
  source: string;
  search: string;
  seniority: string;
  workplaceType: string;
  state: string;
  days: string; // '' = qualquer data | '1' | '3' | '7' | '14' | '30'
  sort: SortOption;
  viewMode: ViewMode;
  beginnerMode: boolean;   // true = mostra só ESTAGIO + JUNIOR
  techStack: string[];     // pills ativos — vaga aparece se tiver QUALQUER um (OR)
}

export const seniorityMeta: Record<Seniority, { label: string; short: string; color: string }> = {
  ESTAGIO:       { label: '🎓 Estágio',       short: 'Estágio', color: '#06b6d4' },
  JUNIOR:        { label: '🌱 Júnior',        short: 'Júnior',  color: '#22c55e' },
  PLENO:         { label: '🚀 Pleno',         short: 'Pleno',   color: '#3b82f6' },
  SENIOR:        { label: '⭐ Sênior',        short: 'Sênior',  color: '#a855f7' },
  NAO_INFORMADO: { label: 'Não informado',    short: 'N/I',     color: '#64748b' },
};

export const sourceMeta: Record<string, { label: string; color: string }> = {
  REMOTIVE:  { label: 'Remotive',         color: '#6366f1' },
  ARBEITNOW: { label: 'Arbeitnow (EU)',   color: '#10b981' },
  WWR:       { label: 'We Work Remotely', color: '#f59e0b' },
  GUPY:      { label: 'Gupy (BR)',        color: '#ec4899' },
  EURECA:    { label: 'Eureca (BR)',      color: '#14b8a6' },
  GLASSDOOR:        { label: 'Glassdoor',              color: '#0caa41' },
  MANUAL:           { label: 'Adicionada manualmente', color: '#94a3b8' },
  QUEROVAGASTECH:   { label: 'QueroVagasTech (BR)',    color: '#f97316' },
  NERDIN:           { label: 'Nerdin (BR)',            color: '#8b5cf6' },
};

export const workplaceMeta: Record<WorkplaceType, { label: string; icon: string }> = {
  REMOTO:     { label: 'Remoto',     icon: '🏠' },
  HIBRIDO:    { label: 'Híbrido',    icon: '🔀' },
  PRESENCIAL: { label: 'Presencial', icon: '🏢' },
};

// Payload pra adicionar uma vaga manualmente (Glassdoor, LinkedIn, indicação...)
export interface ManualJobPayload {
  title: string;
  company: string;
  url: string;
  source?: string;
  salary?: string;
  workplaceType?: WorkplaceType;
  state?: string;
  city?: string;
  tags?: string;
  status?: JobStatus;
}

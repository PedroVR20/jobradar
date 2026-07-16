export type JobSource = 'REMOTIVE' | 'ARBEITNOW' | 'WWR' | 'GUPY' | 'EURECA' | 'GLASSDOOR' | 'MANUAL' | 'QUEROVAGASTECH';

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
  applied: boolean;
  inProgress: boolean;
  rejected: boolean;
  rejectedAt: string | null;
  companyLogoUrl: string | null;
  pcd: boolean;
  pinned: boolean;
  notes: string | null;
}

export interface Stats {
  total: number;
  novas: number;
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
  };
  porSenioridade: Record<Seniority, number>;
}

export type SortOption = 'posted_desc' | 'posted_asc' | 'fetched_desc';

export type ViewMode = 'novas' | 'vistas' | 'aplicadas' | 'andamento' | 'recusadas';

export type JobStatus = 'NOVA' | 'VISTA' | 'APLICADA' | 'ANDAMENTO' | 'RECUSADA';

export const statusMeta: Record<JobStatus, string> = {
  NOVA: '🔴 Nova (não vista)',
  VISTA: '👁 Já vista',
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

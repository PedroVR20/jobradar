import { describe, expect, it } from 'vitest';
import { currentStatus, daysUntilDeletion } from './JobCard';
import { Job } from '../types/Job';

// Fase 10.1 — primeiro teste de frontend do projeto. currentStatus decide
// qual badge de status aparece no card (precedência entre os campos
// booleanos seen/interested/applied/inProgress/rejected) — regra fácil de
// quebrar silenciosamente ao adicionar um campo novo sem atualizar a ordem
// de precedência.
function baseJob(overrides: Partial<Job> = {}): Job {
  return {
    id: 1,
    title: 'Dev Backend',
    company: 'Acme',
    url: 'https://example.com/vaga/1',
    source: 'MANUAL',
    seniority: 'PLENO',
    salary: null,
    workplaceType: null,
    state: null,
    city: null,
    tags: [],
    postedAt: null,
    expiresAt: null,
    fetchedAt: null,
    seen: false,
    interested: false,
    applied: false,
    appliedAt: null,
    inProgress: false,
    inProgressAt: null,
    rejected: false,
    rejectedAt: null,
    companyLogoUrl: null,
    pcd: false,
    pinned: false,
    notes: null,
    classifiedByAi: false,
    archived: false,
    archivedReason: null,
    linkMorto: null,
    rejectedReason: null,
    ...overrides,
  };
}

describe('currentStatus', () => {
  it('vaga sem nenhuma flag é NOVA', () => {
    expect(currentStatus(baseJob())).toBe('NOVA');
  });

  it('seen=true vira VISTA', () => {
    expect(currentStatus(baseJob({ seen: true }))).toBe('VISTA');
  });

  it('interested=true vira INTERESSADO', () => {
    expect(currentStatus(baseJob({ seen: true, interested: true }))).toBe('INTERESSADO');
  });

  it('applied=true vira APLICADA', () => {
    expect(currentStatus(baseJob({ seen: true, applied: true }))).toBe('APLICADA');
  });

  it('inProgress=true vira ANDAMENTO, mesmo com applied=true junto', () => {
    expect(currentStatus(baseJob({ seen: true, applied: true, inProgress: true }))).toBe('ANDAMENTO');
  });

  it('rejected=true tem precedência sobre TODAS as outras flags', () => {
    // Igual ao patchParaStatus do useJobs.ts no fluxo real: RECUSADA não
    // apaga applied (ver Fase 8.7) — mas o status EXIBIDO ainda é RECUSADA.
    expect(currentStatus(baseJob({
      seen: true, interested: true, applied: true, inProgress: true, rejected: true,
    }))).toBe('RECUSADA');
  });
});

describe('daysUntilDeletion', () => {
  it('vaga recusada hoje ainda tem os 7 dias inteiros restantes', () => {
    const hoje = new Date().toISOString();
    expect(daysUntilDeletion(hoje)).toBe(7);
  });

  it('vaga recusada há 5 dias tem 2 dias restantes', () => {
    const cincoDiasAtras = new Date(Date.now() - 5 * 86400000).toISOString();
    expect(daysUntilDeletion(cincoDiasAtras)).toBe(2);
  });

  it('vaga recusada há mais de 7 dias (não apagada ainda por algum motivo) fica travada em 0, nunca negativa', () => {
    const dezDiasAtras = new Date(Date.now() - 10 * 86400000).toISOString();
    expect(daysUntilDeletion(dezDiasAtras)).toBe(0);
  });
});

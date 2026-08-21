import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook } from '@testing-library/react';
import { useJobs } from './useJobs';
import { Filters } from '../types/Job';

// Fase 16.7 — cobre o bug real: antes, TODO refetch de página 0 (filtro
// mudou, aba trocou, cada tecla digitada na busca) ligava `loading`, e
// App.tsx trocava o grid inteiro pelo SkeletonGrid a cada um desses —
// mesmo já tendo vaga real na tela. Agora só o carregamento INICIAL liga
// `loading`; refetches depois disso ligam `refetching` (grid existente só
// esmaece, não é substituído).
function baseFilters(overrides: Partial<Filters> = {}): Filters {
  return {
    source: '', search: '', seniority: '', workplaceType: '', state: '',
    days: '', sort: 'posted_desc', viewMode: 'novas', beginnerMode: false,
    techStack: [],
    ...overrides,
  };
}

function emptyPage() {
  return { content: [], totalElements: 0, page: 0, size: 30, totalPages: 0 };
}

describe('useJobs — loading vs refetching (Fase 16.7)', () => {
  beforeEach(() => {
    vi.useFakeTimers();
    vi.stubGlobal('fetch', vi.fn((url: string) => {
      if (url.includes('/states') || url.includes('/sources')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve([]) } as Response);
      }
      if (url.includes('/stats')) {
        return Promise.resolve({ ok: true, json: () => Promise.resolve({}) } as Response);
      }
      // GET /api/jobs?...
      return Promise.resolve({ ok: true, json: () => Promise.resolve(emptyPage()) } as Response);
    }));
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
  });

  it('carregamento inicial liga loading, não refetching', async () => {
    const { result } = renderHook(({ filters }) => useJobs(filters), {
      initialProps: { filters: baseFilters() },
    });

    // efeito debounced (250ms) ainda não disparou
    expect(result.current.loading).toBe(true);
    expect(result.current.refetching).toBe(false);

    await act(async () => { await vi.runAllTimersAsync(); });

    expect(result.current.loading).toBe(false);
    expect(result.current.refetching).toBe(false);
  });

  it('refetch por mudança de filtro liga refetching, NÃO loading', async () => {
    const { result, rerender } = renderHook(({ filters }) => useJobs(filters), {
      initialProps: { filters: baseFilters() },
    });

    // completa o carregamento inicial primeiro
    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.loading).toBe(false);

    const chamadasAntes = (fetch as ReturnType<typeof vi.fn>).mock.calls
      .filter(([url]) => (url as string).startsWith('/api/jobs?')).length;

    // muda o filtro (ex: usuário digitou na busca) — dispara um novo
    // efeito debounced de 250ms. loading precisa continuar false NA HORA
    // (o bug real: isso virava true de novo aqui, trocando o grid pelo
    // esqueleto a cada tecla).
    rerender({ filters: baseFilters({ search: 'java' }) });
    expect(result.current.loading).toBe(false);

    await act(async () => { await vi.runAllTimersAsync(); });
    expect(result.current.refetching).toBe(false);

    // confirma que o refetch de verdade aconteceu (senão o teste passaria
    // à toa mesmo se o debounce nunca tivesse disparado).
    const chamadasDepois = (fetch as ReturnType<typeof vi.fn>).mock.calls
      .filter(([url]) => (url as string).startsWith('/api/jobs?')).length;
    expect(chamadasDepois).toBeGreaterThan(chamadasAntes);
    expect(result.current.loading).toBe(false);
  });
});

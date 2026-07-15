import { useState, useEffect, useCallback } from 'react';
import { Job, JobStatus, ManualJobPayload, Stats, Filters } from '../types/Job';

const API = '/api/jobs';

export function useJobs(filters: Filters) {
  const [jobs, setJobs] = useState<Job[]>([]);
  const [stats, setStats] = useState<Stats | null>(null);
  const [states, setStates] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // lista de estados só muda quando novas vagas chegam; carrega uma vez
  useEffect(() => {
    fetch(`${API}/states`).then(r => r.json()).then(setStates).catch(() => {});
  }, []);

  const buildQuery = useCallback(() => {
    const params = new URLSearchParams();
    if (filters.source) params.set('source', filters.source);

    // só a busca textual vai pro backend (lógica AND por palavra)
    // os pills de tech stack são filtrados no cliente com lógica OR
    if (filters.search) params.set('search', filters.search);

    // beginnerMode sobrescreve seniority manual: mostra só ESTAGIO e JUNIOR
    if (filters.beginnerMode) {
      params.set('seniority', 'ESTAGIO,JUNIOR');
    } else if (filters.seniority) {
      params.set('seniority', filters.seniority);
    }

    if (filters.workplaceType) params.set('workplaceType', filters.workplaceType);
    if (filters.state) params.set('state', filters.state);
    if (filters.days) params.set('days', filters.days);
    if (filters.sort) params.set('sort', filters.sort);
    if (filters.viewMode === 'novas') params.set('onlyNew', 'true');
    if (filters.viewMode === 'vistas') params.set('onlySeen', 'true');
    if (filters.viewMode === 'aplicadas') params.set('onlyApplied', 'true');
    if (filters.viewMode === 'andamento') params.set('onlyInProgress', 'true');
    if (filters.viewMode === 'recusadas') params.set('onlyRejected', 'true');
    return params.toString();
  }, [filters]);

  const loadJobs = useCallback(async (silent = false) => {
    if (!silent) setLoading(true);
    setError(null);
    try {
      const query = buildQuery();
      const [jobsRes, statsRes] = await Promise.all([
        fetch(`${API}?${query}`),
        fetch(`${API}/stats`)
      ]);
      const allJobs = await jobsRes.json() as Job[];
      // pills: OR logic no cliente — vaga aparece se o título/empresa/tags
      // contiver QUALQUER um dos pills selecionados
      const filtered = filters.techStack.length === 0
        ? allJobs
        : allJobs.filter(j => {
            const hay = (j.title + ' ' + j.company + ' ' + j.tags.join(' ')).toLowerCase();
            return filters.techStack.some(p => hay.includes(p.toLowerCase()));
          });
      setJobs(filtered);
      setStats(await statsRes.json());
    } catch {
      setError('Erro ao carregar vagas. Verifique se o backend está rodando.');
    } finally {
      if (!silent) setLoading(false);
    }
  }, [buildQuery]);

  // debounce: evita uma requisição a cada tecla digitada na busca
  useEffect(() => {
    const t = setTimeout(() => loadJobs(), 250);
    return () => clearTimeout(t);
  }, [loadJobs]);

  const markSeen = async (id: number) => {
    await fetch(`${API}/${id}/seen`, { method: 'PATCH' });
    setJobs(prev => prev.map(j => j.id === id ? { ...j, seen: true } : j));
    loadJobs(true); // reflete a mudança de aba (novas → já vistas) e atualiza stats, sem piscar loading
  };

  const markApplied = async (id: number) => {
    await fetch(`${API}/${id}/applied`, { method: 'PATCH' });
    setJobs(prev => prev.map(j =>
      j.id === id ? { ...j, applied: true, seen: true, inProgress: false } : j
    ));
    loadJobs(true); // atualiza stats sem piscar loading
  };

  const markInProgress = async (id: number) => {
    await fetch(`${API}/${id}/in-progress`, { method: 'PATCH' });
    setJobs(prev => prev.map(j =>
      j.id === id ? { ...j, applied: true, seen: true, inProgress: true } : j
    ));
    loadJobs(true); // atualiza stats sem piscar loading
  };

  // Move a vaga direto pra um status, independente do atual — usado pelo
  // menu "⋮" do card (alternativa ao drag-and-drop pra pular entre abas).
  const setStatus = async (id: number, status: JobStatus) => {
    await fetch(`${API}/${id}/status?value=${status}`, { method: 'PATCH' });
    const patch: Partial<Job> = {
      NOVA:      { seen: false, applied: false, inProgress: false, rejected: false, rejectedAt: null },
      VISTA:     { seen: true,  applied: false, inProgress: false, rejected: false, rejectedAt: null },
      APLICADA:  { seen: true,  applied: true,  inProgress: false, rejected: false, rejectedAt: null },
      ANDAMENTO: { seen: true,  applied: true,  inProgress: true,  rejected: false, rejectedAt: null },
      RECUSADA:  { seen: true,  applied: true,  inProgress: false, rejected: true,  rejectedAt: new Date().toISOString() },
    }[status];
    setJobs(prev => prev.map(j => j.id === id ? { ...j, ...patch } : j));
    loadJobs(true); // reflete a mudança de aba e atualiza stats sem piscar loading
  };

  // Adiciona uma vaga manualmente (achada fora das fontes automáticas, tipo
  // Glassdoor/LinkedIn). Retorna a vaga criada/atualizada, ou null em erro.
  const addManualJob = async (payload: ManualJobPayload): Promise<Job | null> => {
    const res = await fetch(`${API}/manual`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) return null;
    const job = await res.json() as Job;
    await loadJobs();
    return job;
  };

  const triggerFetch = async () => {
    setFetching(true);
    try {
      const res = await fetch(`${API}/fetch`, { method: 'POST' });
      const data = await res.json();
      await loadJobs();
      return data.novasVagas as number;
    } finally {
      setFetching(false);
    }
  };

  const toggleFavorite = async (id: number) => {
    const res = await fetch(`${API}/${id}/favorite`, { method: 'PATCH' });
    const updated = await res.json() as Job;
    setJobs(prev => prev.map(j => j.id === id ? { ...j, favorited: updated.favorited } : j));
  };

  const updateNotes = async (id: number, notes: string) => {
    await fetch(`${API}/${id}/notes`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notes }),
    });
    setJobs(prev => prev.map(j => j.id === id ? { ...j, notes: notes.trim() || null } : j));
  };

  return { jobs, stats, states, loading, fetching, error, markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch, toggleFavorite, updateNotes, reload: loadJobs };
}

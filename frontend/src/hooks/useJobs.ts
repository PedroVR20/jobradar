import { useState, useEffect, useCallback, useRef } from 'react';
import { Job, JobStatus, ManualJobPayload, RejectedReason, Stats, Filters } from '../types/Job';

const API = '/api/jobs';
const PAGE_SIZE = 30;

// Fase 6.3 — formato paginado que GET /api/jobs devolve agora (antes era
// o catálogo inteiro filtrado, List<Job> puro). Ver JobPageResult no backend.
interface JobPage {
  content: Job[];
  totalElements: number;
  page: number;
  size: number;
  totalPages: number;
}

// Extraído de setStatus pra ficar num lugar só o mapeamento status→campos
// booleanos, reaproveitado pelas ações otimistas abaixo. Módulo-level (não
// dentro do hook) porque é pura — não depende de nenhum estado do
// componente, então não precisa entrar em nenhuma lista de deps de useCallback.
function patchParaStatus(j: Job, status: JobStatus, motivo?: RejectedReason): Partial<Job> {
  return {
    NOVA:        { seen: false, interested: false, applied: false,   inProgress: false, rejected: false, rejectedAt: null, rejectedReason: null },
    VISTA:       { seen: true,  interested: false, applied: false,   inProgress: false, rejected: false, rejectedAt: null, rejectedReason: null },
    INTERESSADO: { seen: true,  interested: true,  applied: false,   inProgress: false, rejected: false, rejectedAt: null, rejectedReason: null },
    APLICADA:    { seen: true,  interested: false, applied: true,    inProgress: false, rejected: false, rejectedAt: null, rejectedReason: null },
    ANDAMENTO:   { seen: true,  interested: false, applied: true,    inProgress: true,  rejected: false, rejectedAt: null, rejectedReason: null },
    RECUSADA:    { seen: true,  interested: false, applied: j.applied, inProgress: false, rejected: true, rejectedAt: new Date().toISOString(), rejectedReason: motivo ?? null },
  }[status];
}

export function useJobs(filters: Filters) {
  // jobs acumula as páginas já carregadas (não é só a página atual) — as
  // atualizações otimistas (marcar vista/aplicada/etc, mais abaixo) mexem
  // nessa lista inteira, então precisa conter tudo que já foi mostrado na
  // tela, não só os 30 itens mais recentes.
  const [jobs, setJobs] = useState<Job[]>([]);
  const [totalElements, setTotalElements] = useState(0);
  const paginaAtual = useRef(0);
  const [stats, setStats] = useState<Stats | null>(null);
  const [states, setStates] = useState<string[]>([]);
  const [sources, setSources] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);
  const [loadingMore, setLoadingMore] = useState(false);
  const [fetching, setFetching] = useState(false);
  const [error, setError] = useState<string | null>(null);
  // Fase 16.7 — antes, `loading` virava true em TODO refetch de página 0
  // (troca de aba, cada tecla digitada na busca depois do debounce, filtro
  // qualquer) — e App.tsx usava `loading` pra decidir entre grid real e
  // SkeletonGrid, então a lista inteira "piscava" pro esqueleto e voltava a
  // cada refetch, mesmo já tendo dado real na tela. `loading` agora só liga
  // no carregamento INICIAL de verdade (hasLoadedOnce ainda false);
  // refetches subsequentes ligam `refetching` — App.tsx usa isso pra um
  // indicador sutil (esmaecer o grid existente), não pra trocar tudo por
  // esqueleto.
  const hasLoadedOnce = useRef(false);
  const [refetching, setRefetching] = useState(false);

  // lista de estados/fontes só muda quando novas vagas chegam; carrega uma vez
  useEffect(() => {
    fetch(`${API}/states`).then(r => r.json()).then(setStates).catch(() => {});
    fetch(`${API}/sources`).then(r => r.json()).then(setSources).catch(() => {});
  }, []);

  // Fase 6.3 — techStack virou filtro de SERVIDOR (era só cliente antes):
  // com paginação de verdade, filtrar só depois de já ter recebido a
  // página não funciona (a página 1 pode vir vazia mesmo tendo resultado
  // no catálogo inteiro). page/undefined = usa o valor passado por quem
  // chama (0 pra recarregar do zero, N pro "carregar mais").
  const buildQuery = useCallback((page: number) => {
    const params = new URLSearchParams();
    if (filters.source) params.set('source', filters.source);
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
    if (filters.viewMode === 'interessado') params.set('onlyInteressado', 'true');
    if (filters.viewMode === 'aplicadas') params.set('onlyApplied', 'true');
    if (filters.viewMode === 'andamento') params.set('onlyInProgress', 'true');
    if (filters.viewMode === 'recusadas') params.set('onlyRejected', 'true');
    if (filters.viewMode === 'vencidas') params.set('onlyExpired', 'true');
    if (filters.viewMode === 'arquivadas') params.set('onlyArchived', 'true');
    filters.techStack.forEach(p => params.append('techStack', p));
    params.set('page', String(page));
    params.set('size', String(PAGE_SIZE));
    return params.toString();
  }, [filters]);

  // page=0 (padrão) recarrega do zero — filtro mudou, é uma busca nova.
  // page>0 é "carregar mais": acumula em cima do que já tinha, não substitui.
  const loadJobs = useCallback(async (silent = false, page = 0) => {
    const carregandoMais = page > 0;
    const primeiraVezDeVerdade = !hasLoadedOnce.current;
    if (!silent && !carregandoMais) {
      // Fase 16.7 — só o carregamento INICIAL usa o loading "bloqueante"
      // (SkeletonGrid substitui tudo, faz sentido não ter nada na tela
      // ainda). Refetches depois disso (filtro mudou, aba trocou) usam
      // `refetching` — grid existente continua visível, só esmaecido.
      if (primeiraVezDeVerdade) setLoading(true);
      else setRefetching(true);
    }
    if (carregandoMais) setLoadingMore(true);
    setError(null);
    try {
      const query = buildQuery(page);
      const [jobsRes, statsRes] = await Promise.all([
        fetch(`${API}?${query}`),
        // stats já reflete o estado real independente da página — não
        // precisa buscar de novo só porque "carregar mais" foi clicado.
        carregandoMais ? Promise.resolve(null) : fetch(`${API}/stats`)
      ]);
      const jobPage = await jobsRes.json() as JobPage;
      paginaAtual.current = page;
      setTotalElements(jobPage.totalElements);
      setJobs(prev => carregandoMais ? [...prev, ...jobPage.content] : jobPage.content);
      if (statsRes) setStats(await statsRes.json());
    } catch {
      setError('Erro ao carregar vagas. Verifique se o backend está rodando.');
    } finally {
      if (!silent && !carregandoMais) {
        hasLoadedOnce.current = true;
        setLoading(false);
        setRefetching(false);
      }
      if (carregandoMais) setLoadingMore(false);
    }
  }, [buildQuery]);

  const loadMore = useCallback(() => {
    loadJobs(true, paginaAtual.current + 1);
  }, [loadJobs]);

  // Fase 6.3 — as ações otimistas abaixo (marcar vista/aplicada/etc)
  // precisam refletir mudança de aba + stats atualizados sem descartar
  // páginas extras que o usuário já tinha carregado via "carregar mais".
  // Recarrega TODAS as páginas já vistas até agora (não só a primeira) e
  // concatena — mais requisições que um reload de página única, mas ainda
  // muito mais barato que a lista inteira sem filtro (o problema original
  // da Fase 6.3), e só acontece em resposta a uma ação do usuário, não em
  // toda tecla digitada.
  const reloadPaginasCarregadas = useCallback(async () => {
    try {
      const totalPaginas = paginaAtual.current + 1;
      const [paginas, statsRes] = await Promise.all([
        Promise.all(Array.from({ length: totalPaginas }, (_, p) =>
          fetch(`${API}?${buildQuery(p)}`).then(r => r.json() as Promise<JobPage>))),
        fetch(`${API}/stats`)
      ]);
      setJobs(paginas.flatMap(p => p.content));
      setTotalElements(paginas[paginas.length - 1]?.totalElements ?? 0);
      setStats(await statsRes.json());
    } catch {
      // silencioso de propósito — é um refresh em segundo plano depois de
      // uma ação que já teve sucesso (o PATCH já foi confirmado antes);
      // o estado otimista local continua correto mesmo se isso falhar.
    }
  }, [buildQuery]);

  // debounce: evita uma requisição a cada tecla digitada na busca — sempre
  // reseta pra página 0 (filtro novo, não é "carregar mais").
  useEffect(() => {
    const t = setTimeout(() => loadJobs(false, 0), 250);
    return () => clearTimeout(t);
  }, [loadJobs]);

  // Fase 13.2 — todas as ações abaixo (markSeen/setStatus/togglePin/etc)
  // viram prop de callback no JobCard (ver React.memo em JobCard.tsx). Sem
  // useCallback aqui, cada render do App.tsx recriava a função inteira e o
  // memo do card nunca batia — precisa vir estável de dependências reais.
  const markSeen = useCallback(async (id: number) => {
    await fetch(`${API}/${id}/seen`, { method: 'PATCH' });
    setJobs(prev => prev.map(j => j.id === id ? { ...j, seen: true } : j));
    reloadPaginasCarregadas(); // reflete a mudança de aba (novas → já vistas) e atualiza stats, sem piscar loading
  }, [reloadPaginasCarregadas]);

  const markApplied = useCallback(async (id: number) => {
    await fetch(`${API}/${id}/applied`, { method: 'PATCH' });
    setJobs(prev => prev.map(j =>
      j.id === id ? { ...j, applied: true, seen: true, interested: false, inProgress: false } : j
    ));
    reloadPaginasCarregadas(); // atualiza stats sem piscar loading
  }, [reloadPaginasCarregadas]);

  const markInProgress = useCallback(async (id: number) => {
    await fetch(`${API}/${id}/in-progress`, { method: 'PATCH' });
    setJobs(prev => prev.map(j =>
      j.id === id ? { ...j, applied: true, seen: true, interested: false, inProgress: true } : j
    ));
    reloadPaginasCarregadas(); // atualiza stats sem piscar loading
  }, [reloadPaginasCarregadas]);

  // Move a vaga direto pra um status, independente do atual — usado pelo
  // menu "⋮" do card (alternativa ao drag-and-drop pra pular entre abas).
  // motivo (Fase 8.7) só é relevante com status='RECUSADA'.
  const setStatus = useCallback(async (id: number, status: JobStatus, motivo?: RejectedReason) => {
    const query = motivo ? `value=${status}&motivo=${motivo}` : `value=${status}`;
    await fetch(`${API}/${id}/status?${query}`, { method: 'PATCH' });
    // RECUSADA não força applied:true — antes forçava, contando como
    // "aplicada" toda vaga recusada direto (ex: descartar vaga antiga nunca
    // aplicada), inflando as métricas. Mantém o applied que a vaga já tinha.
    setJobs(prev => prev.map(j => j.id === id ? { ...j, ...patchParaStatus(j, status, motivo) } : j));
    reloadPaginasCarregadas(); // reflete a mudança de aba e atualiza stats sem piscar loading
  }, [reloadPaginasCarregadas]);

  // Adiciona uma vaga manualmente (achada fora das fontes automáticas, tipo
  // Glassdoor/LinkedIn). Retorna a vaga criada/atualizada, ou null em erro.
  const addManualJob = useCallback(async (payload: ManualJobPayload): Promise<Job | null> => {
    const res = await fetch(`${API}/manual`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload),
    });
    if (!res.ok) return null;
    const job = await res.json() as Job;
    await loadJobs();
    // fonte digitada pode ser nova (ex: "InfoJobs") — atualiza a lista pro filtro já oferecer na hora
    setSources(prev => prev.includes(job.source) ? prev : [...prev, job.source].sort());
    return job;
  }, [loadJobs]);

  // Busca manual roda as 7 fontes de forma síncrona no backend (pode passar
  // de 1 minuto, o Nerdin sozinho já levou mais de 1min30 em teste) — se o
  // proxy nginx cortar antes disso (504), a resposta vem como HTML de erro,
  // não JSON. Sem checar res.ok antes de res.json(), isso vira uma exceção
  // não tratada ("Unexpected token '<'") sem nenhum aviso pro usuário.
  const triggerFetch = useCallback(async () => {
    setFetching(true);
    try {
      const res = await fetch(`${API}/fetch`, { method: 'POST' });
      if (!res.ok) {
        throw new Error(
          res.status === 504
            ? 'A busca demorou demais e o servidor cortou a conexão antes de terminar — mas ela pode ter concluído em segundo plano, tente recarregar a página em instantes.'
            : `Erro ao buscar vagas (status ${res.status}). Tente de novo.`
        );
      }
      const data = await res.json();
      // já tinha um fetch em andamento (o automático ao subir o app, ou o
      // agendado de 2h) — não rodou de novo em paralelo à toa, ver JobAggregatorService
      if (data.status === 'already-running') {
        throw new Error('Já tem uma busca em andamento (automática) — aguarde ela terminar e tente de novo.');
      }
      await loadJobs();
      return data.novasVagas as number;
    } finally {
      setFetching(false);
    }
  }, [loadJobs]);

  const togglePin = useCallback(async (id: number) => {
    const res = await fetch(`${API}/${id}/pin`, { method: 'PATCH' });
    const updated = await res.json() as Job;
    // recarrega a lista para respeitar a nova ordenação (pinned-first vem do backend)
    setJobs(prev => prev.map(j => j.id === id ? { ...j, pinned: updated.pinned } : j));
    reloadPaginasCarregadas();
  }, [reloadPaginasCarregadas]);

  // Fase 7.3+8.4 — tira a vaga do "porão" arquivado e devolve pro funil
  // normal. Reversível de propósito (ver JobController.reativarVaga).
  const reativarVaga = useCallback(async (id: number) => {
    await fetch(`${API}/${id}/reativar`, { method: 'POST' });
    setJobs(prev => prev.filter(j => j.id !== id));
    reloadPaginasCarregadas();
  }, [reloadPaginasCarregadas]);

  const updateNotes = useCallback(async (id: number, notes: string) => {
    await fetch(`${API}/${id}/notes`, {
      method: 'PATCH',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ notes }),
    });
    setJobs(prev => prev.map(j => j.id === id ? { ...j, notes: notes.trim() || null } : j));
  }, []);

  const hasMore = jobs.length < totalElements;

  return {
    jobs, stats, states, sources, loading, refetching, fetching, error,
    // Fase 6.3 — paginação real: totalElements/hasMore/loadMore/loadingMore
    // são novos, pro frontend saber quanto falta e pedir a próxima página
    // em vez de já ter tudo carregado e só "revelar" mais linhas de uma
    // lista que já estava inteira em memória.
    totalElements, hasMore, loadMore, loadingMore,
    markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch, togglePin, updateNotes, reativarVaga,
    // reload = recarrega as páginas já vistas (preserva "carregar mais"
    // anteriores) — usado depois de ações externas que mexem em várias
    // vagas de uma vez (Hunter, Triagem rápida).
    reload: reloadPaginasCarregadas,
  };
}

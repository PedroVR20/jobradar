import { useState, useEffect, useRef, useCallback } from 'react';
import { useJobs } from './hooks/useJobs';
import { AgendaTaskStatus, useAgenda } from './hooks/useAgenda';
import { useAiStatus } from './hooks/useAiStatus';
import { useTheme } from './hooks/useTheme';
import { StatsBar } from './components/StatsBar';
import { FilterBar } from './components/FilterBar';
import { ViewTabs } from './components/ViewTabs';
import { JobCard } from './components/JobCard';
import { EmptyState } from './components/EmptyState';
import { SkeletonGrid } from './components/SkeletonCard';
import { AddJobModal } from './components/AddJobModal';
import { AgendaStatusBar } from './components/AgendaStatusBar';
import { MetricsModal } from './components/MetricsModal';
import { SettingsModal } from './components/SettingsModal';
import { JarvisPanel } from './components/JarvisPanel';
import { TriageModal } from './components/TriageModal';
import { DuplicatesModal } from './components/DuplicatesModal';
import { HunterIcon } from './components/HunterIcon';
import { useCandidateProfile } from './hooks/useCandidateProfile';
import { useQuickMatchScores } from './hooks/useQuickMatchScores';
import { Filters, JobStatus, ManualJobPayload, RejectedReason, statusMeta, ViewMode } from './types/Job';
import './App.css';

const FOLLOWUP_DAYS = 7;
const NOTIFY_BEFORE_MINUTES = 24 * 60;

function followUpDueAt(): string {
  const d = new Date();
  d.setDate(d.getDate() + FOLLOWUP_DAYS);
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T23:59:00-03:00`;
}

const agendaStatusFor: Partial<Record<JobStatus, AgendaTaskStatus>> = {
  APLICADA: 'PENDING',
  ANDAMENTO: 'IN_PROGRESS',
  RECUSADA: 'DONE',
};

// Sentido inverso: status da tarefa na Agenda → status da vaga no Job Radar.
// DONE e NOT_DONE significam "processo encerrado" pro nosso funil, então os dois viram RECUSADA.
const jobStatusFor: Partial<Record<AgendaTaskStatus, JobStatus>> = {
  PENDING: 'APLICADA',
  IN_PROGRESS: 'ANDAMENTO',
  DONE: 'RECUSADA',
  NOT_DONE: 'RECUSADA',
};

const defaultFilters: Filters = {
  source: '',
  search: '',
  seniority: '',
  workplaceType: '',
  state: '',
  days: '',
  sort: 'posted_desc',
  viewMode: 'novas',
  beginnerMode: false,
  techStack: [],
};

const LAST_VISIT_KEY = 'jobradar:last-visit';
// Fase 4.5 — modo compacto do grid de vagas, mesmo padrão de persistência
// do modo compacto do Hunter (localStorage, lido uma vez no mount).
const COMPACT_CARDS_KEY = 'jobradar:compact-cards';

export default function App() {
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [toast, setToast] = useState<string | null>(null);
  const [showAddModal, setShowAddModal] = useState(false);
  const [showMetrics, setShowMetrics] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showJarvis, setShowJarvis] = useState(false);
  const [showTriage, setShowTriage] = useState(false);
  // Fase 7.4 — DuplicatesModal existia (GET /api/jobs/duplicates já varre o
  // catálogo inteiro não-recusado, sempre foi "retroativo") mas nunca tinha
  // sido importado/renderizado em lugar nenhum do app — botão morto, tela
  // inacessível. Faltava só isso: um jeito de abrir.
  const [showDuplicates, setShowDuplicates] = useState(false);
  // Fase 8.3 — navegação por teclado no grid principal (a Triagem rápida,
  // Lote 9, já tinha atalho de teclado pro fluxo "uma vaga por vez"; isso
  // aqui é o mesmo princípio pro grid normal, onde o padrão até agora era
  // mouse/clique em tudo). Mover vaga entre abas em lote usa arrastar e
  // soltar (já existia pras abas Aplicadas/Andamento/Recusadas — Fase 8.2
  // estendeu pras demais em vez de introduzir seleção por checkbox).
  const [focusedIndex, setFocusedIndex] = useState(-1);
  const [compactCards, setCompactCards] = useState(() => {
    try { return localStorage.getItem(COMPACT_CARDS_KEY) === '1'; } catch { return false; }
  });
  const toggleCompactCards = () => {
    setCompactCards(c => {
      const next = !c;
      try { localStorage.setItem(COMPACT_CARDS_KEY, next ? '1' : '0'); } catch { /* ignore */ }
      return next;
    });
  };
  // Pulso temporário no card real da vaga que o Hunter acabou de mudar
  // (marcarStatusDeVaga/atualizarNotaDeVaga) — ver job-card--highlighted no
  // App.css. Desliga sozinho depois de alguns segundos.
  const [highlightedJobId, setHighlightedJobId] = useState<number | null>(null);

  const { jobs, stats, states, sources, loading, fetching, error, totalElements, hasMore, loadMore, loadingMore, markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch, togglePin, updateNotes, reativarVaga, reload } =
    useJobs(filters);
  const { isConnected, createTask, linkTask, getLinkedTask, syncTaskStatus, getTaskStatus } = useAgenda();
  const aiStatus = useAiStatus();
  const { theme, toggle: toggleTheme } = useTheme();
  const { profile: candidateProfile } = useCandidateProfile();
  // Badge "🎯 X% match" nos cards de vagas NOVAS — só busca quando há perfil
  // salvo e a aba atual é a de novas (não faz sentido gastar a chamada pras
  // outras abas, que já foram vistas/decididas).
  const { scores: matchScores, refresh: refreshMatchScores } = useQuickMatchScores();
  useEffect(() => {
    if (candidateProfile.trim() && filters.viewMode === 'novas') refreshMatchScores(candidateProfile);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [candidateProfile, filters.viewMode]);
  const [syncingAgenda, setSyncingAgenda] = useState(false);
  const reconciledRef = useRef(false);

  const showToast = (msg: string, durationMs = 3000) => {
    setToast(msg);
    setTimeout(() => setToast(null), durationMs);
  };

  // Avisa quantas vagas novas chegaram desde a última vez que o app foi aberto
  // (não uma janela fixa tipo "últimas 4h" — se você ficar 2 dias sem abrir,
  // mostra tudo que chegou nesses 2 dias). Manda só os minutos decorridos pro
  // backend calcular "agora - X" com o próprio relógio dele, evitando qualquer
  // descompasso de fuso entre o navegador e o servidor.
  useEffect(() => {
    const lastVisit = localStorage.getItem(LAST_VISIT_KEY);
    const now = Date.now();
    if (lastVisit) {
      const minutesAgo = Math.floor((now - Number(lastVisit)) / 60000);
      if (minutesAgo > 0) {
        fetch(`/api/jobs/new-since?minutesAgo=${minutesAgo}`)
          .then(r => r.json())
          .then(data => {
            const count = data.count as number;
            if (count > 0) {
              showToast(`🔔 ${count} vaga${count === 1 ? '' : 's'} nova${count === 1 ? '' : 's'} desde sua última visita!`, 6000);
            }
          })
          .catch(() => {});
      }
    }
    localStorage.setItem(LAST_VISIT_KEY, String(now));
  }, []);

  const syncAgendaForStatus = (id: number, status: JobStatus) => {
    const agendaStatus = agendaStatusFor[status];
    if (agendaStatus) syncTaskStatus(id, agendaStatus);
  };

  // Sentido inverso: relê o status de cada tarefa vinculada na Agenda e reflete
  // no Job Radar quando o usuário mexeu no Kanban por lá em vez de por aqui.
  // Usa setStatus (não syncAgendaForStatus) pra não devolver o PATCH pra Agenda à toa.
  const reconcileWithAgenda = useCallback(async (): Promise<number> => {
    if (!isConnected()) return 0;
    const candidates = jobs.filter(j => (j.applied || j.inProgress) && !j.rejected);
    let changed = 0;
    await Promise.all(candidates.map(async job => {
      const linked = getLinkedTask(job.id);
      if (!linked) return;
      const agendaStatus = await getTaskStatus(linked.id);
      if (!agendaStatus) return;
      const mapped = jobStatusFor[agendaStatus];
      const current: JobStatus = job.inProgress ? 'ANDAMENTO' : 'APLICADA';
      if (mapped && mapped !== current) {
        await setStatus(job.id, mapped);
        changed++;
      }
    }));
    return changed;
  }, [jobs, isConnected, getLinkedTask, getTaskStatus, setStatus]);

  const handleAgendaSync = async () => {
    setSyncingAgenda(true);
    const changed = await reconcileWithAgenda();
    setSyncingAgenda(false);
    showToast(changed > 0 ? `🔄 ${changed} vaga(s) sincronizada(s) com a Agenda` : '✅ Tudo sincronizado com a Agenda');
  };

  // roda a reconciliação automaticamente uma vez, assim que as vagas carregam
  useEffect(() => {
    if (reconciledRef.current || jobs.length === 0 || !isConnected()) return;
    reconciledRef.current = true;
    reconcileWithAgenda().then(changed => {
      if (changed > 0) showToast(`🔄 ${changed} vaga(s) sincronizada(s) com a Agenda`);
    });
  }, [jobs, isConnected, reconcileWithAgenda]);

  // Ctrl+K (ou Cmd+K no mac) abre/fecha o Hunter de qualquer lugar da tela —
  // atalho padrão de "abrir busca/assistente" que a maioria dos apps usa.
  // preventDefault pra não deixar o navegador abrir a barra de endereço.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setShowJarvis(o => !o);
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, []);

  const handleFetch = async () => {
    try {
      const novas = await triggerFetch();
      showToast(novas > 0 ? `🎯 ${novas} novas vagas encontradas!` : '✅ Nenhuma vaga nova no momento');
    } catch (e) {
      showToast(`⚠️ ${e instanceof Error ? e.message : 'Erro ao buscar vagas.'}`, 8000);
    }
  };

  const handleApplied = async (id: number) => {
    await markApplied(id);
    syncAgendaForStatus(id, 'APLICADA');
    showToast('✅ Vaga marcada como aplicada!');
  };

  const handleInProgress = async (id: number) => {
    await markInProgress(id);
    if (isConnected()) {
      const job = jobs.find(j => j.id === id);
      if (job) {
        const dueAt = followUpDueAt();
        const result = await createTask({
          title: `Follow up: ${job.company} — ${job.title}`,
          description: `🔗 ${job.url}`,
          dueAt,
          priority: 'HIGH',
          icon: 'notifications',
          notifyBeforeMinutes: NOTIFY_BEFORE_MINUTES,
        });
        if (result !== 'unauthorized' && result !== 'error') {
          linkTask(id, result.id, dueAt);
          showToast('🔄 Em Andamento — 📅 follow up criado na Agenda!');
          return;
        }
      }
    }
    syncAgendaForStatus(id, 'ANDAMENTO');
    showToast('🔄 Vaga movida pra "Em Andamento"!');
  };

  // Fase 8.2 — estendido pra todas as abas de status (antes só
  // aplicadas/andamento/recusadas aceitavam soltar) em vez de introduzir
  // seleção em lote por checkbox: arrastar e soltar já existia, só faltava
  // funcionar em toda aba pra cobrir o mesmo caso de uso.
  const handleDropJob = async (jobId: number, tab: ViewMode) => {
    if (tab === 'andamento') await handleInProgress(jobId);
    else if (tab === 'aplicadas') await handleApplied(jobId);
    else if (tab === 'interessado') await setStatus(jobId, 'INTERESSADO');
    else if (tab === 'novas') await setStatus(jobId, 'NOVA');
    else if (tab === 'vistas') await setStatus(jobId, 'VISTA');
    else if (tab === 'recusadas') {
      await setStatus(jobId, 'RECUSADA');
      syncAgendaForStatus(jobId, 'RECUSADA');
    }
  };

  const handleSetStatus = async (id: number, status: JobStatus, motivo?: RejectedReason) => {
    await setStatus(id, status, motivo);
    syncAgendaForStatus(id, status);
    showToast(`Vaga movida pra "${statusMeta[status]}"!`);
  };

  const handleAddManual = async (payload: ManualJobPayload) => {
    const job = await addManualJob(payload);
    if (job) {
      setShowAddModal(false);
      showToast(`✅ "${job.title}" adicionada!`);
    }
    return !!job;
  };

  // Fase 8.3 — teclado como caminho principal no grid principal (a Triagem
  // rápida, Lote 9, já tinha isso pro fluxo "uma vaga por vez"; até aqui o
  // grid normal exigia mouse/clique pra tudo). j/k ou ↓/↑ move o foco entre
  // cards; x seleciona (alimenta a barra de ações em lote, Fase 8.2); y/n/v
  // agem direto na vaga focada; Enter abre a vaga. Desativado com QUALQUER
  // modal aberto (o próprio modal tem seus atalhos) ou digitando num campo.
  const anyModalOpen = showAddModal || showMetrics || showSettings || showJarvis || showTriage || showDuplicates;
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (anyModalOpen) return;
      const alvo = e.target as HTMLElement | null;
      if (alvo && ['INPUT', 'TEXTAREA', 'SELECT'].includes(alvo.tagName)) return;
      if (alvo?.isContentEditable) return;
      if (jobs.length === 0) return;

      if (e.key === 'j' || e.key === 'ArrowDown') {
        e.preventDefault();
        setFocusedIndex(i => Math.min(jobs.length - 1, i + 1));
      } else if (e.key === 'k' || e.key === 'ArrowUp') {
        e.preventDefault();
        setFocusedIndex(i => Math.max(0, i - 1));
      } else if (focusedIndex >= 0 && focusedIndex < jobs.length) {
        const job = jobs[focusedIndex];
        if (e.key === 'y') {
          handleSetStatus(job.id, 'INTERESSADO');
        } else if (e.key === 'n') {
          handleSetStatus(job.id, 'RECUSADA');
        } else if (e.key === 'v') {
          markSeen(job.id);
        } else if (e.key === 'Enter') {
          window.open(job.url, '_blank', 'noopener,noreferrer');
        } else if (e.key === 'Escape') {
          setFocusedIndex(-1);
        }
      }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [anyModalOpen, jobs, focusedIndex, handleSetStatus, markSeen]);

  useEffect(() => {
    if (focusedIndex < 0 || focusedIndex >= jobs.length) return;
    document.getElementById(`job-card-${jobs[focusedIndex].id}`)?.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  }, [focusedIndex, jobs]);

  // reseta foco quando o filtro muda — índice antigo não corresponde mais
  // a nada depois de trocar de aba/busca.
  useEffect(() => {
    setFocusedIndex(-1);
  }, [filters.viewMode, filters.source, filters.search, filters.seniority, filters.state]);

  return (
    <div className={`app ${showJarvis ? 'app--jarvis-open' : ''}`}>
      {/* Header */}
      <header className="app-header">
        <div className="header-inner header-inner--flex">
          <div>
            <h1 className="app-title">🎯 Job Radar</h1>
            <p className="app-subtitle">Vagas de programação remotas na Europa + vagas no Brasil (Gupy) · Atualizado a cada 2 horas</p>
          </div>
          <div className="header-actions">
            <button
              className="btn btn-ghost theme-toggle-btn"
              onClick={toggleTheme}
              title={theme === 'dark' ? 'Tema claro' : 'Tema escuro'}
              aria-label={theme === 'dark' ? 'Mudar para tema claro' : 'Mudar para tema escuro'}
            >
              {theme === 'dark' ? '☀️' : '🌙'}
            </button>
            <AgendaStatusBar syncing={syncingAgenda} onSync={handleAgendaSync} />
            <button className="btn btn-ghost" onClick={() => setShowMetrics(true)}>
              📊 Métricas
            </button>
            <button className="btn btn-ghost" onClick={() => setShowSettings(true)}>
              ⚙️ Configurações
            </button>
            <button className="btn btn-ghost" onClick={() => setShowTriage(true)} title="Revisa as vagas novas uma por uma, rapidinho">
              ⚡ Triagem rápida
              {/* Fase 8.6 — progresso visível ANTES de abrir, não só dentro do
                  modal: o tamanho do backlog é informação relevante pra
                  decidir se vale abrir agora. */}
              {!!stats?.novas && <span className="view-tab-count">{stats.novas}</span>}
            </button>
            <button className="btn btn-ghost" onClick={() => setShowDuplicates(true)} title="Vagas da mesma empresa com título parecido, publicadas em fontes diferentes">
              🧩 Duplicatas
            </button>
            {aiStatus.enabled && (
              <button className="btn jarvis-toggle-btn" onClick={() => setShowJarvis(o => !o)} title="Abrir o Hunter (Ctrl+K)">
                <HunterIcon size={17} alive /> Hunter
              </button>
            )}
            <button className="btn btn-primary add-job-btn" onClick={() => setShowAddModal(true)}>
              ➕ Adicionar vaga
            </button>
          </div>
        </div>
      </header>

      {showJarvis && (
        <JarvisPanel
          onClose={() => setShowJarvis(false)}
          onJobsChanged={vagaId => {
            reload();
            if (vagaId != null) {
              setHighlightedJobId(vagaId);
              window.setTimeout(() => setHighlightedJobId(null), 3600);
              // Rola até o card se ele já estiver na lista visível — só
              // depois do reload terminar de atualizar o DOM.
              window.setTimeout(() => {
                document.getElementById(`job-card-${vagaId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' });
              }, 200);
            }
          }}
        />
      )}

      {showTriage && (
        <TriageModal
          onClose={() => { setShowTriage(false); reload(); }}
          onSeen={markSeen}
          onSetStatus={handleSetStatus}
        />
      )}

      {showDuplicates && (
        <DuplicatesModal
          onClose={() => { setShowDuplicates(false); reload(); }}
          onReject={id => handleSetStatus(id, 'RECUSADA')}
        />
      )}

      {showAddModal && (
        <AddJobModal onClose={() => setShowAddModal(false)} onSubmit={handleAddManual} />
      )}

      {showMetrics && (
        <MetricsModal onClose={() => setShowMetrics(false)} />
      )}

      {showSettings && (
        <SettingsModal
          aiStatus={{ enabled: aiStatus.enabled, model: aiStatus.model, requestsToday: aiStatus.requestsToday, keyPool: aiStatus.keyPool }}
          aiLoading={aiStatus.loading}
          onRefreshAiStatus={aiStatus.refresh}
          onClose={() => setShowSettings(false)}
        />
      )}

      <main className="app-main">
        {/* Stats */}
        {stats && (
          <StatsBar
            stats={stats}
            onFetch={handleFetch}
            fetching={fetching}
            activeSeniority={filters.seniority}
            onSeniorityClick={seniority => setFilters({ ...filters, seniority })}
          />
        )}

        {/* Tabs de visualização */}
        <ViewTabs
          viewMode={filters.viewMode}
          onChange={viewMode => setFilters({ ...filters, viewMode })}
          stats={stats}
          onDropJob={handleDropJob}
        />

        {/* Filters */}
        <FilterBar
          filters={filters}
          onChange={setFilters}
          onClear={() => setFilters(defaultFilters)}
          total={jobs.length}
          states={states}
          sources={sources}
          compact={compactCards}
          onToggleCompact={toggleCompactCards}
        />

        {/* Content */}
        {error && (
          <div className="error-box">
            ⚠️ {error}
          </div>
        )}

        {loading ? (
          <SkeletonGrid />
        ) : jobs.length === 0 ? (
          <EmptyState
            filters={filters}
            onClearFilters={() => setFilters(defaultFilters)}
            onClearDays={() => setFilters({ ...filters, days: '' })}
            onFetchNow={handleFetch}
            fetching={fetching}
          />
        ) : (
          <>
            <div className={`jobs-grid ${compactCards ? 'jobs-grid--compact' : ''}`}>
              {jobs.map((job, idx) => (
                <JobCard
                  key={job.id}
                  job={job}
                  onSeen={markSeen}
                  onApplied={handleApplied}
                  onInProgress={handleInProgress}
                  onSetStatus={handleSetStatus}
                  onTogglePin={togglePin}
                  onUpdateNotes={updateNotes}
                  onReativar={filters.viewMode === 'arquivadas' ? reativarVaga : undefined}
                  onToast={showToast}
                  aiEnabled={aiStatus.enabled}
                  sortMode={filters.sort}
                  highlighted={job.id === highlightedJobId}
                  matchPercent={filters.viewMode === 'novas' ? matchScores[String(job.id)] : undefined}
                  compact={compactCards}
                  keyboardFocused={idx === focusedIndex}
                />
              ))}
            </div>
            {hasMore && (
              <button
                className="load-more-btn"
                onClick={loadMore}
                disabled={loadingMore}
              >
                {loadingMore ? 'Carregando...' : `Carregar mais vagas (${totalElements - jobs.length} restantes)`}
              </button>
            )}
          </>
        )}
      </main>

      {/* Toast */}
      {toast && <div className="toast">{toast}</div>}
    </div>
  );
}

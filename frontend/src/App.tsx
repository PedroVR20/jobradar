import { useState, useEffect, useRef, useCallback } from 'react';
import { useJobs } from './hooks/useJobs';
import { AgendaTaskStatus, useAgenda } from './hooks/useAgenda';
import { useAiStatus } from './hooks/useAiStatus';
import { StatsBar } from './components/StatsBar';
import { FilterBar } from './components/FilterBar';
import { ViewTabs } from './components/ViewTabs';
import { JobCard } from './components/JobCard';
import { AddJobModal } from './components/AddJobModal';
import { AgendaStatusBar } from './components/AgendaStatusBar';
import { MetricsModal } from './components/MetricsModal';
import { SettingsModal } from './components/SettingsModal';
import { DuplicatesModal } from './components/DuplicatesModal';
import { JarvisPanel } from './components/JarvisPanel';
import { RoviIcon } from './components/RoviIcon';
import { Filters, JobStatus, ManualJobPayload, statusMeta, ViewMode } from './types/Job';
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

const PAGE_SIZE = 30;
const LAST_VISIT_KEY = 'jobradar:last-visit';

export default function App() {
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [toast, setToast] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [showAddModal, setShowAddModal] = useState(false);
  const [showMetrics, setShowMetrics] = useState(false);
  const [showSettings, setShowSettings] = useState(false);
  const [showJarvis, setShowJarvis] = useState(false);
  const [showDuplicates, setShowDuplicates] = useState(false);

  const { jobs, stats, states, sources, loading, fetching, error, markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch, togglePin, updateNotes } =
    useJobs(filters);
  const { isConnected, createTask, linkTask, getLinkedTask, syncTaskStatus, getTaskStatus } = useAgenda();
  const aiStatus = useAiStatus();
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

  // volta pra primeira "página" sempre que os filtros mudam a lista
  useEffect(() => { setVisibleCount(PAGE_SIZE); }, [filters]);

  const visibleJobs = jobs.slice(0, visibleCount);
  const hasMore = visibleCount < jobs.length;

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

  const handleDropJob = async (jobId: number, tab: ViewMode) => {
    if (tab === 'andamento') await handleInProgress(jobId);
    else if (tab === 'aplicadas') await handleApplied(jobId);
    else if (tab === 'interessado') await setStatus(jobId, 'INTERESSADO');
    else if (tab === 'recusadas') {
      await setStatus(jobId, 'RECUSADA');
      syncAgendaForStatus(jobId, 'RECUSADA');
    }
  };

  const handleSetStatus = async (id: number, status: JobStatus) => {
    await setStatus(id, status);
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

  return (
    <div className={`app ${showJarvis ? 'app--jarvis-open' : ''}`}>
      {/* Header */}
      <header className="app-header">
        <div className="header-inner header-inner--flex">
          <div>
            <h1 className="app-title">🎯 Job Radar</h1>
            <p className="app-subtitle">Vagas de programação remotas na Europa + vagas no Brasil (Gupy) · Atualizado a cada 4 horas</p>
          </div>
          <div className="header-actions">
            <AgendaStatusBar syncing={syncingAgenda} onSync={handleAgendaSync} />
            <button className="btn btn-ghost" onClick={() => setShowMetrics(true)}>
              📊 Métricas
            </button>
            <button className="btn btn-ghost" onClick={() => setShowDuplicates(true)}>
              🧩 Duplicatas
            </button>
            <button className="btn btn-ghost" onClick={() => setShowSettings(true)}>
              ⚙️ Configurações
            </button>
            {aiStatus.enabled && (
              <button className="btn jarvis-toggle-btn" onClick={() => setShowJarvis(o => !o)}>
                <RoviIcon size={17} /> Rovi
              </button>
            )}
            <button className="btn btn-primary add-job-btn" onClick={() => setShowAddModal(true)}>
              ➕ Adicionar vaga
            </button>
          </div>
        </div>
      </header>

      {showJarvis && <JarvisPanel onClose={() => setShowJarvis(false)} />}

      {showAddModal && (
        <AddJobModal onClose={() => setShowAddModal(false)} onSubmit={handleAddManual} />
      )}

      {showMetrics && (
        <MetricsModal onClose={() => setShowMetrics(false)} />
      )}

      {showDuplicates && (
        <DuplicatesModal
          onClose={() => setShowDuplicates(false)}
          onReject={id => handleSetStatus(id, 'RECUSADA')}
        />
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
        />

        {/* Content */}
        {error && (
          <div className="error-box">
            ⚠️ {error}
          </div>
        )}

        {loading ? (
          <div className="loading-box">
            <div className="spinner" />
            <p>Carregando vagas...</p>
          </div>
        ) : jobs.length === 0 ? (
          <div className="empty-box">
            <p>😶 Nenhuma vaga encontrada com esses filtros.</p>
            <button className="btn btn-primary" onClick={() => setFilters(defaultFilters)}>
              Limpar filtros
            </button>
          </div>
        ) : (
          <>
            <div className="jobs-grid">
              {visibleJobs.map(job => (
                <JobCard
                  key={job.id}
                  job={job}
                  onSeen={markSeen}
                  onApplied={handleApplied}
                  onInProgress={handleInProgress}
                  onSetStatus={handleSetStatus}
                  onTogglePin={togglePin}
                  onUpdateNotes={updateNotes}
                  onToast={showToast}
                  aiEnabled={aiStatus.enabled}
                />
              ))}
            </div>
            {hasMore && (
              <button
                className="load-more-btn"
                onClick={() => setVisibleCount(c => c + PAGE_SIZE)}
              >
                Carregar mais vagas ({jobs.length - visibleCount} restantes)
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

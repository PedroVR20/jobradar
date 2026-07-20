import { useState, useEffect } from 'react';
import { useJobs } from './hooks/useJobs';
import { AgendaTaskStatus, useAgenda } from './hooks/useAgenda';
import { StatsBar } from './components/StatsBar';
import { FilterBar } from './components/FilterBar';
import { ViewTabs } from './components/ViewTabs';
import { JobCard } from './components/JobCard';
import { AddJobModal } from './components/AddJobModal';
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

export default function App() {
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [toast, setToast] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [showAddModal, setShowAddModal] = useState(false);

  const { jobs, stats, states, loading, fetching, error, markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch, togglePin, updateNotes } =
    useJobs(filters);
  const { isConnected, createTask, linkTask, syncTaskStatus } = useAgenda();

  const syncAgendaForStatus = (id: number, status: JobStatus) => {
    const agendaStatus = agendaStatusFor[status];
    if (agendaStatus) syncTaskStatus(id, agendaStatus);
  };

  // volta pra primeira "página" sempre que os filtros mudam a lista
  useEffect(() => { setVisibleCount(PAGE_SIZE); }, [filters]);

  const visibleJobs = jobs.slice(0, visibleCount);
  const hasMore = visibleCount < jobs.length;

  const showToast = (msg: string) => {
    setToast(msg);
    setTimeout(() => setToast(null), 3000);
  };

  const handleFetch = async () => {
    const novas = await triggerFetch();
    showToast(novas > 0 ? `🎯 ${novas} novas vagas encontradas!` : '✅ Nenhuma vaga nova no momento');
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
    <div className="app">
      {/* Header */}
      <header className="app-header">
        <div className="header-inner header-inner--flex">
          <div>
            <h1 className="app-title">🎯 Job Radar</h1>
            <p className="app-subtitle">Vagas de programação remotas na Europa + vagas no Brasil (Gupy) · Atualizado diariamente às 08:00</p>
          </div>
          <button className="btn btn-primary add-job-btn" onClick={() => setShowAddModal(true)}>
            ➕ Adicionar vaga
          </button>
        </div>
      </header>

      {showAddModal && (
        <AddJobModal onClose={() => setShowAddModal(false)} onSubmit={handleAddManual} />
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

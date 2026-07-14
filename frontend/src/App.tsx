import { useState, useEffect } from 'react';
import { useJobs } from './hooks/useJobs';
import { StatsBar } from './components/StatsBar';
import { FilterBar } from './components/FilterBar';
import { ViewTabs } from './components/ViewTabs';
import { JobCard } from './components/JobCard';
import { AddJobModal } from './components/AddJobModal';
import { Filters, JobStatus, ManualJobPayload, statusMeta, ViewMode } from './types/Job';
import './App.css';

const defaultFilters: Filters = {
  source: '',
  search: '',
  seniority: '',
  workplaceType: '',
  state: '',
  days: '',
  sort: 'posted_desc',
  viewMode: 'novas',
};

const PAGE_SIZE = 30;

export default function App() {
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  const [toast, setToast] = useState<string | null>(null);
  const [visibleCount, setVisibleCount] = useState(PAGE_SIZE);
  const [showAddModal, setShowAddModal] = useState(false);

  const { jobs, stats, states, loading, fetching, error, markSeen, markApplied, markInProgress, setStatus, addManualJob, triggerFetch } =
    useJobs(filters);

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
    showToast('✅ Vaga marcada como aplicada!');
  };

  const handleInProgress = async (id: number) => {
    await markInProgress(id);
    showToast('🔄 Vaga movida pra "Em Andamento"!');
  };

  const handleDropJob = async (jobId: number, tab: ViewMode) => {
    if (tab === 'andamento') await handleInProgress(jobId);
    else if (tab === 'aplicadas') await handleApplied(jobId);
  };

  const handleSetStatus = async (id: number, status: JobStatus) => {
    await setStatus(id, status);
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

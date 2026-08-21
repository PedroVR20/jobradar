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
import { SemanticSearchModal } from './components/SemanticSearchModal';
import { ShortcutsModal } from './components/ShortcutsModal';
import { HunterIcon } from './components/HunterIcon';
import { useCandidateProfile } from './hooks/useCandidateProfile';
import { useQuickMatchScores } from './hooks/useQuickMatchScores';
import { DIAS_ALERTA_FONTE_PARADA, Filters, JobStatus, ManualJobPayload, RejectedReason, statusMeta, ViewMode } from './types/Job';
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
// Fase 15.5 — evita repetir o mesmo aviso de fonte parada toda vez que o
// app é aberto/recarregado no mesmo dia (o sinal não muda de uma hora pra
// outra, só verifica de novo — mesma ideia do LAST_VISIT_KEY acima, guarda
// a DATA (não timestamp) do último aviso mostrado).
const LAST_SOURCE_HEALTH_ALERT_KEY = 'jobradar:last-source-health-alert-date';
// Fase 4.5 — modo compacto do grid de vagas, mesmo padrão de persistência
// do modo compacto do Hunter (localStorage, lido uma vez no mount).
const COMPACT_CARDS_KEY = 'jobradar:compact-cards';

export default function App() {
  const [filters, setFilters] = useState<Filters>(defaultFilters);
  // Fase 16.5 — era um toast só (string | null): showToast agendava um
  // setTimeout sem NUNCA cancelar o anterior. Bug real: um aviso de 6-8s
  // (ex: "🔔 N vagas novas") sobrevivia até um toast de confirmação de 3s
  // aparecer por cima — quando o timer do PRIMEIRO disparava, ele apagava
  // o SEGUNDO antes da hora, mesmo os dois sendo mensagens diferentes.
  // Quanto mais rápido a pessoa trabalhava, mais mensagem sumia no meio.
  // Fila de verdade: cada toast tem seu próprio id e seu próprio timer.
  const [toasts, setToasts] = useState<{ id: number; msg: string }[]>([]);
  const toastIdRef = useRef(0);
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
  // Fase 9.2 — busca por significado fora do chat do Hunter.
  const [showSemanticSearch, setShowSemanticSearch] = useState(false);
  // Fase 16.8 — folha de atalhos ("?"), pra descobrir o que já existia
  // (Fase 8.3 navegação, Ctrl+K Hunter, drag-and-drop) sem tropeçar por acaso.
  const [showShortcuts, setShowShortcuts] = useState(false);
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

  // Fase 13.2 — vira prop de callback em componentes com React.memo
  // (JobCard etc); useCallback com deps vazias porque só toca toastIdRef
  // (ref, estável) e setToasts (setState, sempre estável).
  const showToast = useCallback((msg: string, durationMs = 3000) => {
    const id = ++toastIdRef.current;
    setToasts(prev => [...prev, { id, msg }]);
    setTimeout(() => setToasts(prev => prev.filter(t => t.id !== id)), durationMs);
  }, []);

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

  // Fase 15.5 — alerta de degradação silenciosa: uma fonte de vaga que
  // quebrou (scraping mudou, API saiu do ar) nunca lança erro pro usuário
  // ver — o painel "Saúde das fontes" em Configurações (Fase 2.7) já
  // sinalizava isso, mas só pra quem abrisse Configurações por conta
  // própria. Isso aqui avisa de forma proativa, uma vez por dia, sem
  // precisar ir procurar. Mesmo sinal que o SourceFreshnessHealthIndicator
  // expõe em /actuator/health, pro lado de quem monitora o backend de fora.
  useEffect(() => {
    const hoje = new Date().toISOString().slice(0, 10);
    if (localStorage.getItem(LAST_SOURCE_HEALTH_ALERT_KEY) === hoje) return;

    fetch('/api/jobs/admin/fontes-saude')
      .then(r => r.ok ? r.json() : Promise.reject())
      .then((fontes: { fonte: string; diasSemVagaNova: number | null }[]) => {
        const paradas = fontes.filter(f => f.diasSemVagaNova !== null && f.diasSemVagaNova > DIAS_ALERTA_FONTE_PARADA);
        if (paradas.length === 0) return;
        const nomes = paradas.map(f => f.fonte).join(', ');
        showToast(
          `⚠️ ${paradas.length} fonte${paradas.length === 1 ? '' : 's'} sem vaga nova há mais de ${DIAS_ALERTA_FONTE_PARADA} dias: ${nomes} — veja em ⚙️ Configurações`,
          10000
        );
        localStorage.setItem(LAST_SOURCE_HEALTH_ALERT_KEY, hoje);
      })
      .catch(() => {}); // silencioso de propósito — é um aviso extra, não pode travar o carregamento do app
  }, [showToast]);

  // Fase 13.5 — antes disparava e esquecia (syncTaskStatus tinha catch
  // silencioso e devolvia void, quem chamava nunca sabia se funcionou).
  // O toast principal da ação (ex: "Vaga marcada como aplicada!") continua
  // disparando na hora, sem esperar isso — só avisa, à parte, se a
  // sincronia com a Agenda especificamente falhou, pra não deixar os dois
  // apps saírem de sincronia sem o usuário nunca descobrir.
  const syncAgendaForStatus = useCallback((id: number, status: JobStatus) => {
    const agendaStatus = agendaStatusFor[status];
    if (!agendaStatus) return;
    syncTaskStatus(id, agendaStatus).then(ok => {
      if (!ok) showToast('⚠️ Não consegui sincronizar o status com a Agenda Pessoal — a vaga foi atualizada aqui normalmente.', 6000);
    });
  }, [syncTaskStatus, showToast]);

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

  const handleAgendaSync = useCallback(async () => {
    setSyncingAgenda(true);
    const changed = await reconcileWithAgenda();
    setSyncingAgenda(false);
    showToast(changed > 0 ? `🔄 ${changed} vaga(s) sincronizada(s) com a Agenda` : '✅ Tudo sincronizado com a Agenda');
  }, [reconcileWithAgenda, showToast]);

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

  const handleFetch = useCallback(async () => {
    try {
      const novas = await triggerFetch();
      showToast(novas > 0 ? `🎯 ${novas} novas vagas encontradas!` : '✅ Nenhuma vaga nova no momento');
    } catch (e) {
      showToast(`⚠️ ${e instanceof Error ? e.message : 'Erro ao buscar vagas.'}`, 8000);
    }
  }, [triggerFetch, showToast]);

  const handleApplied = useCallback(async (id: number) => {
    await markApplied(id);
    syncAgendaForStatus(id, 'APLICADA');
    showToast('✅ Vaga marcada como aplicada!');
  }, [markApplied, syncAgendaForStatus, showToast]);

  const handleInProgress = useCallback(async (id: number) => {
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
  }, [markInProgress, isConnected, jobs, createTask, linkTask, syncAgendaForStatus, showToast]);

  // Fase 8.2 — estendido pra todas as abas de status (antes só
  // aplicadas/andamento/recusadas aceitavam soltar) em vez de introduzir
  // seleção em lote por checkbox: arrastar e soltar já existia, só faltava
  // funcionar em toda aba pra cobrir o mesmo caso de uso.
  const handleDropJob = useCallback(async (jobId: number, tab: ViewMode) => {
    if (tab === 'andamento') await handleInProgress(jobId);
    else if (tab === 'aplicadas') await handleApplied(jobId);
    else if (tab === 'interessado') await setStatus(jobId, 'INTERESSADO');
    else if (tab === 'novas') await setStatus(jobId, 'NOVA');
    else if (tab === 'vistas') await setStatus(jobId, 'VISTA');
    else if (tab === 'recusadas') {
      await setStatus(jobId, 'RECUSADA');
      syncAgendaForStatus(jobId, 'RECUSADA');
    }
  }, [handleInProgress, handleApplied, setStatus, syncAgendaForStatus]);

  const handleSetStatus = useCallback(async (id: number, status: JobStatus, motivo?: RejectedReason) => {
    await setStatus(id, status, motivo);
    syncAgendaForStatus(id, status);
    showToast(`Vaga movida pra "${statusMeta[status]}"!`);
  }, [setStatus, syncAgendaForStatus, showToast]);

  const handleAddManual = useCallback(async (payload: ManualJobPayload) => {
    const job = await addManualJob(payload);
    if (job) {
      setShowAddModal(false);
      showToast(`✅ "${job.title}" adicionada!`);
    }
    return !!job;
  }, [addManualJob, showToast]);

  // Fase 8.3 — teclado como caminho principal no grid principal (a Triagem
  // rápida, Lote 9, já tinha isso pro fluxo "uma vaga por vez"; até aqui o
  // grid normal exigia mouse/clique pra tudo). j/k ou ↓/↑ move o foco entre
  // cards; x seleciona (alimenta a barra de ações em lote, Fase 8.2); y/n/v
  // agem direto na vaga focada; Enter abre a vaga. Desativado com QUALQUER
  // modal aberto (o próprio modal tem seus atalhos) ou digitando num campo.
  const anyModalOpen = showAddModal || showMetrics || showSettings || showJarvis || showTriage || showDuplicates || showSemanticSearch || showShortcuts;
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const alvo = e.target as HTMLElement | null;
      if (alvo && ['INPUT', 'TEXTAREA', 'SELECT'].includes(alvo.tagName)) return;
      if (alvo?.isContentEditable) return;
      // Fase 16.8 — "?" precisa abrir a folha de atalhos mesmo sem NENHUM
      // modal aberto (senão vira um atalho pra descobrir atalho que só
      // funciona quando você já sabe que não tem nada aberto). Fecha com
      // Esc/clique fora como qualquer outro modal (useEscapeToClose nele).
      if (e.key === '?' && !anyModalOpen) {
        e.preventDefault();
        setShowShortcuts(true);
        return;
      }
      if (anyModalOpen) return;
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
            {/* Fase 16.10 — a versão antiga citava "Europa + Gupy" como se
                fossem as únicas fontes; hoje são 9 (Gupy, QueroVagasTech,
                Nerdin, Arbeitnow, Greenhouse, Eureca, WWR, Remotive,
                InfoJobs — ver stats.porFonte). Descreve a categoria em vez
                de listar fonte por fonte, pra não voltar a ficar
                desatualizado a cada fonte nova que entrar. */}
            <p className="app-subtitle">Vagas de programação no Brasil e remotas internacionais, de múltiplas fontes agregadas · Atualizado a cada 2 horas</p>
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
            <button className="btn btn-ghost" onClick={() => setShowSemanticSearch(true)} title="Busca por significado, não por texto exato — roda local, sem gastar cota de IA">
              🧠 Busca semântica
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
          aiEnabled={aiStatus.enabled}
        />
      )}

      {showDuplicates && (
        <DuplicatesModal
          onClose={() => { setShowDuplicates(false); reload(); }}
          onReject={id => handleSetStatus(id, 'RECUSADA')}
        />
      )}

      {showSemanticSearch && (
        <SemanticSearchModal
          onClose={() => { setShowSemanticSearch(false); reload(); }}
          onSeen={markSeen}
          onApplied={handleApplied}
          onInProgress={handleInProgress}
          onSetStatus={handleSetStatus}
          onTogglePin={togglePin}
          onUpdateNotes={updateNotes}
          onToast={showToast}
          aiEnabled={aiStatus.enabled}
          candidateProfile={candidateProfile}
        />
      )}

      {showShortcuts && (
        <ShortcutsModal onClose={() => setShowShortcuts(false)} />
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
            {/* Fase 13.1/13.3 — sem acesso a browser/DevTools Profiler nessa
                sessão, a decisão sobre virtualizar essa lista foi por
                julgamento de código, não medição real: paginação já limita
                cada carregamento a PAGE_SIZE=30 (useJobs.ts) — "carregar
                mais" acumula, mas o caso comum fica na casa de dezenas, não
                milhares — e React.memo (Fase 13.2) já corta o re-render de
                card não afetado, que era o custo dominante mais óbvio.
                Virtualização (react-window) traria uma dependência nova +
                refatoração de layout (grid CSS vira lista de altura
                variável) pra um ganho que, sem medição real, não dá pra
                afirmar que compensa aqui. O que DEU pra confirmar sem
                DevTools — imagem de logo carregando eager mesmo fora da
                viewport em toda vaga — foi corrigido (loading="lazy" em
                JobCard, nativo do browser, zero dependência nova). Se o
                catálogo/paginação crescer a ponto de "carregar mais" virar
                hábito de centenas de cards na tela, revisitar com medição
                real primeiro. */}
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
                  candidateProfile={candidateProfile}
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

      {/* Fase 16.5 — fila (cada toast some sozinho no seu próprio tempo,
          sem apagar os outros) + aria-live pra leitor de tela anunciar. */}
      <div className="toast-stack" aria-live="polite" aria-atomic="false">
        {toasts.map(t => (
          <div key={t.id} className="toast">{t.msg}</div>
        ))}
      </div>
    </div>
  );
}

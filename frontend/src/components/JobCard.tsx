import { DragEvent, useEffect, useRef, useState } from 'react';
import { DIAS_PARA_EXCLUIR_RECUSADAS, Job, JobStatus, SortOption, statusMeta, seniorityMeta, sourceMeta, workplaceMeta } from '../types/Job';
import { AgendaModal } from './AgendaModal';
import { InterviewModal } from './InterviewModal';
import { CoverLetterModal } from './CoverLetterModal';
import { SalaryEstimateModal } from './SalaryEstimateModal';
import { MatchScoreModal } from './MatchScoreModal';
import { InterviewQuestionsModal } from './InterviewQuestionsModal';
import { useAgenda } from '../hooks/useAgenda';
import { useSourceColors } from '../hooks/useSourceColors';
import { HunterIcon } from './HunterIcon';

interface Props {
  job: Job;
  onSeen: (id: number) => void;
  onApplied: (id: number) => void;
  onInProgress: (id: number) => void;
  onSetStatus: (id: number, status: JobStatus) => void;
  onTogglePin: (id: number) => void;
  onUpdateNotes: (id: number, notes: string) => void;
  onToast: (msg: string) => void;
  aiEnabled: boolean;
  sortMode: SortOption;
  // Pulso temporário quando essa vaga acabou de mudar via chat do Hunter
  // (marcarStatusDeVaga/atualizarNotaDeVaga) — ajuda a notar a mudança sem
  // precisar procurar o card na lista depois de mexer pelo chat.
  highlighted?: boolean;
  // Pré-filtro heurístico (sem IA, sobreposição de tags do perfil) — ver
  // POST /api/jobs/quick-match-scores. Só passado pra vagas não vistas,
  // onde faz sentido triar; badge só aparece a partir de um mínimo de
  // sobreposição, senão viraria ruído em quase toda vaga.
  matchPercent?: number;
}

const techTags = [
  'java', 'spring', 'react', 'typescript', 'python', 'node',
  'docker', 'kubernetes', 'aws', 'postgresql', 'go', 'rust',
  'fullstack', 'backend', 'frontend', 'devops', 'remote', 'remoto',
];

const ALL_STATUSES: JobStatus[] = ['NOVA', 'VISTA', 'INTERESSADO', 'APLICADA', 'ANDAMENTO', 'RECUSADA'];

// GET /api/jobs/{id}/events — timeline de status (ver model JobEvent no
// backend). Só existe evento a partir de quando essa tabela foi criada,
// vaga antiga não tem histórico retroativo.
interface JobEventDto { status: JobStatus; occurredAt: string }

function currentStatus(job: Job): JobStatus {
  if (job.rejected) return 'RECUSADA';
  if (job.inProgress) return 'ANDAMENTO';
  if (job.applied) return 'APLICADA';
  if (job.interested) return 'INTERESSADO';
  if (job.seen) return 'VISTA';
  return 'NOVA';
}

function daysUntilDeletion(rejectedAt: string): number {
  const rejectedDate = new Date(rejectedAt);
  const deleteDate = new Date(rejectedDate.getTime() + DIAS_PARA_EXCLUIR_RECUSADAS * 86400000);
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  deleteDate.setHours(0, 0, 0, 0);
  return Math.max(0, Math.round((deleteDate.getTime() - today.getTime()) / 86400000));
}

// O backend serializa LocalDateTime sem timezone (o container roda em UTC)
// — ex: "2026-08-10T19:57:00", sem "Z" nem offset. Sem isso, o navegador
// interpreta a string como hora LOCAL (é o padrão do JS pra ISO sem
// timezone), o que descolava o horário mostrado do real em até 3h (fuso
// BR) — reportado: card dizia "19:57" com o relógio real marcando 17:16.
// Força interpretação como UTC anexando "Z" quando a string ainda não tem
// timezone explícito (idempotente: se já vier com Z/offset, não mexe).
function parseBackendIso(iso: string): Date {
  return new Date(/[Zz]|[+-]\d{2}:\d{2}$/.test(iso) ? iso : `${iso}Z`);
}

// Com a busca rodando a cada 2h, só a data não diz qual vaga "acabou de
// chegar" — todo mundo publicado hoje mostrava o mesmo "10 de ago." Se foi
// hoje, mostra a hora exata em vez da data (mais compacto e mais útil);
// vagas mais antigas continuam só com a data, sem virar bagunça em
// milhares de cards antigos que não precisam desse nível de detalhe.
function formatDate(iso: string | null): string {
  if (!iso) return '—';
  const date = parseBackendIso(iso);
  const now = new Date();
  const isToday = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  if (isToday) {
    const hora = new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' }).format(date);
    return `Hoje, ${hora}`;
  }
  return new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: 'short', year: 'numeric'
  }).format(date);
}

function formatDateFull(iso: string | null): string | undefined {
  if (!iso) return undefined;
  return new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: 'short', year: 'numeric', hour: '2-digit', minute: '2-digit'
  }).format(parseBackendIso(iso));
}

// Quando a lista tá ordenada por "🔄 Adicionadas recentemente", o que
// importa é quando o JOB RADAR encontrou a vaga (fetchedAt) — não quando
// ela foi originalmente publicada na fonte (postedAt), que pode ser bem
// mais antigo (uma vaga publicada há 2 dias só "é nova" pra você quando a
// gente finalmente a descobre). Reportado: buscou vagas novas (144→148),
// mas as 4 novas não apareciam com hora "de agora" — o badge só mostrava
// postedAt, sem refletir a descoberta recente.
function formatFetched(iso: string | null): string {
  if (!iso) return '—';
  const date = parseBackendIso(iso);
  const diffMin = Math.floor((Date.now() - date.getTime()) / 60000);
  if (diffMin < 1) return 'agora mesmo';
  if (diffMin < 60) return `há ${diffMin}min`;
  const now = new Date();
  const isToday = date.getFullYear() === now.getFullYear()
    && date.getMonth() === now.getMonth()
    && date.getDate() === now.getDate();
  if (isToday) {
    return `hoje, ${new Intl.DateTimeFormat('pt-BR', { hour: '2-digit', minute: '2-digit' }).format(date)}`;
  }
  const h = Math.floor(diffMin / 60);
  if (h < 24) return `há ${h}h`;
  return new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: 'short' }).format(date);
}

function highlightTechTag(tag: string): boolean {
  return techTags.some(t => tag.toLowerCase().includes(t));
}

function daysUntil(dateStr: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = new Date(dateStr + 'T00:00:00');
  return Math.round((target.getTime() - today.getTime()) / 86400000);
}

function deadlineInfo(expiresAt: string | null): { label: string; className: string } | null {
  if (!expiresAt) return null;
  const days = daysUntil(expiresAt);
  if (days < 0) return { label: '⛔ Encerrada', className: 'badge-deadline--closed' };
  if (days === 0) return { label: '🔥 Fecha hoje!', className: 'badge-deadline--urgent' };
  if (days <= 3) return { label: `🔥 Fecha em ${days}d`, className: 'badge-deadline--urgent' };
  if (days <= 7) return { label: `⏳ Fecha em ${days}d`, className: 'badge-deadline--soon' };
  return { label: `📆 Fecha em ${days}d`, className: 'badge-deadline--ok' };
}

function daysUntilIso(iso: string): number {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const target = parseBackendIso(iso);
  target.setHours(0, 0, 0, 0);
  return Math.round((target.getTime() - today.getTime()) / 86400000);
}

function agendaDeadlineInfo(dueAt: string | null): { label: string; className: string } | null {
  if (!dueAt) return null;
  const days = daysUntilIso(dueAt);
  if (days < 0) return { label: '🔔 Agenda: atrasado', className: 'badge-deadline--closed' };
  if (days === 0) return { label: '🔔 Agenda: hoje', className: 'badge-deadline--urgent' };
  if (days <= 2) return { label: `🔔 Agenda: ${days}d`, className: 'badge-deadline--urgent' };
  return { label: `🔔 Agenda: ${days}d`, className: 'badge-deadline--soon' };
}

function interviewInfo(dueAt: string | null): { label: string; className: string } | null {
  if (!dueAt) return null;
  const days = daysUntilIso(dueAt);
  if (days < 0) return { label: '🎤 Entrevista encerrada', className: 'badge-deadline--closed' };
  if (days === 0) return { label: '🎤 Entrevista hoje!', className: 'badge-deadline--urgent' };
  return { label: `🎤 Entrevista em ${days}d`, className: 'badge-deadline--urgent' };
}

// Checklist dentro da nota — sintaxe Markdown padrão ("- [ ] item" / "- [x]
// item", o traço é opcional). A nota continua sendo texto livre por baixo
// (nada muda no backend/model) — só a exibição reconhece essas linhas e
// vira caixinha clicável em vez de texto solto. O Hunter já lê o campo
// 'notes' cru, então ele também "vê" o [ ]/[x] sem precisar de nada novo.
const CHECKLIST_RE = /^(\s*[-*]?\s*)\[([ xX])\]\s?(.*)$/;

function toggleChecklistLine(notes: string, lineIndex: number): string {
  const linhas = notes.split('\n');
  const m = linhas[lineIndex]?.match(CHECKLIST_RE);
  if (!m) return notes;
  const novoMarcador = m[2].toLowerCase() === 'x' ? ' ' : 'x';
  linhas[lineIndex] = `${m[1]}[${novoMarcador}] ${m[3]}`;
  return linhas.join('\n');
}

function NotesPreview({ notes, onToggle, onOpenEditor }: {
  notes: string;
  onToggle: (lineIndex: number) => void;
  onOpenEditor: () => void;
}) {
  const linhas = notes.split('\n');
  return (
    <div className="notes-preview-block">
      <div className="notes-preview-lines">
        {linhas.map((linha, i) => {
          const m = linha.match(CHECKLIST_RE);
          if (!m) {
            return linha.trim() ? <p key={i} className="notes-preview-text">{linha}</p> : null;
          }
          const marcado = m[2].toLowerCase() === 'x';
          return (
            <button
              key={i}
              type="button"
              className={`notes-checklist-item ${marcado ? 'notes-checklist-item--done' : ''}`}
              onClick={() => onToggle(i)}
            >
              <span className="notes-checklist-box">{marcado ? '✓' : ''}</span>
              <span className="notes-checklist-label">{m[3] || '(item vazio)'}</span>
            </button>
          );
        })}
      </div>
      <button className="notes-preview-edit" onClick={onOpenEditor} title="Editar notas">
        📝 editar
      </button>
    </div>
  );
}

// Gera iniciais da empresa para o avatar fallback
function companyInitials(name: string): string {
  return name
    .split(/[\s-]+/)
    .slice(0, 2)
    .map(w => w[0]?.toUpperCase() ?? '')
    .join('');
}

export function JobCard({ job, onSeen, onApplied, onInProgress, onSetStatus, onTogglePin, onUpdateNotes, onToast, aiEnabled, sortMode, highlighted, matchPercent }: Props) {
  const isOfficialSource = Object.prototype.hasOwnProperty.call(sourceMeta, job.source);
  const { getColor, setColor } = useSourceColors();
  const customColor = !isOfficialSource ? getColor(job.source) : null;
  const src = sourceMeta[job.source] ?? { label: job.source, color: customColor ?? '#64748b' };
  const colorInputRef = useRef<HTMLInputElement>(null);
  const isNew = !job.seen && !job.applied;
  const seniority = seniorityMeta[job.seniority] ?? seniorityMeta.NAO_INFORMADO;
  const showSeniority = job.seniority && job.seniority !== 'NAO_INFORMADO';
  const deadline = deadlineInfo(job.expiresAt);
  const { getLinkedTask, getInterviewTask } = useAgenda();
  const agendaTask = job.rejected ? null : getLinkedTask(job.id);
  const agendaDeadline = agendaDeadlineInfo(agendaTask?.dueAt ?? null);
  const interviewTask = job.rejected ? null : getInterviewTask(job.id);
  const interview = interviewInfo(interviewTask?.dueAt ?? null);

  const isSeenOnly = job.seen && !job.interested && !job.applied && !job.rejected;
  const isInterestedOnly = job.interested && !job.applied && !job.rejected;
  const isPlainApplied = job.applied && !job.inProgress && !job.rejected;
  const status = currentStatus(job);
  const daysLeft = job.rejected && job.rejectedAt ? daysUntilDeletion(job.rejectedAt) : null;

  const [menuOpen, setMenuOpen] = useState(false);
  const [aiMenuOpen, setAiMenuOpen] = useState(false);
  const [notesOpen, setNotesOpen] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);
  const [history, setHistory] = useState<JobEventDto[] | null>(null);
  const [historyLoading, setHistoryLoading] = useState(false);
  const [agendaOpen, setAgendaOpen] = useState(false);
  const [interviewOpen, setInterviewOpen] = useState(false);
  const [coverLetterOpen, setCoverLetterOpen] = useState(false);
  const [salaryOpen, setSalaryOpen] = useState(false);
  const [matchScoreOpen, setMatchScoreOpen] = useState(false);
  const [interviewQuestionsOpen, setInterviewQuestionsOpen] = useState(false);
  const [notesText, setNotesText] = useState(job.notes ?? '');
  const [notesSaved, setNotesSaved] = useState(false);
  const [logoError, setLogoError] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const aiMenuRef = useRef<HTMLDivElement>(null);
  const notesTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const savedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => {
    setNotesText(job.notes ?? '');
  }, [job.notes]);

  useEffect(() => {
    if (!menuOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [menuOpen]);

  useEffect(() => {
    if (!aiMenuOpen) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (aiMenuRef.current && !aiMenuRef.current.contains(e.target as Node)) {
        setAiMenuOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [aiMenuOpen]);

  const handleDragStart = (e: DragEvent<HTMLDivElement>) => {
    e.dataTransfer.setData('text/job-id', String(job.id));
    e.dataTransfer.effectAllowed = 'move';
  };

  const moveTo = (newStatus: JobStatus) => {
    setMenuOpen(false);
    onSetStatus(job.id, newStatus);
  };

  const handleNotesChange = (value: string) => {
    setNotesText(value);
    setNotesSaved(false);
    if (notesTimeout.current) clearTimeout(notesTimeout.current);
    notesTimeout.current = setTimeout(() => {
      onUpdateNotes(job.id, value);
      setNotesSaved(true);
      if (savedTimeout.current) clearTimeout(savedTimeout.current);
      savedTimeout.current = setTimeout(() => setNotesSaved(false), 2000);
    }, 800);
  };

  // Busca sob demanda (não no carregamento da lista inteira) — evita N+1
  // requisições disparando pra cada card visível de uma vez.
  const handleToggleHistory = () => {
    const abrindo = !historyOpen;
    setHistoryOpen(abrindo);
    if (abrindo && history === null) {
      setHistoryLoading(true);
      fetch(`/api/jobs/${job.id}/events`)
        .then(r => r.json())
        .then((data: JobEventDto[]) => setHistory(data))
        .catch(() => setHistory([]))
        .finally(() => setHistoryLoading(false));
    }
  };

  return (
    <div
      className={`job-card ${isNew ? 'job-card--new' : ''} ${isPlainApplied ? 'job-card--applied' : ''} ${job.inProgress && !job.rejected ? 'job-card--in-progress' : ''} ${job.rejected ? 'job-card--rejected' : ''} ${isSeenOnly ? 'job-card--seen' : ''} ${isInterestedOnly ? 'job-card--interested' : ''} ${job.pinned ? 'job-card--pinned' : ''} ${highlighted ? 'job-card--highlighted' : ''}`}
      id={`job-card-${job.id}`}
      draggable={job.applied}
      onDragStart={job.applied ? handleDragStart : undefined}
      title={job.applied ? 'Arraste pra outra aba, ou use o menu ⋮' : undefined}
    >
      {/* Header */}
      <div className="card-header">
        <div className="card-header-left">
          {/* Logo da empresa */}
          <div className="company-avatar" style={{ borderColor: src.color + '44' }}>
            {job.companyLogoUrl && !logoError ? (
              <img
                src={job.companyLogoUrl}
                alt={job.company}
                className="company-logo"
                onError={() => setLogoError(true)}
              />
            ) : (
              <span className="company-initials" style={{ color: src.color }}>
                {companyInitials(job.company)}
              </span>
            )}
          </div>

          <div className="card-badges-left">
            {isNew && <span className="badge-new">NOVA</span>}
            {matchPercent != null && matchPercent >= 50 && (
              <span
                className="badge-match"
                title="Sobreposição de tags técnicas com seu perfil — pré-filtro sem IA, não é uma nota final de compatibilidade"
              >
                🎯 {matchPercent}% match
              </span>
            )}
            {job.rejected && <span className="badge-rejected">❌ RECUSADA</span>}
            {job.inProgress && !job.rejected && <span className="badge-in-progress">EM ANDAMENTO 🔄</span>}
            {isPlainApplied && <span className="badge-applied">APLICADA ✅</span>}
            {isInterestedOnly && <span className="badge-interested">⭐ INTERESSADO</span>}
            {isOfficialSource ? (
              <span className="badge-source" style={{ background: src.color + '22', color: src.color }}>
                {src.label}
              </span>
            ) : (
              <>
                <button
                  type="button"
                  className="badge-source badge-source--custom"
                  style={{ background: src.color + '22', color: src.color, borderColor: src.color + '55' }}
                  onClick={() => colorInputRef.current?.click()}
                  title="Fonte personalizada — clique pra escolher uma cor"
                >
                  {src.label} 🎨
                </button>
                <input
                  ref={colorInputRef}
                  type="color"
                  className="badge-source-color-input"
                  value={src.color}
                  onChange={e => setColor(job.source, e.target.value)}
                  aria-label={`Cor da fonte ${src.label}`}
                />
              </>
            )}
            {showSeniority && (
              <span
                className="badge-seniority"
                style={{ background: seniority.color + '22', color: seniority.color, borderColor: seniority.color + '55' }}
              >
                {seniority.label}
              </span>
            )}
            {job.classifiedByAi && (
              <span className="badge-ai" title="Senioridade/stack classificados por IA (Gemini), porque o título era ambíguo">
                🤖
              </span>
            )}
            {job.pcd && (
              <span className="badge-pcd" title="Vaga com ação afirmativa para Pessoas com Deficiência">
                ♿ PcD
              </span>
            )}
          </div>
        </div>
        <div className="card-header-right">
          {sortMode === 'fetched_desc' ? (
            <span
              className="card-date"
              title={`Publicada na fonte: ${formatDateFull(job.postedAt) ?? '—'} · Encontrada pelo Job Radar: ${formatDateFull(job.fetchedAt) ?? '—'}`}
            >
              🆕 {formatFetched(job.fetchedAt)}
            </span>
          ) : (
            <span className="card-date" title={formatDateFull(job.postedAt)}>📅 {formatDate(job.postedAt)}</span>
          )}

          {/* Botão fixar — oculto em vagas recusadas */}
          {!job.rejected && (
            <button
              className={`btn-pin ${job.pinned ? 'btn-pin--active' : ''}`}
              onClick={() => onTogglePin(job.id)}
              title={job.pinned ? 'Desafixar vaga' : 'Fixar no topo da lista'}
              aria-label={job.pinned ? 'Desafixar' : 'Fixar vaga'}
            >
              📌
            </button>
          )}

          <button
            className={`btn-pin ${historyOpen ? 'btn-pin--active' : ''}`}
            onClick={handleToggleHistory}
            title="Histórico de status dessa vaga"
            aria-label="Ver histórico de status"
          >
            📜
          </button>

          <div className="card-menu" ref={menuRef}>
            <button
              className="card-menu-btn"
              onClick={() => setMenuOpen(open => !open)}
              aria-label="Mais opções"
              title="Mover pra outra aba"
            >
              ⋮
            </button>
            {menuOpen && (
              <div className="card-menu-dropdown">
                <div className="card-menu-title">Mover para:</div>
                {ALL_STATUSES.filter(s => s !== status).map(s => (
                  <button key={s} className="card-menu-item" onClick={() => moveTo(s)}>
                    {statusMeta[s]}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Body */}
      <h3 className="card-title">
        <a href={job.url} target="_blank" rel="noopener noreferrer" draggable={false} onClick={() => onSeen(job.id)}>
          {job.title}
        </a>
      </h3>
      <p className="card-company">🏢 {job.company}</p>

      {(job.workplaceType || job.city || job.state) && (
        <p className="card-location">
          {job.workplaceType && (
            <span className="location-workplace">
              {workplaceMeta[job.workplaceType].icon} {workplaceMeta[job.workplaceType].label}
            </span>
          )}
          {(job.city || job.state) && (
            <span className="location-place">
              📍 {[job.city, job.state].filter(Boolean).join(' - ')}
            </span>
          )}
        </p>
      )}

      {(job.salary || deadline || agendaDeadline || interview || daysLeft !== null) && (
        <div className="card-badges">
          {job.salary && <span className="badge-salary">💰 {job.salary}</span>}
          {deadline && <span className={`badge-deadline ${deadline.className}`}>{deadline.label}</span>}
          {agendaDeadline && <span className={`badge-deadline ${agendaDeadline.className}`}>{agendaDeadline.label}</span>}
          {interview && <span className={`badge-deadline ${interview.className}`}>{interview.label}</span>}
          {daysLeft !== null && (
            <span className="badge-deletion">
              🗑 {daysLeft === 0 ? 'Some hoje' : `Some em ${daysLeft}d`}
            </span>
          )}
        </div>
      )}

      {/* Tags */}
      {job.tags.length > 0 && (
        <div className="card-tags">
          {job.tags.filter(Boolean).slice(0, 8).map(tag => (
            <span
              key={tag}
              className={`tag ${highlightTechTag(tag) ? 'tag--tech' : 'tag--normal'}`}
            >
              {tag}
            </span>
          ))}
        </div>
      )}

      {historyOpen && (
        <div className="card-history-panel">
          {historyLoading ? (
            <p className="card-history-empty">Carregando...</p>
          ) : !history || history.length === 0 ? (
            <p className="card-history-empty">Sem histórico registrado ainda (só a partir de quando essa vaga mudar de status de novo).</p>
          ) : (
            <ul className="card-history-list">
              {history.map((ev, i) => (
                <li key={i}>
                  <span className="card-history-status">{statusMeta[ev.status] ?? ev.status}</span>
                  <span className="card-history-date">{formatDateFull(ev.occurredAt)}</span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {/* Notas pessoais */}
      <div className="card-notes-section">
        {notesOpen ? (
          <div className="notes-editor">
            <div className="notes-editor-head">
              <span className="notes-editor-title">📝 Notas pessoais</span>
              <span className="notes-save-status">
                {notesSaved ? '✓ Salvo' : notesText !== (job.notes ?? '') ? 'Salvando...' : ''}
              </span>
              <button
                className="notes-editor-close"
                onClick={() => setNotesOpen(false)}
                aria-label="Fechar notas"
                title="Fechar"
              >
                ✕
              </button>
            </div>
            <textarea
              className="notes-textarea"
              placeholder="Salário negociado, contato do recrutador, impressões da entrevista... Linhas '- [ ] item' viram checklist clicável."
              value={notesText}
              onChange={e => handleNotesChange(e.target.value)}
              rows={3}
              autoFocus
            />
          </div>
        ) : job.notes ? (
          <NotesPreview
            notes={job.notes}
            onToggle={lineIndex => onUpdateNotes(job.id, toggleChecklistLine(job.notes ?? '', lineIndex))}
            onOpenEditor={() => setNotesOpen(true)}
          />
        ) : (
          <button className="notes-toggle" onClick={() => setNotesOpen(true)}>
            📝 Adicionar nota
          </button>
        )}
      </div>

      {agendaOpen && (
        <AgendaModal
          job={job}
          onClose={() => setAgendaOpen(false)}
          onSuccess={() => {
            setAgendaOpen(false);
            onToast('📅 Tarefa criada na Agenda!');
          }}
        />
      )}

      {interviewOpen && (
        <InterviewModal
          job={job}
          onClose={() => setInterviewOpen(false)}
          onSuccess={() => {
            setInterviewOpen(false);
            onToast('🎤 Entrevista marcada na Agenda!');
          }}
        />
      )}

      {coverLetterOpen && (
        <CoverLetterModal job={job} onClose={() => setCoverLetterOpen(false)} />
      )}

      {salaryOpen && (
        <SalaryEstimateModal job={job} onClose={() => setSalaryOpen(false)} />
      )}

      {matchScoreOpen && (
        <MatchScoreModal job={job} onClose={() => setMatchScoreOpen(false)} />
      )}

      {interviewQuestionsOpen && (
        <InterviewQuestionsModal job={job} onClose={() => setInterviewQuestionsOpen(false)} />
      )}

      {/* Actions */}
      <div className="card-actions">
        <a
          href={job.url}
          target="_blank"
          rel="noopener noreferrer"
          className="btn btn-primary"
          draggable={false}
          onClick={() => onSeen(job.id)}
        >
          Ver vaga →
        </a>
        {!job.rejected && (
          <button
            className="btn btn-agenda"
            onClick={() => setAgendaOpen(true)}
            title="Salvar esta vaga como tarefa na Agenda Pessoal"
          >
            📅 Salvar na Agenda
          </button>
        )}
        {job.inProgress && !job.rejected && (
          <button
            className="btn btn-agenda"
            onClick={() => setInterviewOpen(true)}
            title="Agendar entrevista na Agenda Pessoal (prioridade crítica)"
          >
            🎤 Marcar entrevista
          </button>
        )}
        {!job.rejected && (
          <button
            className="btn btn-ghost"
            onClick={() => setSalaryOpen(true)}
            title="Estimativa de faixa salarial baseada em vagas parecidas já cadastradas (dado real, não IA)"
          >
            💰 Salário estimado
          </button>
        )}
        {aiEnabled && !job.rejected && (
          <div className="card-menu ai-menu" ref={aiMenuRef}>
            <button
              className="btn btn-ai"
              onClick={() => setAiMenuOpen(open => !open)}
              title="Recursos de IA pra essa vaga"
            >
              <HunterIcon size={14} /> IA ▾
            </button>
            {aiMenuOpen && (
              <div className="card-menu-dropdown ai-menu-dropdown">
                <button
                  className="card-menu-item card-menu-item--icon"
                  onClick={() => { setAiMenuOpen(false); setCoverLetterOpen(true); }}
                >
                  <span className="card-menu-item-badge card-menu-item-badge--accent">✉️</span>
                  Gerar carta de apresentação
                </button>
                <button
                  className="card-menu-item card-menu-item--icon"
                  onClick={() => { setAiMenuOpen(false); setMatchScoreOpen(true); }}
                >
                  <span className="card-menu-item-badge card-menu-item-badge--green">🎯</span>
                  Compatibilidade com meu perfil
                </button>
                <button
                  className="card-menu-item card-menu-item--icon"
                  onClick={() => { setAiMenuOpen(false); setInterviewQuestionsOpen(true); }}
                >
                  <span className="card-menu-item-badge card-menu-item-badge--yellow">❓</span>
                  Perguntas prováveis de entrevista
                </button>
              </div>
            )}
          </div>
        )}
        {!job.applied && (
          <button
            className="btn btn-success"
            onClick={() => onApplied(job.id)}
          >
            ✅ Marquei como aplicada
          </button>
        )}
        {!job.seen && !job.applied && (
          <button
            className="btn btn-ghost"
            onClick={() => onSeen(job.id)}
          >
            👁 Marcar como vista
          </button>
        )}
        {!job.applied && !job.rejected && (
          job.interested ? (
            <button
              className="btn btn-ghost"
              onClick={() => onSetStatus(job.id, 'VISTA')}
            >
              ⭐ Tirar interesse
            </button>
          ) : (
            <button
              className="btn btn-interested"
              onClick={() => onSetStatus(job.id, 'INTERESSADO')}
            >
              ⭐ Marquei interesse
            </button>
          )
        )}
        {isPlainApplied && (
          <button
            className="btn btn-progress"
            onClick={() => onInProgress(job.id)}
          >
            🔄 Entrei em processo
          </button>
        )}
        {job.applied && !job.rejected && (
          <button
            className="btn btn-danger"
            onClick={() => onSetStatus(job.id, 'RECUSADA')}
          >
            ❌ Recusada/congelada
          </button>
        )}
        {job.rejected && (
          <button
            className="btn btn-ghost"
            onClick={() => onSetStatus(job.id, 'APLICADA')}
          >
            ↩ Reativar vaga
          </button>
        )}
        {job.inProgress && !job.rejected && (
          <button
            className="btn btn-ghost"
            onClick={() => onApplied(job.id)}
          >
            ↩ Voltar pra Aplicadas
          </button>
        )}
      </div>
    </div>
  );
}

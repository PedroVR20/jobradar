import { DragEvent, useEffect, useRef, useState } from 'react';
import { Job, JobStatus, statusMeta, seniorityMeta, sourceMeta, workplaceMeta } from '../types/Job';

interface Props {
  job: Job;
  onSeen: (id: number) => void;
  onApplied: (id: number) => void;
  onInProgress: (id: number) => void;
  onSetStatus: (id: number, status: JobStatus) => void;
}

const techTags = [
  'java', 'spring', 'react', 'typescript', 'python', 'node',
  'docker', 'kubernetes', 'aws', 'postgresql', 'go', 'rust',
  'fullstack', 'backend', 'frontend', 'devops', 'remote', 'remoto',
];

const ALL_STATUSES: JobStatus[] = ['NOVA', 'VISTA', 'APLICADA', 'ANDAMENTO'];

function currentStatus(job: Job): JobStatus {
  if (job.inProgress) return 'ANDAMENTO';
  if (job.applied) return 'APLICADA';
  if (job.seen) return 'VISTA';
  return 'NOVA';
}

function formatDate(iso: string | null): string {
  if (!iso) return '—';
  return new Intl.DateTimeFormat('pt-BR', {
    day: '2-digit', month: 'short', year: 'numeric'
  }).format(new Date(iso));
}

function highlightTechTag(tag: string): boolean {
  return techTags.some(t => tag.toLowerCase().includes(t));
}

// dias entre hoje (00:00) e a data de expiração (YYYY-MM-DD, sem horário)
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

export function JobCard({ job, onSeen, onApplied, onInProgress, onSetStatus }: Props) {
  const src = sourceMeta[job.source] ?? { label: job.source, color: '#64748b' };
  const isNew = !job.seen && !job.applied;
  const seniority = seniorityMeta[job.seniority] ?? seniorityMeta.NAO_INFORMADO;
  const showSeniority = job.seniority && job.seniority !== 'NAO_INFORMADO';
  const deadline = deadlineInfo(job.expiresAt);

  const isSeenOnly = job.seen && !job.applied;
  const isPlainApplied = job.applied && !job.inProgress;
  const status = currentStatus(job);

  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

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

  const handleDragStart = (e: DragEvent<HTMLDivElement>) => {
    e.dataTransfer.setData('text/job-id', String(job.id));
    e.dataTransfer.effectAllowed = 'move';
  };

  const moveTo = (newStatus: JobStatus) => {
    setMenuOpen(false);
    onSetStatus(job.id, newStatus);
  };

  return (
    <div
      className={`job-card ${isNew ? 'job-card--new' : ''} ${isPlainApplied ? 'job-card--applied' : ''} ${job.inProgress ? 'job-card--in-progress' : ''} ${isSeenOnly ? 'job-card--seen' : ''}`}
      draggable={job.applied}
      onDragStart={job.applied ? handleDragStart : undefined}
      title={job.applied ? 'Arraste pra "Aplicadas" ou "Em Andamento", ou use o menu ⋮' : undefined}
    >
      {/* Header */}
      <div className="card-header">
        <div className="card-header-left">
          {isNew && <span className="badge-new">NOVA</span>}
          {job.inProgress && <span className="badge-in-progress">EM ANDAMENTO 🔄</span>}
          {isPlainApplied && <span className="badge-applied">APLICADA ✅</span>}
          <span className="badge-source" style={{ background: src.color + '22', color: src.color }}>
            {src.label}
          </span>
          {showSeniority && (
            <span
              className="badge-seniority"
              style={{ background: seniority.color + '22', color: seniority.color, borderColor: seniority.color + '55' }}
            >
              {seniority.label}
            </span>
          )}
        </div>
        <div className="card-header-right">
          <span className="card-date">📅 {formatDate(job.postedAt)}</span>
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

      {(job.salary || deadline) && (
        <div className="card-badges">
          {job.salary && <span className="badge-salary">💰 {job.salary}</span>}
          {deadline && <span className={`badge-deadline ${deadline.className}`}>{deadline.label}</span>}
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
        {isPlainApplied && (
          <button
            className="btn btn-progress"
            onClick={() => onInProgress(job.id)}
          >
            🔄 Entrei em processo
          </button>
        )}
        {job.inProgress && (
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

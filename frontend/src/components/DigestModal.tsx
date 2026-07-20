import { useEffect, useState } from 'react';
import { Job, seniorityMeta } from '../types/Job';

interface Props {
  onClose: () => void;
}

function daysAgo(iso: string | null): number | null {
  if (!iso) return null;
  const posted = new Date(iso);
  const now = new Date();
  return Math.floor((now.getTime() - posted.getTime()) / 86400000);
}

export function DigestModal({ onClose }: Props) {
  const [jobs, setJobs] = useState<Job[] | null>(null);

  useEffect(() => {
    fetch('/api/jobs?onlyNew=true&sort=posted_desc')
      .then(r => r.json())
      .then(setJobs);
  }, []);

  const last24h = jobs?.filter(j => { const d = daysAgo(j.postedAt); return d !== null && d <= 1; }) ?? [];
  const last7d = jobs?.filter(j => { const d = daysAgo(j.postedAt); return d !== null && d <= 7; }) ?? [];
  const highlights = jobs?.slice(0, 10) ?? [];

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal digest-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>📰</span>
          <h2>Resumo de vagas novas</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {!jobs ? (
          <p className="agenda-hint">Carregando...</p>
        ) : jobs.length === 0 ? (
          <p className="agenda-hint">Nenhuma vaga nova não vista no momento. Tudo em dia!</p>
        ) : (
          <div className="metrics-body">
            <div className="metrics-grid metrics-grid--3">
              <div className="metric-card">
                <span className="metric-value">{jobs.length}</span>
                <span className="metric-label">🔴 Não vistas (total)</span>
              </div>
              <div className="metric-card">
                <span className="metric-value metric-value--progress">{last24h.length}</span>
                <span className="metric-label">📅 Últimas 24h</span>
              </div>
              <div className="metric-card">
                <span className="metric-value metric-value--waiting">{last7d.length}</span>
                <span className="metric-label">🗓 Últimos 7 dias</span>
              </div>
            </div>

            <div className="metrics-section">
              <h3 className="metrics-section-title">Mais recentes</h3>
              <div className="digest-list">
                {highlights.map(job => (
                  <a
                    key={job.id}
                    className="digest-item"
                    href={job.url}
                    target="_blank"
                    rel="noopener noreferrer"
                  >
                    <span className="digest-item-main">
                      <span className="digest-item-title">{job.title}</span>
                      <span className="digest-item-company">🏢 {job.company}</span>
                    </span>
                    <span className="digest-item-seniority">
                      {seniorityMeta[job.seniority]?.short ?? '—'}
                    </span>
                  </a>
                ))}
              </div>
              {jobs.length > highlights.length && (
                <p className="agenda-hint metrics-note">
                  + {jobs.length - highlights.length} outra(s) vaga(s) não vista(s) — confira na aba "Novas".
                </p>
              )}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

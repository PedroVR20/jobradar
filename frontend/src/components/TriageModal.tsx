import { useEffect, useMemo, useState } from 'react';
import { Job, JobStatus, seniorityMeta, workplaceMeta } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useQuickMatchScores } from '../hooks/useQuickMatchScores';

interface Props {
  onSeen: (id: number) => Promise<void> | void;
  onSetStatus: (id: number, status: JobStatus) => Promise<void> | void;
  onClose: () => void;
}

// Modo "Triagem rápida" — resposta ao gargalo real do app: milhares de vagas
// NOVAS acumuladas que ninguém revisa uma por uma na lista normal (rolar uma
// lista de 4700+ cards não é fluxo, é trabalho). Aqui é uma vaga de cada vez,
// decisão binária rápida (⭐ interessa / ❌ não interessa / pular), com o
// pré-filtro heurístico (sem IA) ordenando as mais prováveis primeiro quando
// há perfil salvo — sem perfil, cai pra ordem cronológica normal.
export function TriageModal({ onSeen, onSetStatus, onClose }: Props) {
  const { profile } = useCandidateProfile();
  const { scores, loading: scoring, refresh } = useQuickMatchScores();
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [index, setIndex] = useState(0);
  const [decided, setDecided] = useState(0);

  useEffect(() => {
    fetch('/api/jobs?onlyNew=true&sort=posted_desc')
      .then(r => r.json())
      .then((data: Job[]) => setJobs(data))
      .catch(() => setJobs([]));
    if (profile.trim()) refresh(profile);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const ordenadas = useMemo(() => {
    if (!jobs) return [];
    if (Object.keys(scores).length === 0) return jobs;
    return [...jobs].sort((a, b) => (scores[String(b.id)] ?? -1) - (scores[String(a.id)] ?? -1));
  }, [jobs, scores]);

  const atual = ordenadas[index];
  const acabou = jobs !== null && (ordenadas.length === 0 || index >= ordenadas.length);

  const avancar = () => setIndex(i => i + 1);

  const handleDecide = async (acao: 'interessa' | 'descarta' | 'pula') => {
    if (!atual) return;
    setDecided(d => d + 1);
    if (acao === 'interessa') await onSetStatus(atual.id, 'INTERESSADO');
    else if (acao === 'descarta') await onSetStatus(atual.id, 'RECUSADA');
    else await onSeen(atual.id);
    avancar();
  };

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { onClose(); return; }
      if (acabou || !atual) return;
      if (e.key === 'ArrowRight' || e.key.toLowerCase() === 'y') handleDecide('interessa');
      else if (e.key === 'ArrowLeft' || e.key.toLowerCase() === 'n') handleDecide('descarta');
      else if (e.key === ' ' || e.key.toLowerCase() === 's') { e.preventDefault(); handleDecide('pula'); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual, acabou]);

  const matchPercent = atual ? scores[String(atual.id)] : undefined;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal triage-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>⚡</span>
          <h2>Triagem rápida</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {jobs === null ? (
          <p className="agenda-hint" style={{ padding: '1rem' }}>Carregando vagas não vistas...</p>
        ) : acabou ? (
          <div className="triage-done">
            <p className="triage-done-emoji">🎉</p>
            <p>{decided > 0 ? `Triou ${decided} vaga${decided === 1 ? '' : 's'}!` : 'Nenhuma vaga nova pra triar agora.'}</p>
            <button className="btn btn-primary" onClick={onClose}>Fechar</button>
          </div>
        ) : (
          <>
            <div className="triage-progress">
              <span>{index + 1} de {ordenadas.length}</span>
              {profile.trim() && (
                <span className="triage-progress-hint">
                  {scoring ? 'calculando pré-filtro...' : 'ordenado por provável match'}
                </span>
              )}
            </div>

            <div className="triage-card">
              {matchPercent != null && matchPercent >= 50 && (
                <span className="badge-match" style={{ marginBottom: '0.4rem', display: 'inline-block' }}>
                  🎯 {matchPercent}% match
                </span>
              )}
              <h3 className="triage-card-title">{atual.title}</h3>
              <p className="triage-card-company">🏢 {atual.company}</p>
              <p className="triage-card-meta">
                {atual.workplaceType && <span>{workplaceMeta[atual.workplaceType].icon} {workplaceMeta[atual.workplaceType].label}</span>}
                {atual.seniority !== 'NAO_INFORMADO' && <span>{seniorityMeta[atual.seniority].label}</span>}
                {atual.salary && <span>💰 {atual.salary}</span>}
                {(atual.city || atual.state) && <span>📍 {[atual.city, atual.state].filter(Boolean).join(' - ')}</span>}
              </p>
              {atual.tags.length > 0 && (
                <div className="card-tags">
                  {atual.tags.slice(0, 8).map(t => <span key={t} className="tag tag--normal">{t}</span>)}
                </div>
              )}
              <a href={atual.url} target="_blank" rel="noopener noreferrer" className="btn btn-ghost triage-card-link">
                Ver vaga completa →
              </a>
            </div>

            <div className="triage-actions">
              <button className="btn btn-danger" onClick={() => handleDecide('descarta')} title="Atalho: ← ou N">
                ❌ Não interessa
              </button>
              <button className="btn btn-ghost" onClick={() => handleDecide('pula')} title="Atalho: Espaço ou S">
                ⏭ Pular
              </button>
              <button className="btn btn-interested" onClick={() => handleDecide('interessa')} title="Atalho: → ou Y">
                ⭐ Interessa!
              </button>
            </div>
            <p className="triage-shortcuts-hint">← não interessa · espaço pula · → interessa · Esc fecha</p>
          </>
        )}
      </div>
    </div>
  );
}

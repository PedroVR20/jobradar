import { useEffect, useState } from 'react';
import { DuplicateGroup, sourceMeta } from '../types/Job';
import { SkeletonLines } from './SkeletonCard';
import { useEscapeToClose } from '../hooks/useEscapeToClose';

interface Props {
  onClose: () => void;
  onReject: (id: number) => void;
}

export function DuplicatesModal({ onClose, onReject }: Props) {
  useEscapeToClose(onClose);
  const [groups, setGroups] = useState<DuplicateGroup[] | null>(null);

  useEffect(() => {
    fetch('/api/jobs/duplicates')
      .then(r => r.json())
      .then(setGroups);
  }, []);

  const handleReject = (jobId: number) => {
    onReject(jobId);
    setGroups(prev =>
      prev
        ?.map(g => ({ ...g, jobs: g.jobs.filter(j => j.id !== jobId) }))
        .filter(g => g.jobs.length > 1) ?? null
    );
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal duplicates-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>🧩</span>
          <h2>Possíveis duplicatas</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Vagas da mesma empresa com título muito parecido, vindas de fontes diferentes.
          Isso é só uma sugestão — nada é apagado automaticamente, você decide o que recusar.
        </p>

        {!groups ? (
          <SkeletonLines count={4} />
        ) : groups.length === 0 ? (
          <p className="agenda-hint">Nenhuma duplicata encontrada. 🎉</p>
        ) : (
          <div className="duplicates-list">
            {groups.map((group, idx) => (
              <div key={`${group.company}-${idx}`} className="duplicate-group">
                <h3 className="duplicate-group-title">
                  🏢 {group.company}
                  {group.aiVerificado && (
                    <span className="badge-ai badge-ai--inline" title="A IA (Gemini) confirmou que são a mesma vaga">
                      🤖 confirmado por IA
                    </span>
                  )}
                </h3>
                {group.jobs.map(job => (
                  <div key={job.id} className="duplicate-item">
                    <span
                      className="badge-source"
                      style={{
                        background: (sourceMeta[job.source]?.color ?? '#64748b') + '22',
                        color: sourceMeta[job.source]?.color ?? '#64748b',
                      }}
                    >
                      {sourceMeta[job.source]?.label ?? job.source}
                    </span>
                    <a
                      className="duplicate-item-title"
                      href={job.url}
                      target="_blank"
                      rel="noopener noreferrer"
                    >
                      {job.title}
                    </a>
                    <button
                      className="btn btn-danger duplicate-item-reject"
                      onClick={() => handleReject(job.id)}
                    >
                      ❌ Recusar
                    </button>
                  </div>
                ))}
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

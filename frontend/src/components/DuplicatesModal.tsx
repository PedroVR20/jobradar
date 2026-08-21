import { useEffect, useState } from 'react';
import { DuplicateGroup, sourceMeta } from '../types/Job';
import { SkeletonLines } from './SkeletonCard';
import { useEscapeToClose } from '../hooks/useEscapeToClose';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Props {
  onClose: () => void;
  onReject: (id: number) => void;
}

export function DuplicatesModal({ onClose, onReject }: Props) {
  useEscapeToClose(onClose);
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const [groups, setGroups] = useState<DuplicateGroup[] | null>(null);
  // Fase 11.1 — verificação por IA saiu do GET /duplicates (travava o
  // endpoint) e virou sob demanda, por grupo. Estado local só pra saber
  // qual grupo está com a chamada em voo / deu erro.
  const [verificando, setVerificando] = useState<Set<number>>(new Set());
  const [erroVerificacao, setErroVerificacao] = useState<Record<number, string>>({});

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

  const handleVerificarComIa = async (idx: number, group: DuplicateGroup) => {
    setVerificando(prev => new Set(prev).add(idx));
    setErroVerificacao(prev => { const { [idx]: _omit, ...rest } = prev; return rest; });
    try {
      const res = await fetch('/api/jobs/duplicates/verify', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ company: group.company, titles: group.jobs.map(j => j.title) }),
      });
      const data = await res.json();
      if (!res.ok) {
        setErroVerificacao(prev => ({ ...prev, [idx]: data.error ?? 'Não consegui verificar agora.' }));
        return;
      }
      if (data.mesmaVaga) {
        setGroups(prev => prev?.map((g, i) => i === idx ? { ...g, aiVerificado: true } : g) ?? null);
      } else {
        // IA disse que são vagas genuinamente diferentes — remove o grupo,
        // mesmo comportamento de antes quando a verificação rodava embutida.
        setGroups(prev => prev?.filter((_, i) => i !== idx) ?? null);
      }
    } catch {
      setErroVerificacao(prev => ({ ...prev, [idx]: 'Erro de conexão com o backend.' }));
    } finally {
      setVerificando(prev => { const next = new Set(prev); next.delete(idx); return next; });
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal duplicates-modal"
        onClick={e => e.stopPropagation()}
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="duplicates-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <span>🧩</span>
          <h2 id="duplicates-modal-title">Possíveis duplicatas</h2>
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
                  {group.aiVerificado ? (
                    <span className="badge-ai badge-ai--inline" title="A IA (Gemini) confirmou que são a mesma vaga">
                      🤖 confirmado por IA
                    </span>
                  ) : (
                    <button
                      type="button"
                      className="btn-link duplicate-verify-btn"
                      onClick={() => handleVerificarComIa(idx, group)}
                      disabled={verificando.has(idx)}
                    >
                      {verificando.has(idx) ? '🤖 verificando…' : '🤖 verificar com IA'}
                    </button>
                  )}
                </h3>
                {erroVerificacao[idx] && (
                  <p className="agenda-error duplicate-verify-error">{erroVerificacao[idx]}</p>
                )}
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

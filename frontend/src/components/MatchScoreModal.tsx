import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job, MatchScoreResult } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { AiFeedbackBox } from './AiFeedbackBox';

interface Props {
  job: Job;
  onClose: () => void;
}

function scoreColor(score: number): string {
  if (score >= 70) return 'var(--green)';
  if (score >= 40) return 'var(--yellow)';
  return 'var(--red)';
}

export function MatchScoreModal({ job, onClose }: Props) {
  const { profile } = useCandidateProfile();
  const { buildContext } = useAiFeedback('match-score');
  const [perfilTexto, setPerfilTexto] = useState(profile);
  const [result, setResult] = useState<MatchScoreResult | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleCalculate = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch(`/api/jobs/${job.id}/match-score`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ candidateProfile: perfilTexto, feedbackContext: buildContext() || undefined }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? 'Não foi possível calcular agora. Tente de novo em instantes.');
        return;
      }
      setResult(data as MatchScoreResult);
    } catch {
      setError('Erro de conexão com o backend.');
    } finally {
      setLoading(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal match-score-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>🎯</span>
          <h2>Compatibilidade com a vaga</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Compara seu perfil com <strong>{job.title}</strong> na <strong>{job.company}</strong> (buscando a
          descrição real da vaga quando possível) e avalia com honestidade o que bate e o que falta.
        </p>

        <label className="agenda-label">
          Seu perfil/currículo
          <textarea
            className="agenda-input"
            value={perfilTexto}
            onChange={e => setPerfilTexto(e.target.value)}
            placeholder="Cole seu currículo/resumo de experiência aqui, ou salve um em ⚙️ Configurações pra vir preenchido sozinho."
            rows={4}
            disabled={loading}
          />
        </label>

        {!profile && (
          <p className="agenda-hint cover-letter-profile-hint">
            💡 Dica: salve seu currículo/stack uma vez em ⚙️ Configurações e ele preenche esse campo sozinho da próxima vez.
          </p>
        )}

        {error && <p className="agenda-error">{error}</p>}

        {result && (
          <div className="match-result">
            <div className="match-score-circle" style={{ borderColor: scoreColor(result.score), color: scoreColor(result.score) }}>
              {result.score}
              <span className="match-score-percent">%</span>
            </div>
            {result.resumo && <p className="match-summary">{result.resumo}</p>}

            {result.pontosFortes.length > 0 && (
              <div className="match-section">
                <h4 className="match-section-title match-section-title--good">✅ Pontos fortes</h4>
                <ul className="match-list">
                  {result.pontosFortes.map((p, i) => <li key={i}>{p}</li>)}
                </ul>
              </div>
            )}

            {result.pontosFaltando.length > 0 && (
              <div className="match-section">
                <h4 className="match-section-title match-section-title--gap">⚠️ Pontos a desenvolver</h4>
                <ul className="match-list">
                  {result.pontosFaltando.map((p, i) => <li key={i}>{p}</li>)}
                </ul>
              </div>
            )}

            <AiFeedbackBox featureKey="match-score" label="Essa análise ficou boa?" />
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
          <button className="btn btn-ai" onClick={handleCalculate} disabled={loading || !perfilTexto.trim()}>
            {loading ? 'Calculando...' : result ? '🔄 Calcular de novo' : '🎯 Calcular compatibilidade'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

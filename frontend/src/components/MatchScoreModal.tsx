import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job, LearningPlan, MatchScoreResult } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { combineWithGitHub, useGitHubProfile } from '../hooks/useGitHubProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { AiFeedbackBox } from './AiFeedbackBox';
import { useEscapeToClose } from '../hooks/useEscapeToClose';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Props {
  job: Job;
  onClose: () => void;
}

function scoreColor(score: number): string {
  if (score >= 70) return 'var(--green)';
  if (score >= 40) return 'var(--yellow)';
  return 'var(--red)';
}

interface GapItemProps {
  job: Job;
  gap: string;
  candidateProfile: string;
  feedbackContext: string;
}

// Cada "ponto a desenvolver" vira uma contramedida sob demanda — só chama a
// IA de novo se o usuário realmente clicar em "Plano de ação" nesse item
// específico, não gera plano pra todas as lacunas de uma vez (gastaria cota
// à toa em pontos que o usuário nem tá interessado em desenvolver agora).
function GapItem({ job, gap, candidateProfile, feedbackContext }: GapItemProps) {
  const [plan, setPlan] = useState<LearningPlan | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);

  const handleGeneratePlan = async () => {
    if (plan) { setOpen(o => !o); return; }
    setLoading(true);
    setError('');
    try {
      const res = await fetch(`/api/jobs/${job.id}/learning-plan`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ gap, candidateProfile, feedbackContext: feedbackContext || undefined }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? 'Não foi possível gerar o plano agora. Tente de novo.');
        return;
      }
      setPlan(data as LearningPlan);
      setOpen(true);
    } catch {
      setError('Erro de conexão com o backend.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <li className="match-gap-item">
      <span className="match-gap-text">{gap}</span>
      <button type="button" className="match-plan-btn" onClick={handleGeneratePlan} disabled={loading}>
        {loading ? '⏳ Gerando plano...' : plan ? (open ? '📚 Ocultar plano' : '📚 Ver plano') : '📚 Plano de ação'}
      </button>
      {error && <p className="agenda-error match-gap-error">{error}</p>}
      {plan && open && (
        <div className="match-plan">
          {plan.resumo && <p className="match-plan-resumo">{plan.resumo}</p>}
          {plan.tempoEstimado && <span className="match-plan-tempo">⏱ {plan.tempoEstimado}</span>}
          {plan.passos.length > 0 && (
            <ol className="match-plan-steps">
              {plan.passos.map((passo, i) => <li key={i}>{passo}</li>)}
            </ol>
          )}
          <AiFeedbackBox featureKey="learning-plan" label="Esse plano ficou bom?" />
        </div>
      )}
    </li>
  );
}

export function MatchScoreModal({ job, onClose }: Props) {
  useEscapeToClose(onClose);
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const { profile } = useCandidateProfile();
  const { summary: githubSummary } = useGitHubProfile();
  const { buildContext } = useAiFeedback('match-score');
  const { buildContext: buildPlanContext } = useAiFeedback('learning-plan');
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
        body: JSON.stringify({ candidateProfile: combineWithGitHub(perfilTexto, githubSummary), feedbackContext: buildContext() || undefined }),
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
      <div
        className="modal match-score-modal"
        onClick={e => e.stopPropagation()}
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="match-score-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <span>🎯</span>
          <h2 id="match-score-modal-title">Compatibilidade com a vaga</h2>
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
                  {result.pontosFaltando.map((p, i) => (
                    <GapItem key={i} job={job} gap={p} candidateProfile={combineWithGitHub(perfilTexto, githubSummary)} feedbackContext={buildPlanContext()} />
                  ))}
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

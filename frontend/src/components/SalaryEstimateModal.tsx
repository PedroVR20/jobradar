import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Job, PersonalizedSalaryEstimate, SalaryEstimate } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useEscapeToClose } from '../hooks/useEscapeToClose';

interface Props {
  job: Job;
  onClose: () => void;
}

function fmt(n: number | undefined): string {
  return n !== undefined ? `R$ ${n.toLocaleString('pt-BR')}` : '—';
}

export function SalaryEstimateModal({ job, onClose }: Props) {
  useEscapeToClose(onClose);
  const { profile } = useCandidateProfile();
  const [estimate, setEstimate] = useState<SalaryEstimate | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const [personalized, setPersonalized] = useState<PersonalizedSalaryEstimate | null>(null);
  const [personalizing, setPersonalizing] = useState(false);

  useEffect(() => {
    fetch(`/api/jobs/${job.id}/salary-estimate`)
      .then(r => r.json())
      .then((data: SalaryEstimate) => setEstimate(data))
      .catch(() => setError('Erro ao buscar a estimativa. Tente de novo.'))
      .finally(() => setLoading(false));
  }, [job.id]);

  const handlePersonalize = async () => {
    setPersonalizing(true);
    try {
      const res = await fetch(`/api/jobs/${job.id}/salary-estimate/personalized`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ candidateProfile: profile }),
      });
      const data = await res.json();
      setPersonalized(data as PersonalizedSalaryEstimate);
    } catch {
      // silencioso — é um extra opcional, não trava o resto do modal
    } finally {
      setPersonalizing(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal salary-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>💰</span>
          <h2>Faixa salarial estimada</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Estimativa por um modelo treinado com vagas reais do nosso banco (não é IA generativa —
          é regressão estatística sobre senioridade, stack, modalidade e estado). Nunca é um chute.
        </p>

        {loading ? (
          <p className="agenda-hint">Calculando...</p>
        ) : error ? (
          <p className="agenda-error">{error}</p>
        ) : !estimate?.available ? (
          <p className="agenda-hint">
            Não foi possível estimar pra essa vaga (modelo indisponível ou dados insuficientes).
          </p>
        ) : (
          <div className="salary-result">
            {estimate.predicted !== undefined && (
              <>
                <div className="salary-range">
                  <span className="salary-range-value">{fmt(estimate.predicted)}</span>
                  <span className="salary-median-label">/mês</span>
                </div>
                {estimate.modelInfo && (
                  <p className="agenda-hint salary-sample">
                    ⚠️ Margem de erro típica de ~{estimate.modelInfo.maePercent}% (modelo treinado com{' '}
                    {estimate.modelInfo.nSamples} vagas do nosso banco) — use como ponto de partida pra
                    negociação, não como número final.
                  </p>
                )}
              </>
            )}

            {estimate.similarJobs && (
              <div className="salary-similar">
                <span className="salary-similar-label">Vagas parecidas no banco:</span>
                <span>
                  {fmt(estimate.similarJobs.min)} – {fmt(estimate.similarJobs.max)} (mediana{' '}
                  {fmt(estimate.similarJobs.median)}, {estimate.similarJobs.sampleSize} vagas)
                </span>
              </div>
            )}

            <div className="salary-personalized">
              {!personalized ? (
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={handlePersonalize}
                  disabled={personalizing || !profile}
                  title={!profile ? 'Salve seu currículo em ⚙️ Configurações primeiro' : undefined}
                >
                  {personalizing ? 'Calculando...' : '🎯 Baseado no meu perfil salvo'}
                </button>
              ) : personalized.available ? (
                <div className="salary-personalized-result">
                  <span className="salary-personalized-label">
                    Baseado no seu perfil ({personalized.inferredSeniority}
                    {personalized.inferredStack && personalized.inferredStack.length > 0
                      ? `, ${personalized.inferredStack.slice(0, 5).join(', ')}`
                      : ''}
                    ):
                  </span>
                  <span className="salary-range-value salary-range-value--small">{fmt(personalized.predicted)}/mês</span>
                </div>
              ) : (
                <p className="agenda-hint">Não consegui identificar stack/senioridade suficiente no seu perfil salvo.</p>
              )}
              {!profile && !personalized && (
                <p className="agenda-hint cover-letter-profile-hint">
                  💡 Salve seu currículo em ⚙️ Configurações pra ver uma estimativa baseada na sua própria stack.
                </p>
              )}
            </div>
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-primary" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>,
    document.body
  );
}

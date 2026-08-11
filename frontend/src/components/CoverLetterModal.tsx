import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { combineWithGitHub, useGitHubProfile } from '../hooks/useGitHubProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { AiFeedbackBox } from './AiFeedbackBox';

interface Props {
  job: Job;
  onClose: () => void;
}

export function CoverLetterModal({ job, onClose }: Props) {
  const { profile } = useCandidateProfile();
  const { summary: githubSummary } = useGitHubProfile();
  const { buildContext } = useAiFeedback('cover-letter');
  // Pré-preenche com o perfil salvo em Configurações (se houver) — o usuário
  // pode editar/completar livremente pra essa vaga específica; a edição aqui
  // não altera o perfil salvo, só afeta essa geração.
  const [extraContext, setExtraContext] = useState(profile);
  const [letter, setLetter] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [copied, setCopied] = useState(false);

  const handleGenerate = async () => {
    setLoading(true);
    setError('');
    setCopied(false);
    try {
      const res = await fetch(`/api/jobs/${job.id}/cover-letter`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          extraContext: combineWithGitHub(extraContext.trim(), githubSummary) || undefined,
          feedbackContext: buildContext() || undefined,
        }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? 'Não foi possível gerar a carta agora. Tente de novo em instantes.');
        return;
      }
      setLetter(data.coverLetter as string);
    } catch {
      setError('Erro de conexão com o backend.');
    } finally {
      setLoading(false);
    }
  };

  const handleCopy = () => {
    if (!letter) return;
    navigator.clipboard.writeText(letter).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal cover-letter-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>🤖</span>
          <h2>Carta de apresentação</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Gerada por IA (Gemini) com os dados que o Job Radar já tem sobre <strong>{job.title}</strong> na{' '}
          <strong>{job.company}</strong>. Ela não inventa sua experiência — cole abaixo trechos do seu
          currículo/perfil ou requisitos da vaga pra deixar o resultado mais direcionado.
        </p>

        <label className="agenda-label">
          Contexto adicional (opcional)
          <textarea
            className="agenda-input"
            value={extraContext}
            onChange={e => setExtraContext(e.target.value)}
            placeholder="Ex: 3 anos com Java/Spring, projeto X, certificação Y..."
            rows={3}
            disabled={loading}
          />
        </label>

        {profile ? (
          <p className="agenda-hint cover-letter-profile-hint">
            📄 Pré-preenchido com seu perfil salvo em ⚙️ Configurações — edite à vontade pra essa vaga.
          </p>
        ) : (
          <p className="agenda-hint cover-letter-profile-hint">
            💡 Dica: salve seu currículo/stack uma vez em ⚙️ Configurações e ele preenche esse campo sozinho da próxima vez.
          </p>
        )}

        {error && <p className="agenda-error">{error}</p>}

        {letter && (
          <div className="cover-letter-result">
            <textarea className="cover-letter-textarea" value={letter} readOnly rows={10} />
            <AiFeedbackBox featureKey="cover-letter" label="Essa carta ficou boa?" />
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
          {letter && (
            <button className="btn btn-ghost" onClick={handleCopy}>
              {copied ? '✅ Copiado!' : '📋 Copiar'}
            </button>
          )}
          <button className="btn btn-ai" onClick={handleGenerate} disabled={loading}>
            {loading ? 'Gerando...' : letter ? '🔄 Gerar de novo' : '🤖 Gerar carta'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

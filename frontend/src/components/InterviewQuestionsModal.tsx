import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job } from '../types/Job';
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

export function InterviewQuestionsModal({ job, onClose }: Props) {
  useEscapeToClose(onClose);
  const dialogRef = useFocusTrap<HTMLDivElement>();
  const { profile } = useCandidateProfile();
  const { summary: githubSummary } = useGitHubProfile();
  const { buildContext } = useAiFeedback('interview-questions');
  const [questions, setQuestions] = useState<string[] | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const handleGenerate = async () => {
    setLoading(true);
    setError('');
    try {
      const res = await fetch(`/api/jobs/${job.id}/interview-questions`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ candidateProfile: combineWithGitHub(profile, githubSummary) || undefined, feedbackContext: buildContext() || undefined }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? 'Não foi possível gerar as perguntas agora. Tente de novo em instantes.');
        return;
      }
      setQuestions(data.questions as string[]);
    } catch {
      setError('Erro de conexão com o backend.');
    } finally {
      setLoading(false);
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal interview-questions-modal"
        onClick={e => e.stopPropagation()}
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="interview-questions-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <span>❓</span>
          <h2 id="interview-questions-modal-title">Perguntas prováveis de entrevista</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Geradas com base em <strong>{job.title}</strong> na <strong>{job.company}</strong> (e na descrição real
          da vaga, quando disponível){profile ? ', considerando também o perfil que você salvou em ⚙️ Configurações.' : '.'}
        </p>

        {!profile && (
          <p className="agenda-hint cover-letter-profile-hint">
            💡 Sem perfil salvo, as perguntas ficam focadas só na vaga/stack — salve um currículo em
            ⚙️ Configurações pra perguntas mais direcionadas à sua experiência.
          </p>
        )}

        {error && <p className="agenda-error">{error}</p>}

        {questions && (
          <>
            <ol className="interview-questions-list">
              {questions.map((q, i) => <li key={i}>{q}</li>)}
            </ol>
            <AiFeedbackBox featureKey="interview-questions" label="Essas perguntas ficaram boas?" />
          </>
        )}

        <div className="modal-actions">
          <button className="btn btn-ghost" onClick={onClose}>Fechar</button>
          <button className="btn btn-ai" onClick={handleGenerate} disabled={loading}>
            {loading ? 'Gerando...' : questions ? '🔄 Gerar de novo' : '❓ Gerar perguntas'}
          </button>
        </div>
      </div>
    </div>,
    document.body
  );
}

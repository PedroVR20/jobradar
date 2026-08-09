import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';

interface Props {
  job: Job;
  onClose: () => void;
}

export function InterviewQuestionsModal({ job, onClose }: Props) {
  const { profile } = useCandidateProfile();
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
        body: JSON.stringify({ candidateProfile: profile || undefined }),
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
      <div className="modal interview-questions-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>❓</span>
          <h2>Perguntas prováveis de entrevista</h2>
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
          <ol className="interview-questions-list">
            {questions.map((q, i) => <li key={i}>{q}</li>)}
          </ol>
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

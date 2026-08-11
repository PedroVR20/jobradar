import { useState } from 'react';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { ThumbDownIcon, ThumbUpIcon } from './HunterMiniIcons';

interface Props {
  // Chave que separa o histórico por recurso (ex: "cover-letter",
  // "match-score", "interview-questions") — cada um guarda o próprio.
  featureKey: string;
  label?: string;
}

// Caixa de avaliação reaproveitada em CoverLetterModal, MatchScoreModal e
// InterviewQuestionsModal — 👍/👎 + comentário livre sobre o que concorda/
// discorda, salvos localmente e reaproveitados como referência de estilo na
// próxima geração desse mesmo recurso (ver useAiFeedback).
export function AiFeedbackBox({ featureKey, label = 'Essa geração ficou boa?' }: Props) {
  const { entries, addFeedback, removeFeedback, clearFeedback } = useAiFeedback(featureKey);
  const [rating, setRating] = useState<'like' | 'dislike' | null>(null);
  const [comment, setComment] = useState('');
  const [saved, setSaved] = useState(false);
  const [historyOpen, setHistoryOpen] = useState(false);

  const handleRate = (r: 'like' | 'dislike') => {
    setRating(r);
    setSaved(false);
  };

  const handleSave = () => {
    if (!rating) return;
    addFeedback(rating, comment);
    setRating(null);
    setComment('');
    setSaved(true);
    setTimeout(() => setSaved(false), 2500);
  };

  return (
    <div className="ai-feedback-box">
      <div className="ai-feedback-head">
        <span className="ai-feedback-label">{label}</span>
        <div className="ai-feedback-rate">
          <button
            type="button"
            className={`ai-feedback-btn ${rating === 'like' ? 'ai-feedback-btn--active' : ''}`}
            onClick={() => handleRate('like')}
            aria-label="Gostei"
          >
            <ThumbUpIcon />
          </button>
          <button
            type="button"
            className={`ai-feedback-btn ai-feedback-btn--down ${rating === 'dislike' ? 'ai-feedback-btn--active' : ''}`}
            onClick={() => handleRate('dislike')}
            aria-label="Não gostei"
          >
            <ThumbDownIcon />
          </button>
        </div>
      </div>

      {rating && (
        <div className="ai-feedback-form">
          <textarea
            className="agenda-input"
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="Opcional: o que especificamente você concorda ou discorda? (ex: 'muito longa', 'gostei do tom direto', 'a pergunta 3 não fazia sentido pra essa vaga')"
            rows={2}
          />
          <button type="button" className="btn btn-ai ai-feedback-save" onClick={handleSave}>
            Salvar feedback
          </button>
        </div>
      )}

      {saved && <p className="ai-feedback-saved">✓ Feedback salvo — vale pras próximas gerações desse recurso.</p>}

      {entries.length > 0 && (
        <div className="ai-feedback-history">
          <button type="button" className="profile-manual-toggle" onClick={() => setHistoryOpen(o => !o)}>
            {historyOpen ? '▾' : '▸'} Ver feedback salvo ({entries.length})
          </button>
          {historyOpen && (
            <div className="ai-feedback-list">
              {entries.map((e, i) => (
                <div key={e.timestamp} className="ai-feedback-item">
                  <span>{e.rating === 'like' ? '👍' : '👎'}</span>
                  <span className="ai-feedback-item-comment">{e.comment || '(sem comentário)'}</span>
                  <button
                    type="button"
                    className="ai-feedback-item-remove"
                    onClick={() => removeFeedback(i)}
                    aria-label="Remover"
                    title="Remover esse feedback"
                  >
                    ✕
                  </button>
                </div>
              ))}
              <button type="button" className="ai-feedback-clear" onClick={clearFeedback}>
                🗑 Limpar todo o feedback
              </button>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

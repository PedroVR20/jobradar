import { useState } from 'react';

// "Treinar" a IA sem fine-tuning de verdade: guarda o que o usuário achou
// bom/ruim de gerações anteriores (carta, compatibilidade, perguntas de
// entrevista) e devolve isso formatado pra entrar no próximo prompt como
// referência de estilo — o modelo em si nunca muda, só o contexto que ele
// recebe. Fica só no navegador (localStorage), nunca persistido no backend;
// só é enviado junto do request quando o usuário gera algo novo.
export interface FeedbackEntry {
  rating: 'like' | 'dislike';
  comment: string;
  timestamp: number;
}

// Limita o histórico guardado — sem isso o prompt cresceria sem parar e
// feedback muito antigo tende a valer menos que o mais recente mesmo.
const MAX_ENTRIES = 8;

export function useAiFeedback(featureKey: string) {
  const storageKey = `jobradar:ai-feedback:${featureKey}`;

  const [entries, setEntries] = useState<FeedbackEntry[]>(() => {
    try {
      const raw = localStorage.getItem(storageKey);
      return raw ? (JSON.parse(raw) as FeedbackEntry[]) : [];
    } catch {
      return [];
    }
  });

  const persist = (next: FeedbackEntry[]) => {
    setEntries(next);
    if (next.length > 0) localStorage.setItem(storageKey, JSON.stringify(next));
    else localStorage.removeItem(storageKey);
  };

  const addFeedback = (rating: 'like' | 'dislike', comment: string) => {
    const next = [...entries, { rating, comment: comment.trim(), timestamp: Date.now() }].slice(-MAX_ENTRIES);
    persist(next);
  };

  const removeFeedback = (index: number) => {
    persist(entries.filter((_, i) => i !== index));
  };

  const clearFeedback = () => persist([]);

  // Formata pro backend incluir no prompt — texto simples, um item por linha.
  const buildContext = (): string => {
    if (entries.length === 0) return '';
    return entries
      .map(e => `- ${e.rating === 'like' ? '👍 gostou' : '👎 não gostou'}${e.comment ? `: ${e.comment}` : ' (sem comentário)'}`)
      .join('\n');
  };

  return { entries, addFeedback, removeFeedback, clearFeedback, buildContext };
}

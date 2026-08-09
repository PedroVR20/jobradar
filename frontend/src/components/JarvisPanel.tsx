import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { CompatibilityScanResult } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';

interface Props {
  onClose: () => void;
}

type Message =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; text: string }
  | { id: number; role: 'assistant-scan'; result: CompatibilityScanResult; days: number }
  | { id: number; role: 'assistant-loading' };

const SUGESTOES = [
  { label: '🎯 Compatibilidade com vagas de hoje', text: 'compatibilidade com vagas de hoje' },
  { label: '📆 Compatibilidade com vagas da semana', text: 'compatibilidade com vagas da semana' },
  { label: '📊 Resumo rápido', text: 'resumo rápido' },
];

function scoreColor(score: number): string {
  if (score >= 70) return 'var(--green)';
  if (score >= 40) return 'var(--yellow)';
  return 'var(--red)';
}

let nextId = 1;

export function JarvisPanel({ onClose }: Props) {
  const { profile } = useCandidateProfile();
  const { buildContext } = useAiFeedback('match-score');
  const [messages, setMessages] = useState<Message[]>([
    { id: nextId++, role: 'assistant', text: 'Oi! Eu sou o Jarvis 🤖 — posso comparar seu perfil salvo com as vagas mais recentes, ou te dar um resumo rápido do funil. O que você quer saber?' },
  ]);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  const addMessage = (m: Omit<Message, 'id'>) => setMessages(prev => [...prev, { ...m, id: nextId++ } as Message]);

  const runCompatibilityScan = async (days: number) => {
    addMessage({ role: 'assistant-loading' } as Omit<Message, 'id'>);
    try {
      const res = await fetch('/api/jobs/assistant/compatibility-scan', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ candidateProfile: profile, days, feedbackContext: buildContext() || undefined }),
      });
      const data = (await res.json()) as CompatibilityScanResult;
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));
      addMessage({ role: 'assistant-scan', result: data, days } as Omit<Message, 'id'>);
    } catch {
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));
      addMessage({ role: 'assistant', text: 'Deu erro de conexão com o backend. Tenta de novo?' } as Omit<Message, 'id'>);
    }
  };

  const runResumo = async () => {
    addMessage({ role: 'assistant-loading' } as Omit<Message, 'id'>);
    try {
      const res = await fetch('/api/jobs/stats');
      const s = await res.json();
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));
      const aplicadasLiquido = Math.max(0, (s.aplicadas ?? 0) - (s.recusadas ?? 0));
      addMessage({
        role: 'assistant',
        text: `📊 Aqui vai: **${s.total}** vagas no total, **${s.novas}** ainda não vistas, **${s.hojeCount}** publicadas nas últimas 24h. Do seu funil: **${s.interessadas}** com interesse marcado, **${aplicadasLiquido}** aplicadas ativas, **${s.emAndamento}** em processo, **${s.recusadas}** recusadas.`,
      } as Omit<Message, 'id'>);
    } catch {
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));
      addMessage({ role: 'assistant', text: 'Deu erro buscando as estatísticas. Tenta de novo?' } as Omit<Message, 'id'>);
    }
  };

  // Roteamento simples por palavra-chave — o Jarvis é um assistente
  // "guiado" com ações prontas, não um chat livre com function-calling;
  // isso mantém previsível o que ele faz (e quanto gasta de cota de IA).
  const handleSend = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;
    addMessage({ role: 'user', text: trimmed } as Omit<Message, 'id'>);
    setInput('');
    setBusy(true);

    const lower = trimmed.toLowerCase();
    try {
      if (lower.includes('compat')) {
        const days = lower.includes('semana') || lower.includes('7 dia') ? 7 : 1;
        await runCompatibilityScan(days);
      } else if (lower.includes('resumo') || lower.includes('estatística') || lower.includes('estatistica') || lower.includes('status')) {
        await runResumo();
      } else {
        addMessage({
          role: 'assistant',
          text: 'Não entendi 🤔 — hoje eu sei fazer:\n\n🎯 Comparar seu perfil com vagas recentes ("compatibilidade com vagas de hoje/da semana")\n📊 Resumo rápido do seu funil ("resumo rápido")',
        } as Omit<Message, 'id'>);
      }
    } finally {
      setBusy(false);
    }
  };

  return createPortal(
    <div className="jarvis-panel">
        <div className="jarvis-header">
          <span className="jarvis-header-title">🤖 Jarvis</span>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <div className="jarvis-messages" ref={scrollRef}>
          {messages.map(m => {
            if (m.role === 'user') {
              return <div key={m.id} className="jarvis-bubble jarvis-bubble--user">{m.text}</div>;
            }
            if (m.role === 'assistant-loading') {
              return <div key={m.id} className="jarvis-bubble jarvis-bubble--assistant jarvis-bubble--loading">🤖 Pensando...</div>;
            }
            if (m.role === 'assistant-scan') {
              const { result, days } = m;
              return (
                <div key={m.id} className="jarvis-bubble jarvis-bubble--assistant">
                  {!result.available ? (
                    <p>{result.errorMessage}</p>
                  ) : result.totalConsiderados === 0 ? (
                    <p>Não achei vagas publicadas {days === 1 ? 'nas últimas 24h' : `nos últimos ${days} dias`}. Tenta um período maior?</p>
                  ) : result.hits.length === 0 ? (
                    <p>
                      Olhei {result.totalConsiderados} vaga{result.totalConsiderados === 1 ? '' : 's'} do período, mas nenhuma teve
                      nem uma tecnologia em comum com seu perfil salvo — {result.errorMessage ?? 'sem match óbvio dessa vez.'}
                    </p>
                  ) : (
                    <>
                      <p className="jarvis-scan-intro">
                        Olhei {result.totalConsiderados} vaga{result.totalConsiderados === 1 ? '' : 's'} do período e analisei as{' '}
                        {result.totalAnalisadosPorIa} mais parecidas com seu perfil de verdade:
                      </p>
                      <div className="jarvis-hits">
                        {result.hits.map(h => (
                          <div key={h.job.id} className="jarvis-hit">
                            <div className="jarvis-hit-head">
                              <span className="jarvis-hit-score" style={{ color: scoreColor(h.score), borderColor: scoreColor(h.score) }}>
                                {h.score}%
                              </span>
                              <div className="jarvis-hit-title">
                                <a href={h.job.url} target="_blank" rel="noopener noreferrer">{h.job.title}</a>
                                <span className="jarvis-hit-company">{h.job.company}</span>
                              </div>
                            </div>
                            <p className="jarvis-hit-resumo">{h.resumo}</p>
                          </div>
                        ))}
                      </div>
                      {result.errorMessage && <p className="jarvis-scan-warning">⚠️ {result.errorMessage}</p>}
                    </>
                  )}
                </div>
              );
            }
            return <div key={m.id} className="jarvis-bubble jarvis-bubble--assistant">{m.text}</div>;
          })}
        </div>

        <div className="jarvis-suggestions">
          {SUGESTOES.map(s => (
            <button key={s.text} className="jarvis-suggestion-chip" onClick={() => handleSend(s.text)} disabled={busy}>
              {s.label}
            </button>
          ))}
        </div>

        <form
          className="jarvis-input-row"
          onSubmit={e => { e.preventDefault(); handleSend(input); }}
        >
          <input
            className="agenda-input jarvis-input"
            value={input}
            onChange={e => setInput(e.target.value)}
            placeholder="Pergunte algo pro Jarvis..."
            disabled={busy}
          />
          <button type="submit" className="btn btn-ai" disabled={busy || !input.trim()}>Enviar</button>
        </form>
      </div>,
      document.body
  );
}

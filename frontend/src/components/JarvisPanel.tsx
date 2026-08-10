import { useEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  JarvisChatResponse,
  JarvisCompatibilidadeData,
  JarvisListarVagasData,
  JarvisResumoFunilData,
  JarvisToolResult,
} from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';

interface Props {
  onClose: () => void;
}

type Message =
  | { id: number; role: 'user'; text: string }
  | { id: number; role: 'assistant'; text: string; toolResults?: JarvisToolResult[] }
  | { id: number; role: 'assistant-loading' };

const SUGESTOES = [
  'Compatibilidade com vagas de hoje',
  'Dê uma olhada nas minhas vagas em andamento',
  'Resumo rápido do meu funil',
];

const STATUS_META: Record<string, { label: string; color: string }> = {
  NOVA: { label: 'Nova', color: 'var(--accent)' },
  VISTA: { label: 'Vista', color: 'var(--text-muted)' },
  INTERESSADO: { label: 'Interesse', color: 'var(--yellow)' },
  APLICADA: { label: 'Aplicada', color: 'var(--green)' },
  ANDAMENTO: { label: 'Em andamento', color: 'var(--green)' },
  RECUSADA: { label: 'Recusada', color: 'var(--red)' },
};

function scoreColor(score: number): string {
  if (score >= 70) return 'var(--green)';
  if (score >= 40) return 'var(--yellow)';
  return 'var(--red)';
}

// Histórico persistido no navegador — antes o painel perdia a conversa toda
// vez que fechava e abria de novo (o componente desmontava e o estado ia
// junto). Guarda só as últimas MAX_STORED mensagens pra não crescer sem limite.
const STORAGE_KEY = 'jobradar:jarvis-history';
const MAX_STORED = 40;

let nextId = 1;

function greeting(): Message {
  return {
    id: nextId++,
    role: 'assistant',
    text: 'Oi! Eu sou o Jarvis 🤖 — pode falar comigo do jeito que quiser, tipo "dê uma olhada nas minhas vagas em andamento" ou "quantas vagas eu tenho hoje". Eu entendo a pergunta e busco o dado real pra responder.',
  };
}

function loadInitialMessages(): Message[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [greeting()];
    const parsed = JSON.parse(raw) as Message[];
    if (!Array.isArray(parsed) || parsed.length === 0) return [greeting()];
    nextId = Math.max(...parsed.map(m => m.id)) + 1;
    return parsed;
  } catch {
    return [greeting()];
  }
}

function ListarVagasCard({ data }: { data: JarvisListarVagasData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">Não achei nenhuma vaga com esses critérios.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: meta.color, borderColor: meta.color }}>
                {meta.label}
              </span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.title}</a>
                <span className="jarvis-hit-company">{v.company}</span>
              </div>
            </div>
            {v.notes && <p className="jarvis-hit-resumo">📝 {v.notes}</p>}
          </div>
        );
      })}
    </div>
  );
}

function ResumoFunilCard({ data }: { data: JarvisResumoFunilData }) {
  const itens: [string, number][] = [
    ['Total', data.total],
    ['Novas', data.novas],
    ['Interesse', data.interessadas],
    ['Aplicadas', data.aplicadas],
    ['Em andamento', data.emAndamento],
    ['Recusadas', data.recusadas],
  ];
  return (
    <div className="jarvis-stats-grid">
      {itens.map(([label, value]) => (
        <div key={label} className="jarvis-stat">
          <span className="jarvis-stat-value">{value}</span>
          <span className="jarvis-stat-label">{label}</span>
        </div>
      ))}
    </div>
  );
}

function CompatibilidadeCard({ data }: { data: JarvisCompatibilidadeData }) {
  if (!data.available) {
    return <p>{data.erro ?? 'Recurso de IA indisponível no momento.'}</p>;
  }
  if (data.hits.length === 0) {
    return (
      <p>
        Olhei {data.totalConsiderados} vaga{data.totalConsiderados === 1 ? '' : 's'} do período, mas nenhuma teve
        match — {data.erro ?? 'sem compatibilidade óbvia dessa vez.'}
      </p>
    );
  }
  return (
    <>
      <p className="jarvis-scan-intro">
        Olhei {data.totalConsiderados} vaga{data.totalConsiderados === 1 ? '' : 's'} do período e analisei as{' '}
        {data.totalAnalisadosPorIa} mais parecidas com seu perfil de verdade:
      </p>
      <div className="jarvis-hits">
        {data.hits.map(h => (
          <div key={h.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: scoreColor(h.score), borderColor: scoreColor(h.score) }}>
                {h.score}%
              </span>
              <div className="jarvis-hit-title">
                <a href={h.url} target="_blank" rel="noopener noreferrer">{h.titulo}</a>
                <span className="jarvis-hit-company">{h.empresa}</span>
              </div>
            </div>
            <p className="jarvis-hit-resumo">{h.resumo}</p>
          </div>
        ))}
      </div>
      {data.erro && <p className="jarvis-scan-warning">⚠️ {data.erro}</p>}
    </>
  );
}

function ToolResultCard({ result }: { result: JarvisToolResult }) {
  switch (result.tool) {
    case 'listarVagas':
      return <ListarVagasCard data={result.data as JarvisListarVagasData} />;
    case 'resumoFunil':
      return <ResumoFunilCard data={result.data as JarvisResumoFunilData} />;
    case 'compatibilidadeComVagasRecentes':
      return <CompatibilidadeCard data={result.data as JarvisCompatibilidadeData} />;
    default:
      return null;
  }
}

export function JarvisPanel({ onClose }: Props) {
  const { profile } = useCandidateProfile();
  const [messages, setMessages] = useState<Message[]>(loadInitialMessages);
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
  }, [messages]);

  useEffect(() => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(messages.slice(-MAX_STORED)));
  }, [messages]);

  const addMessage = (m: Omit<Message, 'id'>) => setMessages(prev => [...prev, { ...m, id: nextId++ } as Message]);

  const handleClear = () => {
    setMessages([greeting()]);
  };

  // Chat livre com function-calling de verdade: manda o histórico da
  // conversa inteiro pro backend, e é o próprio Gemini que decide se e quais
  // ferramentas chamar (listarVagas, resumoFunil, compatibilidadeComVagasRecentes)
  // a partir da linguagem natural — sem roteamento por palavra-chave aqui.
  const handleSend = async (text: string) => {
    const trimmed = text.trim();
    if (!trimmed || busy) return;

    const historicoAnterior = messages
      .filter((m): m is Extract<Message, { role: 'user' | 'assistant' }> => m.role === 'user' || m.role === 'assistant')
      .map(m => ({ role: m.role, text: m.text }));
    const history = [...historicoAnterior, { role: 'user' as const, text: trimmed }];

    addMessage({ role: 'user', text: trimmed } as Omit<Message, 'id'>);
    setInput('');
    setBusy(true);
    addMessage({ role: 'assistant-loading' } as Omit<Message, 'id'>);

    try {
      const res = await fetch('/api/jobs/assistant/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ history, candidateProfile: profile }),
      });
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));

      if (!res.ok) {
        const err = await res.json().catch(() => null);
        addMessage({
          role: 'assistant',
          text: err?.error ?? 'Deu erro falando com o Jarvis. Tenta de novo?',
        } as Omit<Message, 'id'>);
        return;
      }

      const data = (await res.json()) as JarvisChatResponse;
      addMessage({
        role: 'assistant',
        text: data.reply ?? 'Não consegui gerar uma resposta dessa vez — tenta reformular?',
        toolResults: data.toolResults,
      } as Omit<Message, 'id'>);
    } catch {
      setMessages(prev => prev.filter(m => m.role !== 'assistant-loading'));
      addMessage({ role: 'assistant', text: 'Deu erro de conexão com o backend. Tenta de novo?' } as Omit<Message, 'id'>);
    } finally {
      setBusy(false);
    }
  };

  return createPortal(
    <div className="jarvis-panel">
        <div className="jarvis-header">
          <span className="jarvis-header-title">🤖 Jarvis</span>
          <div className="jarvis-header-actions">
            <button className="jarvis-clear-btn" onClick={handleClear} title="Limpar conversa">🗑</button>
            <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
          </div>
        </div>

        <div className="jarvis-messages" ref={scrollRef}>
          {messages.map(m => {
            if (m.role === 'user') {
              return <div key={m.id} className="jarvis-bubble jarvis-bubble--user">{m.text}</div>;
            }
            if (m.role === 'assistant-loading') {
              return <div key={m.id} className="jarvis-bubble jarvis-bubble--assistant jarvis-bubble--loading">🤖 Pensando...</div>;
            }
            return (
              <div key={m.id} className="jarvis-bubble jarvis-bubble--assistant">
                {m.toolResults && m.toolResults.length > 0 && (
                  <div className="jarvis-tool-results">
                    {m.toolResults.map((tr, i) => <ToolResultCard key={i} result={tr} />)}
                  </div>
                )}
                {m.text}
              </div>
            );
          })}
        </div>

        <div className="jarvis-suggestions">
          {SUGESTOES.map(s => (
            <button key={s} className="jarvis-suggestion-chip" onClick={() => handleSend(s)} disabled={busy}>
              {s}
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

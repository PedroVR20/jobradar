import { ReactNode, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  JarvisChatResponse,
  JarvisCompatibilidadeData,
  JarvisCompatibilidadeHit,
  JarvisListarVagasData,
  JarvisResumoFunilData,
  JarvisSalarioData,
  JarvisSalarioVaga,
  JarvisToolResult,
  LearningPlan,
} from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { AiFeedbackBox } from './AiFeedbackBox';
import { HunterIcon } from './HunterIcon';

interface Props {
  onClose: () => void;
}

type Message =
  | { id: number; role: 'user'; text: string; imageDataUrl?: string }
  | { id: number; role: 'assistant'; text: string; toolResults?: JarvisToolResult[] }
  | { id: number; role: 'assistant-loading' };

// Imagem anexada/colada no chat, já convertida — dataUrl é só pra pré-visualizar
// e reexibir na bolha enviada; base64/mimeType é o que de fato vai pro backend.
interface AttachedImage {
  dataUrl: string;
  mimeType: string;
  base64: string;
}

interface Conversation {
  id: string;
  title: string;
  messages: Message[];
  updatedAt: number;
}

const SUGESTOES = [
  { icon: '🎯', text: 'Compatibilidade com vagas de hoje' },
  { icon: '🔄', text: 'Dê uma olhada nas minhas vagas em andamento' },
  { icon: '📊', text: 'Resumo rápido do meu funil' },
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

function formatBRL(valor: number): string {
  return valor.toLocaleString('pt-BR', { style: 'currency', currency: 'BRL', maximumFractionDigits: 0 });
}

// Print de tela raramente passa de 1-2MB — 6MB de folga cobre até screenshot
// de monitor 4K sem comprimir, e ainda fica bem abaixo do limite de POST do
// backend (12MB, já contando a expansão de ~33% do base64).
const MAX_ATTACH_BYTES = 6 * 1024 * 1024;
const ACCEPTED_IMAGE_TYPES = ['image/png', 'image/jpeg', 'image/webp', 'image/gif'];

class AttachError extends Error {}

// FileReader.readAsDataURL devolve "data:image/png;base64,AAAA..." — separa
// o prefixo (guardado à parte só pra pré-visualização) do miolo em base64
// puro que de fato vai pro backend (Gemini espera só os bytes, sem o prefixo).
function readFileAsAttachedImage(file: File): Promise<AttachedImage> {
  if (!ACCEPTED_IMAGE_TYPES.includes(file.type)) {
    return Promise.reject(new AttachError('Só imagens PNG, JPEG, WEBP ou GIF — esse arquivo é ' + (file.type || 'de tipo desconhecido') + '.'));
  }
  if (file.size > MAX_ATTACH_BYTES) {
    return Promise.reject(new AttachError(`Imagem muito grande (${(file.size / 1024 / 1024).toFixed(1)}MB) — o limite é ${MAX_ATTACH_BYTES / 1024 / 1024}MB.`));
  }
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new AttachError('Não consegui ler essa imagem.'));
    reader.onload = () => {
      const dataUrl = reader.result as string;
      const comma = dataUrl.indexOf(',');
      if (comma === -1) { reject(new AttachError('Não consegui ler essa imagem.')); return; }
      resolve({ dataUrl, mimeType: file.type, base64: dataUrl.slice(comma + 1) });
    };
    reader.readAsDataURL(file);
  });
}

// ===================== Markdown "lite" =====================
// A instrução de sistema pede pro Gemini não usar títulos/separadores num
// chat, mas ele nem sempre obedece à risca, e mesmo **negrito**/listas
// simples (que a instrução permite) precisam ser interpretados, senão
// aparecem como asteriscos crus na tela. Sem trazer uma lib de markdown pro
// bundle — só cobre o que o Jarvis realmente usa: **negrito**, [link](url),
// listas com "-"/"*"/"1.", e títulos "#" (caso escape a instrução).
function renderInline(text: string, keyPrefix: string): ReactNode[] {
  const nodes: ReactNode[] = [];
  const re = /\*\*(.+?)\*\*|\[([^\]]+)\]\(([^)]+)\)/g;
  let last = 0;
  let match: RegExpExecArray | null;
  let i = 0;
  while ((match = re.exec(text))) {
    if (match.index > last) nodes.push(text.slice(last, match.index));
    if (match[1] !== undefined) {
      nodes.push(<strong key={`${keyPrefix}-b${i++}`}>{match[1]}</strong>);
    } else {
      nodes.push(
        <a key={`${keyPrefix}-l${i++}`} href={match[3]} target="_blank" rel="noopener noreferrer">{match[2]}</a>
      );
    }
    last = re.lastIndex;
  }
  if (last < text.length) nodes.push(text.slice(last));
  return nodes;
}

function renderMarkdownLite(text: string): ReactNode {
  const lines = text.split('\n');
  const blocks: ReactNode[] = [];
  let listItems: string[] = [];
  let listOrdered = false;
  let paraLines: string[] = [];
  let key = 0;

  const flushList = () => {
    if (listItems.length === 0) return;
    const items = listItems;
    const ordered = listOrdered;
    const ListTag = ordered ? 'ol' : 'ul';
    blocks.push(
      <ListTag className="jarvis-md-list" key={`list-${key++}`}>
        {items.map((li, i) => <li key={i}>{renderInline(li, `li${key}-${i}`)}</li>)}
      </ListTag>
    );
    listItems = [];
  };
  const flushPara = () => {
    if (paraLines.length === 0) return;
    const joined = paraLines.join(' ');
    blocks.push(<p className="jarvis-md-p" key={`p-${key++}`}>{renderInline(joined, `p${key}`)}</p>);
    paraLines = [];
  };

  for (const rawLine of lines) {
    const line = rawLine.trim();
    if (line === '') { flushList(); flushPara(); continue; }
    if (line === '---' || line === '***') { flushList(); flushPara(); continue; }

    const headerMatch = /^#{1,4}\s+(.*)$/.exec(line);
    if (headerMatch) {
      flushList(); flushPara();
      blocks.push(<p className="jarvis-md-heading" key={`h-${key++}`}>{renderInline(headerMatch[1], `h${key}`)}</p>);
      continue;
    }

    const orderedMatch = /^\d+[.)]\s+(.*)$/.exec(line);
    const bulletMatch = /^[-*]\s+(.*)$/.exec(line);
    if (orderedMatch || bulletMatch) {
      flushPara();
      const isOrdered = !!orderedMatch;
      if (listItems.length > 0 && listOrdered !== isOrdered) flushList();
      listOrdered = isOrdered;
      listItems.push((orderedMatch ?? bulletMatch)![1]);
      continue;
    }

    flushList();
    paraLines.push(rawLine);
  }
  flushList();
  flushPara();

  return <>{blocks}</>;
}

// ===================== Persistência: múltiplas conversas =====================
// Antes era uma conversa só (localStorage 'jobradar:jarvis-history'). Agora
// guarda várias, como o histórico lateral do Claude Desktop: dá pra abrir
// uma pergunta antiga sem perder a de agora.
const CONVERSATIONS_KEY = 'jobradar:jarvis-conversations';
const ACTIVE_KEY = 'jobradar:jarvis-active-conversation';
const LEGACY_KEY = 'jobradar:jarvis-history';
const MAX_CONVERSATIONS = 30;
const MAX_STORED_PER_CONVO = 40;
const TITLE_MAX_LEN = 48;

// Largura do painel — arrastável pela borda esquerda (ver .jarvis-resize-handle),
// lembrada entre sessões. min/max evitam um painel inutilizável (muito
// estreito pra ler, ou tomando a tela toda num desktop).
const WIDTH_KEY = 'jobradar:jarvis-width';
const DEFAULT_WIDTH = 400;
const MIN_WIDTH = 320;
const MAX_WIDTH = 800;
const CSS_VAR = '--jarvis-panel-width';

function clampWidth(w: number): number {
  return Math.min(MAX_WIDTH, Math.max(MIN_WIDTH, w));
}

function loadWidth(): number {
  const raw = localStorage.getItem(WIDTH_KEY);
  const parsed = raw ? parseInt(raw, 10) : DEFAULT_WIDTH;
  return Number.isFinite(parsed) ? clampWidth(parsed) : DEFAULT_WIDTH;
}

let nextId = 1;

function generateId(): string {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) return crypto.randomUUID();
  return `${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

function greeting(): Message {
  return {
    id: nextId++,
    role: 'assistant',
    text: 'Oi! Eu sou o Hunter — pode falar comigo do jeito que quiser, tipo "dê uma olhada nas minhas vagas em andamento" ou "quantas vagas eu tenho hoje". Eu entendo a pergunta e busco o dado real pra responder.',
  };
}

function deriveTitle(messages: Message[]): string {
  const firstUser = messages.find((m): m is Extract<Message, { role: 'user' }> => m.role === 'user');
  if (!firstUser) return 'Nova conversa';
  const t = firstUser.text.trim();
  return t.length > TITLE_MAX_LEN ? `${t.slice(0, TITLE_MAX_LEN)}…` : t;
}

function freshConversation(): Conversation {
  return { id: generateId(), title: 'Nova conversa', messages: [greeting()], updatedAt: Date.now() };
}

function computeNextId(conversations: Conversation[]): number {
  let max = 0;
  for (const c of conversations) for (const m of c.messages) if (m.id > max) max = m.id;
  return max + 1;
}

// Conversas salvas antes da troca de nome (primeiro "Jarvis", depois "Rovi",
// agora "Hunter") têm mensagens do próprio assistente com o nome antigo
// gravado no texto — a saudação, mensagens de erro, ou uma resposta onde ele
// se apresentou. Só corrige mensagens DO assistente (nunca mexe no que o
// usuário escreveu) trocando o nome como palavra inteira.
function migrateAssistantText(text: string): string {
  return text.replace(/\bRovi\b/g, 'Hunter').replace(/\bJarvis\b/g, 'Hunter');
}

function migrateConversations(conversations: Conversation[]): Conversation[] {
  return conversations.map(c => ({
    ...c,
    messages: c.messages.map(m => m.role === 'assistant' ? { ...m, text: migrateAssistantText(m.text) } : m),
  }));
}

function loadConversations(): Conversation[] {
  try {
    const raw = localStorage.getItem(CONVERSATIONS_KEY);
    if (raw) {
      const parsed = JSON.parse(raw) as Conversation[];
      if (Array.isArray(parsed) && parsed.length > 0) {
        nextId = computeNextId(parsed);
        return migrateConversations(parsed);
      }
    }
  } catch { /* ignore, cai pro fallback abaixo */ }

  // Migração de sessões antigas (uma conversa só, sem histórico de verdade)
  try {
    const legacyRaw = localStorage.getItem(LEGACY_KEY);
    if (legacyRaw) {
      const legacyMessages = JSON.parse(legacyRaw) as Message[];
      localStorage.removeItem(LEGACY_KEY);
      if (Array.isArray(legacyMessages) && legacyMessages.length > 0) {
        nextId = computeNextId([{ id: '', title: '', messages: legacyMessages, updatedAt: 0 }]);
        const migratedMessages = legacyMessages.map(m => m.role === 'assistant' ? { ...m, text: migrateAssistantText(m.text) } : m);
        return [{ id: generateId(), title: deriveTitle(migratedMessages), messages: migratedMessages, updatedAt: Date.now() }];
      }
    }
  } catch { /* ignore */ }

  return [freshConversation()];
}

function loadActiveId(): string {
  const conversations = loadConversations();
  try {
    const raw = localStorage.getItem(ACTIVE_KEY);
    if (raw && conversations.some(c => c.id === raw)) return raw;
  } catch { /* ignore */ }
  return [...conversations].sort((a, b) => b.updatedAt - a.updatedAt)[0].id;
}

function relativeTime(ts: number): string {
  const diffMin = Math.floor((Date.now() - ts) / 60000);
  if (diffMin < 1) return 'agora';
  if (diffMin < 60) return `há ${diffMin}min`;
  const h = Math.floor(diffMin / 60);
  if (h < 24) return `há ${h}h`;
  const d = Math.floor(h / 24);
  if (d === 1) return 'ontem';
  if (d < 7) return `há ${d}d`;
  return new Date(ts).toLocaleDateString('pt-BR', { day: '2-digit', month: '2-digit' });
}

// ===================== Cards de resultado de ferramenta =====================

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

// "Dashboard" com a visão geral antes dos cards individuais — pedido depois
// de um resultado só em texto/cards: média, quantas em cada faixa, e uma
// barra por vaga ordenada da maior compatibilidade pra menor, pra comparar
// todas de relance sem precisar ler card por card. Só aparece com 2+ vagas
// (com uma só, "média" e "distribuição" não dizem nada de útil).
function CompatDashboard({ hits }: { hits: JarvisCompatibilidadeHit[] }) {
  if (hits.length < 2) return null;
  const media = Math.round(hits.reduce((soma, h) => soma + h.score, 0) / hits.length);
  const altas = hits.filter(h => h.score >= 70).length;
  const medias = hits.filter(h => h.score >= 40 && h.score < 70).length;
  const baixas = hits.filter(h => h.score < 40).length;
  const ordenados = [...hits].sort((a, b) => b.score - a.score);

  return (
    <div className="compat-dashboard">
      <div className="compat-stats-grid">
        <div className="jarvis-stat">
          <span className="jarvis-stat-value">{media}%</span>
          <span className="jarvis-stat-label">Média</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value" style={{ color: 'var(--green)' }}>{altas}</span>
          <span className="jarvis-stat-label">Altas (≥70%)</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value" style={{ color: 'var(--yellow)' }}>{medias}</span>
          <span className="jarvis-stat-label">Médias (40-69%)</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value" style={{ color: 'var(--red)' }}>{baixas}</span>
          <span className="jarvis-stat-label">Baixas (&lt;40%)</span>
        </div>
      </div>
      <div className="compat-bars">
        {ordenados.map(h => (
          <div className="compat-bar-row" key={h.id}>
            <div className="compat-bar-head">
              <span className="compat-bar-label" title={h.titulo}>{h.titulo}</span>
              <span className="compat-bar-score" style={{ color: scoreColor(h.score) }}>{h.score}%</span>
            </div>
            <div className="compat-bar-track">
              <div className="compat-bar-fill" style={{ width: `${h.score}%`, background: scoreColor(h.score) }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

// Mesmo componente/endpoint que o "📚 Plano de ação" do modal de compatibilidade
// (ver GapItem em MatchScoreModal.tsx) — só troca `job: Job` por `jobId:
// number` porque aqui só temos o id/título/empresa da vaga (JarvisCompatibilidadeHit),
// não o objeto Job inteiro. Usa a MESMA featureKey 'learning-plan' no
// AiFeedbackBox — o 👍/👎 dado aqui cai no mesmo pool de feedback que o
// modal já usa, então o que o usuário avalia num lugar vale pro outro também.
function ChatGapItem({ jobId, gap, candidateProfile, feedbackContext }: {
  jobId: number; gap: string; candidateProfile: string; feedbackContext: string;
}) {
  const [plan, setPlan] = useState<LearningPlan | null>(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [open, setOpen] = useState(false);

  const handleGeneratePlan = async () => {
    if (plan) { setOpen(o => !o); return; }
    setLoading(true);
    setError('');
    try {
      const res = await fetch(`/api/jobs/${jobId}/learning-plan`, {
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

function CompatibilidadeCard({ data, candidateProfile, planFeedbackContext }: {
  data: JarvisCompatibilidadeData; candidateProfile: string; planFeedbackContext: string;
}) {
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
      <CompatDashboard hits={data.hits} />
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
            {!!h.pontosFortes?.length && (
              <div className="match-section">
                <h4 className="match-section-title match-section-title--good">✅ Pontos fortes</h4>
                <ul className="match-list">
                  {h.pontosFortes.map((p, i) => <li key={i}>{p}</li>)}
                </ul>
              </div>
            )}
            {!!h.pontosFaltando?.length && (
              <div className="match-section">
                <h4 className="match-section-title match-section-title--gap">⚠️ Pontos a desenvolver</h4>
                <ul className="match-list">
                  {h.pontosFaltando.map((p, i) => (
                    <ChatGapItem key={i} jobId={h.id} gap={p} candidateProfile={candidateProfile} feedbackContext={planFeedbackContext} />
                  ))}
                </ul>
              </div>
            )}
          </div>
        ))}
      </div>
      {data.erro && <p className="jarvis-scan-warning">⚠️ {data.erro}</p>}
    </>
  );
}

// Mesmo padrão visual do CompatDashboard (cards de estatística + barras),
// agora pra estimativa salarial — a interface monta esse resumo sozinha a
// partir de QUALQUER ferramenta que devolva um número comparável por vaga,
// não é algo específico de compatibilidade (ver SYSTEM_INSTRUCTION no backend).
function SalarioDashboard({ vagas }: { vagas: JarvisSalarioVaga[] }) {
  const comEstimativa = vagas.filter((v): v is JarvisSalarioVaga & { estimativa: number } => v.estimativa != null);
  if (comEstimativa.length < 2) return null;

  const valores = comEstimativa.map(v => v.estimativa);
  const media = Math.round(valores.reduce((soma, v) => soma + v, 0) / valores.length);
  const maior = Math.max(...valores);
  const menor = Math.min(...valores);
  const ordenados = [...comEstimativa].sort((a, b) => b.estimativa - a.estimativa);

  return (
    <div className="compat-dashboard">
      <div className="compat-stats-grid">
        <div className="jarvis-stat">
          <span className="jarvis-stat-value">{formatBRL(media)}</span>
          <span className="jarvis-stat-label">Média</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value" style={{ color: 'var(--green)' }}>{formatBRL(maior)}</span>
          <span className="jarvis-stat-label">Maior</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value" style={{ color: 'var(--yellow)' }}>{formatBRL(menor)}</span>
          <span className="jarvis-stat-label">Menor</span>
        </div>
        <div className="jarvis-stat">
          <span className="jarvis-stat-value">{comEstimativa.length}/{vagas.length}</span>
          <span className="jarvis-stat-label">Com estimativa</span>
        </div>
      </div>
      <div className="compat-bars">
        {ordenados.map(v => (
          <div className="compat-bar-row" key={v.id}>
            <div className="compat-bar-head">
              <span className="compat-bar-label" title={v.titulo}>{v.titulo}</span>
              <span className="compat-bar-score">{formatBRL(v.estimativa)}</span>
            </div>
            <div className="compat-bar-track">
              <div className="compat-bar-fill" style={{ width: `${Math.max(4, (v.estimativa / maior) * 100)}%`, background: 'var(--accent)' }} />
            </div>
          </div>
        ))}
      </div>
    </div>
  );
}

function SalarioCard({ data }: { data: JarvisSalarioData }) {
  if (!data.modeloDisponivel) {
    return <p>Modelo de estimativa salarial ainda não foi treinado — retreine em ⚙️ Configurações.</p>;
  }
  if (data.vagas.length === 0) {
    return <p>Não achei nenhuma vaga nesse filtro pra estimar.</p>;
  }
  return (
    <>
      <p className="jarvis-scan-intro">
        Estimei o salário de {data.vagas.length} vaga{data.vagas.length === 1 ? '' : 's'}
        {data.totalEncontradas > data.vagas.length ? ` (de ${data.totalEncontradas} encontradas)` : ''}:
      </p>
      <SalarioDashboard vagas={data.vagas} />
      <div className="jarvis-hits">
        {data.vagas.map(v => (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: 'var(--accent)', borderColor: 'var(--accent)' }}>
                {v.estimativa != null ? formatBRL(v.estimativa) : '—'}
              </span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
            {v.salarioInformado && <p className="jarvis-hit-resumo">Salário informado na vaga: {v.salarioInformado}</p>}
          </div>
        ))}
      </div>
    </>
  );
}

function ToolResultCard({ result, candidateProfile, planFeedbackContext }: {
  result: JarvisToolResult; candidateProfile: string; planFeedbackContext: string;
}) {
  switch (result.tool) {
    case 'listarVagas':
      return <ListarVagasCard data={result.data as JarvisListarVagasData} />;
    case 'resumoFunil':
      return <ResumoFunilCard data={result.data as JarvisResumoFunilData} />;
    case 'compatibilidadeComVagasRecentes':
    case 'compatibilidadeComVagasDoFunil':
      return (
        <CompatibilidadeCard
          data={result.data as JarvisCompatibilidadeData}
          candidateProfile={candidateProfile}
          planFeedbackContext={planFeedbackContext}
        />
      );
    case 'estimativaSalarialDeVagas':
      return <SalarioCard data={result.data as JarvisSalarioData} />;
    default:
      return null;
  }
}

// ===================== Histórico de conversas =====================

function HistoryView({
  conversations, activeId, onSelect, onDelete,
}: {
  conversations: Conversation[];
  activeId: string;
  onSelect: (id: string) => void;
  onDelete: (id: string, e: React.MouseEvent) => void;
}) {
  const ordered = [...conversations].sort((a, b) => b.updatedAt - a.updatedAt);
  return (
    <div className="jarvis-history-list">
      {ordered.map(c => (
        <button
          key={c.id}
          className={`jarvis-history-item ${c.id === activeId ? 'jarvis-history-item--active' : ''}`}
          onClick={() => onSelect(c.id)}
        >
          <div className="jarvis-history-item-main">
            <span className="jarvis-history-item-title">{c.title}</span>
            <span className="jarvis-history-item-time">{relativeTime(c.updatedAt)}</span>
          </div>
          <span
            className="jarvis-history-item-delete"
            role="button"
            aria-label="Apagar conversa"
            onClick={e => onDelete(c.id, e)}
          >
            🗑
          </span>
        </button>
      ))}
    </div>
  );
}

export function JarvisPanel({ onClose }: Props) {
  const { profile } = useCandidateProfile();
  // Mesmas featureKeys que MatchScoreModal.tsx usa pros cards de vaga — o
  // 👍/👎 dado ali OU aqui no chat cai no mesmo pool salvo em localStorage,
  // então o feedback vale nos dois lugares (antes o chat não tinha acesso
  // nenhum a esse histórico).
  const { buildContext: buildMatchFeedbackContext } = useAiFeedback('match-score');
  const { buildContext: buildPlanFeedbackContext } = useAiFeedback('learning-plan');
  const [conversations, setConversations] = useState<Conversation[]>(loadConversations);
  const [activeId, setActiveId] = useState<string>(loadActiveId);
  const [view, setView] = useState<'chat' | 'history'>('chat');
  const [input, setInput] = useState('');
  const [busy, setBusy] = useState(false);
  const [closing, setClosing] = useState(false);
  const [width, setWidth] = useState(loadWidth);
  const [resizing, setResizing] = useState(false);
  // Recall de mensagens anteriores tipo terminal: seta ↑ traz a última
  // mensagem enviada de volta pro campo (útil quando o Hunter erra numa
  // mensagem enorme e ela já não tá mais no clipboard) e ↓ vai voltando.
  const [historyNav, setHistoryNav] = useState<{ index: number; draft: string } | null>(null);
  const [attachedImage, setAttachedImage] = useState<AttachedImage | null>(null);
  const [attachError, setAttachError] = useState('');
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);

  // loadConversations() e loadActiveId() são inicializadores independentes
  // do useState (cada um roda separado) — quando o localStorage começa
  // vazio, os dois chamam freshConversation() por conta própria e geram
  // UUIDs diferentes, então o activeId "bruto" pode não bater com nenhuma
  // conversa de verdade no primeiro render. Por isso toda lógica interna usa
  // resolvedActiveId (o id que realmente existe, com fallback pra primeira
  // conversa), nunca o activeId bruto — senão os writes silenciosamente não
  // encontram a conversa e a mensagem some.
  const active = conversations.find(c => c.id === activeId) ?? conversations[0];
  const resolvedActiveId = active.id;
  const messages = active.messages;

  useEffect(() => {
    if (view === 'chat') {
      scrollRef.current?.scrollTo({ top: scrollRef.current.scrollHeight, behavior: 'smooth' });
    }
  }, [messages, view]);

  // Troca de conversa invalida o índice do recall — cada conversa tem sua
  // própria lista de mensagens do usuário.
  useEffect(() => {
    setHistoryNav(null);
  }, [resolvedActiveId]);

  // A variável fica no <html> (não teria como setar via className, já que
  // .jarvis-panel é portal pro body e .app-main é uma div separada lá no
  // App.tsx) — os dois lêem a mesma var, então empurram/encolhem juntos.
  // useLayoutEffect (não useEffect) pra aplicar antes do primeiro paint —
  // sem isso, abrir o painel com uma largura salva diferente de 400px
  // piscaria o default por um frame antes de corrigir.
  useLayoutEffect(() => {
    document.documentElement.style.setProperty(CSS_VAR, `${width}px`);
    return () => {
      document.documentElement.style.removeProperty(CSS_VAR);
    };
  }, [width]);

  // Arrasta a borda esquerda do painel — o conteúdo principal (app-main)
  // acompanha em tempo real porque lê a MESMA variável CSS, atualizada aqui
  // a cada movimento do ponteiro, sem esperar o React re-renderizar.
  const handleResizeStart = (e: React.PointerEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startWidth = width;
    setResizing(true);
    document.documentElement.classList.add('jarvis-resizing');

    const handleMove = (ev: PointerEvent) => {
      const deltaX = startX - ev.clientX; // arrastar pra esquerda cresce o painel
      const novaLargura = clampWidth(startWidth + deltaX);
      document.documentElement.style.setProperty(CSS_VAR, `${novaLargura}px`);
      setWidth(novaLargura);
    };
    const handleUp = () => {
      window.removeEventListener('pointermove', handleMove);
      window.removeEventListener('pointerup', handleUp);
      document.documentElement.classList.remove('jarvis-resizing');
      setResizing(false);
      setWidth(w => {
        localStorage.setItem(WIDTH_KEY, String(w));
        return w;
      });
    };
    window.addEventListener('pointermove', handleMove);
    window.addEventListener('pointerup', handleUp);
  };

  useEffect(() => {
    const capped = [...conversations]
      .sort((a, b) => b.updatedAt - a.updatedAt)
      .slice(0, MAX_CONVERSATIONS)
      .map(c => ({ ...c, messages: c.messages.slice(-MAX_STORED_PER_CONVO) }));
    localStorage.setItem(CONVERSATIONS_KEY, JSON.stringify(capped));
  }, [conversations]);

  useEffect(() => {
    localStorage.setItem(ACTIVE_KEY, resolvedActiveId);
  }, [resolvedActiveId]);

  const patchActive = (fn: (msgs: Message[]) => Message[]) => {
    setConversations(prev => prev.map(c => {
      if (c.id !== resolvedActiveId) return c;
      const newMessages = fn(c.messages);
      const newTitle = c.title === 'Nova conversa' ? deriveTitle(newMessages) : c.title;
      return { ...c, messages: newMessages, updatedAt: Date.now(), title: newTitle };
    }));
  };

  const addMessage = (m: Omit<Message, 'id'>) => patchActive(msgs => [...msgs, { ...m, id: nextId++ } as Message]);
  const removeLoading = () => patchActive(msgs => msgs.filter(m => m.role !== 'assistant-loading'));

  const handleNewConversation = () => {
    if (messages.length <= 1) { setView('chat'); return; } // já tá numa conversa vazia, não duplica
    const fresh = freshConversation();
    setConversations(prev => [fresh, ...prev]);
    setActiveId(fresh.id);
    setView('chat');
  };

  const handleSelectConversation = (id: string) => {
    setActiveId(id);
    setView('chat');
  };

  const handleDeleteConversation = (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    const rest = conversations.filter(c => c.id !== id);
    if (rest.length === 0) {
      const fresh = freshConversation();
      setConversations([fresh]);
      setActiveId(fresh.id);
      return;
    }
    setConversations(rest);
    if (id === resolvedActiveId) {
      setActiveId([...rest].sort((a, b) => b.updatedAt - a.updatedAt)[0].id);
    }
  };

  const handleRequestClose = () => {
    setClosing(true);
    window.setTimeout(onClose, 180); // acompanha a duração da animação de saída
  };

  // Chat livre com function-calling de verdade: manda o histórico da
  // conversa ativa inteiro pro backend, e é o próprio Gemini que decide se e
  // quais ferramentas chamar (listarVagas, resumoFunil,
  // compatibilidadeComVagasRecentes) a partir da linguagem natural.
  // `image`, se presente, vai só na mensagem mais nova (print anexado/colado
  // pelo usuário) — nunca reenviamos imagens de mensagens antigas.
  const handleSend = async (text: string, image?: AttachedImage | null) => {
    const trimmed = text.trim();
    if ((!trimmed && !image) || busy) return;

    const historicoAnterior = messages
      .filter((m): m is Extract<Message, { role: 'user' | 'assistant' }> => m.role === 'user' || m.role === 'assistant')
      .map(m => ({ role: m.role, text: m.text }));
    const history = [
      ...historicoAnterior,
      {
        role: 'user' as const,
        text: trimmed,
        ...(image ? { imageMimeType: image.mimeType, imageBase64: image.base64 } : {}),
      },
    ];

    addMessage({ role: 'user', text: trimmed, imageDataUrl: image?.dataUrl } as Omit<Message, 'id'>);
    setInput('');
    setAttachedImage(null);
    setAttachError('');
    setHistoryNav(null);
    setBusy(true);
    addMessage({ role: 'assistant-loading' } as Omit<Message, 'id'>);

    try {
      const res = await fetch('/api/jobs/assistant/chat', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ history, candidateProfile: profile, feedbackContext: buildMatchFeedbackContext() }),
      });
      removeLoading();

      if (!res.ok) {
        const err = await res.json().catch(() => null);
        addMessage({
          role: 'assistant',
          text: err?.error ?? 'Deu erro falando com o Hunter. Tenta de novo?',
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
      removeLoading();
      addMessage({ role: 'assistant', text: 'Deu erro de conexão com o backend. Tenta de novo?' } as Omit<Message, 'id'>);
    } finally {
      setBusy(false);
    }
  };

  const handleInputKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    // Enter manda a mensagem (como todo chat) — Shift+Enter quebra linha
    // normalmente. Precisa checar isComposing pra não disparar envio no meio
    // de um input assistido (IME de chinês/japonês/coreano confirmando com
    // Enter), embora raro nesse app em pt-BR.
    if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) {
      e.preventDefault();
      handleSend(input, attachedImage);
      return;
    }

    if (e.key !== 'ArrowUp' && e.key !== 'ArrowDown') return;

    const userMessages = messages.filter((m): m is Extract<Message, { role: 'user' }> => m.role === 'user');
    if (userMessages.length === 0) return;

    // Textarea agora pode ter várias linhas — só sequestra a seta quando ela
    // faria a mesma coisa num campo de uma linha só (cursor bem no início
    // pra ↑, ou já estava navegando o histórico, que sempre substitui o
    // campo inteiro então a posição do cursor deixa de importar). Sem essa
    // trava, ↑/↓ pra mover o cursor dentro de um rascunho de várias linhas
    // ficaria impossível.
    const ta = e.currentTarget;
    const cursorNoInicio = ta.selectionStart === 0 && ta.selectionEnd === 0;
    const cursorNoFim = ta.selectionStart === ta.value.length && ta.selectionEnd === ta.value.length;

    if (e.key === 'ArrowUp') {
      if (!historyNav && !cursorNoInicio) return;
      e.preventDefault();
      if (!historyNav) {
        const idx = userMessages.length - 1;
        setHistoryNav({ index: idx, draft: input });
        setInput(userMessages[idx].text);
      } else if (historyNav.index > 0) {
        const idx = historyNav.index - 1;
        setHistoryNav({ ...historyNav, index: idx });
        setInput(userMessages[idx].text);
      }
      return;
    }

    // ArrowDown
    if (!historyNav || !cursorNoFim) return;
    e.preventDefault();
    if (historyNav.index < userMessages.length - 1) {
      const idx = historyNav.index + 1;
      setHistoryNav({ ...historyNav, index: idx });
      setInput(userMessages[idx].text);
    } else {
      setInput(historyNav.draft);
      setHistoryNav(null);
    }
  };

  // Auto-grow: cresce junto com o texto (word-wrap deixa tudo visível sem
  // precisar rolar horizontalmente) até um teto, depois passa a rolar dentro
  // da própria caixa. Reseta pra 'auto' antes de medir scrollHeight, senão o
  // navegador nunca encolhe de volta ao apagar texto.
  useEffect(() => {
    const ta = textareaRef.current;
    if (!ta) return;
    ta.style.height = 'auto';
    ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`;
  }, [input]);

  const handleAttachFile = async (file: File) => {
    setAttachError('');
    try {
      const img = await readFileAsAttachedImage(file);
      setAttachedImage(img);
    } catch (e) {
      setAttachError(e instanceof AttachError ? e.message : 'Não foi possível anexar essa imagem.');
    }
  };

  const handleFileInputChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // permite anexar o mesmo arquivo de novo depois
    if (file) handleAttachFile(file);
  };

  // Print colado (Ctrl+V) é o jeito mais natural de anexar um print de vaga
  // — não precisa procurar o arquivo salvo. Só intercepta quando o clipboard
  // realmente tem uma imagem; texto colado continua funcionando normal.
  const handlePaste = (e: React.ClipboardEvent<HTMLTextAreaElement>) => {
    const item = Array.from(e.clipboardData.items).find(i => i.type.startsWith('image/'));
    if (!item) return;
    const file = item.getAsFile();
    if (!file) return;
    e.preventDefault();
    handleAttachFile(file);
  };

  return createPortal(
    <div className={`jarvis-panel ${closing ? 'jarvis-panel--closing' : ''}`}>
      <div
        className={`jarvis-resize-handle ${resizing ? 'jarvis-resize-handle--active' : ''}`}
        onPointerDown={handleResizeStart}
        title="Arraste pra redimensionar"
      />
      <div className="jarvis-header">
        <span className="jarvis-header-title">
          {view === 'history' ? '🕘 Histórico' : <><HunterIcon size={19} alive /> Hunter</>}
        </span>
        <div className="jarvis-header-actions">
          <button
            className="jarvis-header-icon-btn"
            onClick={() => setView(v => (v === 'history' ? 'chat' : 'history'))}
            title={view === 'history' ? 'Voltar pro chat' : 'Ver conversas anteriores'}
          >
            {view === 'history' ? '💬' : '🕘'}
          </button>
          <button className="jarvis-header-icon-btn" onClick={handleNewConversation} title="Nova conversa">➕</button>
          <button className="jarvis-header-icon-btn" onClick={handleRequestClose} aria-label="Fechar">✕</button>
        </div>
      </div>

      {view === 'history' ? (
        <HistoryView
          conversations={conversations}
          activeId={resolvedActiveId}
          onSelect={handleSelectConversation}
          onDelete={handleDeleteConversation}
        />
      ) : (
        <>
          <div className="jarvis-messages" ref={scrollRef}>
            {messages.map(m => {
              if (m.role === 'user') {
                return (
                  <div key={m.id} className="jarvis-bubble jarvis-bubble--user">
                    {m.imageDataUrl && <img src={m.imageDataUrl} alt="Print anexado" className="jarvis-msg-image" />}
                    {m.text}
                  </div>
                );
              }
              if (m.role === 'assistant-loading') {
                return (
                  <div key={m.id} className="jarvis-msg-row">
                    <span className="jarvis-avatar"><HunterIcon size={22} alive /></span>
                    <div className="jarvis-bubble jarvis-bubble--assistant jarvis-bubble--loading">
                      <span className="jarvis-typing"><span></span><span></span><span></span></span>
                    </div>
                  </div>
                );
              }
              return (
                <div key={m.id} className="jarvis-msg-row">
                  <span className="jarvis-avatar"><HunterIcon size={22} alive /></span>
                  <div className="jarvis-bubble jarvis-bubble--assistant">
                    {m.toolResults && m.toolResults.length > 0 && (
                      <div className="jarvis-tool-results">
                        {m.toolResults.map((tr, i) => (
                          <ToolResultCard
                            key={i}
                            result={tr}
                            candidateProfile={profile}
                            planFeedbackContext={buildPlanFeedbackContext()}
                          />
                        ))}
                      </div>
                    )}
                    {renderMarkdownLite(m.text)}
                  </div>
                </div>
              );
            })}

            {messages.length <= 1 && (
              <div className="jarvis-suggestions">
                {SUGESTOES.map(s => (
                  <button key={s.text} className="jarvis-suggestion-card" onClick={() => handleSend(s.text)} disabled={busy}>
                    <span className="jarvis-suggestion-icon">{s.icon}</span>
                    <span>{s.text}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {attachError && <p className="jarvis-attach-error">⚠️ {attachError}</p>}

          {attachedImage && (
            <div className="jarvis-attach-preview">
              <img src={attachedImage.dataUrl} alt="Print a anexar" />
              <button type="button" className="jarvis-attach-remove" onClick={() => setAttachedImage(null)} aria-label="Remover imagem">✕</button>
            </div>
          )}

          <form className="jarvis-input-row" onSubmit={e => { e.preventDefault(); handleSend(input, attachedImage); }}>
            <input
              ref={fileInputRef}
              type="file"
              accept={ACCEPTED_IMAGE_TYPES.join(',')}
              className="jarvis-file-input"
              onChange={handleFileInputChange}
            />
            <button
              type="button"
              className="jarvis-attach-btn"
              onClick={() => fileInputRef.current?.click()}
              disabled={busy}
              aria-label="Anexar print"
              title="Anexar print (ou cole com Ctrl+V no campo de texto)"
            >
              📎
            </button>
            <textarea
              ref={textareaRef}
              className="jarvis-input"
              rows={1}
              value={input}
              onChange={e => { setInput(e.target.value); setHistoryNav(null); }}
              onKeyDown={handleInputKeyDown}
              onPaste={handlePaste}
              placeholder="Pergunte algo pro Hunter... (Shift+Enter quebra linha, ↑ recupera mensagens, cole um print com Ctrl+V)"
              disabled={busy}
            />
            <button type="submit" className="jarvis-send-btn" disabled={busy || (!input.trim() && !attachedImage)} aria-label="Enviar">➤</button>
          </form>
        </>
      )}
    </div>,
    document.body
  );
}

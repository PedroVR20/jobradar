import { ReactNode, useEffect, useLayoutEffect, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import {
  JarvisAcaoVaga,
  JarvisAdicionarVagaData,
  JarvisApagarVagaData,
  JarvisAtualizarNotaData,
  JarvisBuscaSemanticaData,
  JarvisCartaData,
  JarvisChatResponse,
  JarvisCompararMercadoData,
  JarvisCompatibilidadeData,
  JarvisCompatibilidadeHit,
  JarvisDetalharVagasData,
  JarvisDuplicatasData,
  JarvisFixarVagaData,
  JarvisFontesData,
  JarvisLembrarData,
  JarvisLembreteAgendaData,
  JarvisHistoricoEmpresaData,
  JarvisListarVagasData,
  JarvisMarcarStatusData,
  JarvisMetricasData,
  JarvisOQueFazerAgoraData,
  JarvisPendingQuestion,
  JarvisPrazoData,
  JarvisResumoFunilData,
  JarvisSalarioData,
  JarvisSalarioVaga,
  JarvisToolResult,
  JarvisVagasParadasData,
  JarvisVagasParecidasData,
  LearningPlan,
} from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useAiFeedback } from '../hooks/useAiFeedback';
import { useAiStatus } from '../hooks/useAiStatus';
import { useHunterMemory } from '../hooks/useHunterMemory';
import { useAgenda } from '../hooks/useAgenda';
import { HunterIcon } from './HunterIcon';
import {
  BookIcon, ChartIcon, ChatBubbleIcon, CheckIcon, ClipIcon, CloseIcon, CompactIcon, CopyIcon,
  CycleIcon, DownloadIcon, HistoryIcon, MicIcon, NoteIcon, PencilIcon, PlusIcon, SearchIcon,
  SuccessIcon, TargetIcon, ThinkingIcon, ThumbDownIcon, ThumbUpIcon, TimerIcon, TrashIcon, WarningIcon,
} from './HunterMiniIcons';

// Preferência de "modo compacto" (esconde os cards visuais, só texto) —
// persistida à parte do resto do estado do chat, é uma preferência de
// exibição, não algo específico de uma conversa.
const COMPACT_MODE_KEY = 'jobradar:jarvis-compact-mode';

// Ditado por voz (Web Speech API) — só Chrome/Edge/derivados suportam hoje
// (window.SpeechRecognition ainda não existe no lib.dom.d.ts do TypeScript
// padrão, daí a declaração manual aqui). Nada de biblioteca externa — é uma
// API nativa do navegador, só falta o tipo.
interface SpeechRecognitionResultLike {
  isFinal: boolean;
  0: { transcript: string };
}
interface SpeechRecognitionEventLike {
  resultIndex: number;
  results: ArrayLike<SpeechRecognitionResultLike>;
}
interface SpeechRecognitionInstance extends EventTarget {
  lang: string;
  continuous: boolean;
  interimResults: boolean;
  start(): void;
  stop(): void;
  onresult: ((event: SpeechRecognitionEventLike) => void) | null;
  onerror: (() => void) | null;
  onend: (() => void) | null;
}
declare global {
  interface Window {
    SpeechRecognition?: new () => SpeechRecognitionInstance;
    webkitSpeechRecognition?: new () => SpeechRecognitionInstance;
  }
}

interface Props {
  onClose: () => void;
  // Avisa o App.tsx pra recarregar a lista de vagas quando o Hunter mudou
  // algo de verdade — sem isso o card mudado só refletiria depois de um F5,
  // mesmo a mudança já valendo no banco. vagaId (quando dá pra saber qual
  // vaga foi) liga o pulso visual nesse card específico (ver App.tsx).
  onJobsChanged?: (vagaId?: number) => void;
}

type Message =
  | { id: number; role: 'user'; text: string; imageDataUrl?: string }
  | { id: number; role: 'assistant'; text: string; toolResults?: JarvisToolResult[]; thinking?: string | null; pendingQuestion?: JarvisPendingQuestion | null; isGreeting?: boolean }
  // livePhrase: preenchido em tempo real pelos eventos SSE (ver
  // /assistant/chat/stream) — quando presente, LoadingPhrase mostra a
  // narração REAL do passo atual em vez do ciclo de frases genéricas.
  | { id: number; role: 'assistant-loading'; livePhrase?: string }
  // Marcador que substitui as mensagens mais antigas cortadas quando uma
  // conversa passa do teto salvo (ver MAX_STORED_PER_CONVO) — em vez de só
  // sumir sem deixar rastro, fica esse aviso de quantas ficaram de fora.
  | { id: number; role: 'condensed'; count: number };

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
  { Icon: TargetIcon, text: 'Compatibilidade com vagas de hoje' },
  { Icon: CycleIcon, text: 'Dê uma olhada nas minhas vagas em andamento' },
  { Icon: ChartIcon, text: 'Resumo rápido do meu funil' },
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

// ===================== Streaming (SSE) =====================
// POST /api/jobs/assistant/chat/stream devolve text/event-stream — o
// navegador não tem um EventSource nativo pra POST (só GET), então lê o
// corpo da resposta como stream manualmente via fetch + ReadableStream.
// Formato de cada evento: "event: NOME\ndata: {...json...}\n\n".
async function consumeSseStream(
  response: Response,
  onEvent: (eventName: string, data: unknown) => void
): Promise<void> {
  const reader = response.body?.getReader();
  if (!reader) return;
  const decoder = new TextDecoder();
  let buffer = '';
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    buffer += decoder.decode(value, { stream: true });
    let boundary: number;
    while ((boundary = buffer.indexOf('\n\n')) !== -1) {
      const rawEvent = buffer.slice(0, boundary);
      buffer = buffer.slice(boundary + 2);
      let eventName = 'message';
      const dataLines: string[] = [];
      for (const line of rawEvent.split('\n')) {
        if (line.startsWith('event:')) eventName = line.slice(6).trim();
        else if (line.startsWith('data:')) dataLines.push(line.slice(5).trim());
      }
      if (dataLines.length === 0) continue;
      try {
        onEvent(eventName, JSON.parse(dataLines.join('\n')));
      } catch {
        // linha malformada — ignora esse evento em vez de derrubar o stream inteiro
      }
    }
  }
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
    // Mensagem fixa de apresentação, não uma resposta gerada — não faz
    // sentido copiar ou dar 👍/👎 nela, então a barra de ações fica de fora.
    isGreeting: true,
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
            {v.notes && <p className="jarvis-hit-resumo jarvis-hit-resumo--flex"><NoteIcon /> {v.notes}</p>}
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

// Versão enxuta do AiFeedbackBox (que continua igual nos outros modais —
// carta, match-score, perguntas de entrevista) só pro chat: aqui a mesma
// caixa reaparece a cada resposta, então um bloco grande com rótulo +
// textarea sempre visível + histórico "Ver feedback salvo" empilhava e
// poluía a conversa rápido. Essa versão: ícones só, 1 clique já salva e
// TRAVA (sem like/dislike infinito na mesma resposta), campo de comentário
// só aparece se o usuário quiser (clica "+ comentário" depois de avaliar),
// sem lista de histórico — ela ainda existe em ⚙️/nos outros modais, só não
// precisa reaparecer aqui toda hora. Mesma featureKey/pool de sempre (ver
// useAiFeedback), então o que é avaliado aqui conta junto com o resto.
function CompactFeedback({ featureKey }: { featureKey: string }) {
  const { addFeedback } = useAiFeedback(featureKey);
  const [rating, setRating] = useState<'like' | 'dislike' | null>(null);
  const [done, setDone] = useState(false);
  const [showComment, setShowComment] = useState(false);
  const [comment, setComment] = useState('');

  const handlePick = (r: 'like' | 'dislike') => {
    if (done) return;
    setRating(r);
    addFeedback(r, '');
    setDone(true);
  };

  const handleAddComment = () => {
    if (!rating || !comment.trim()) return;
    addFeedback(rating, comment); // soma outra entrada com o comentário — a de cima já ficou sem
    setShowComment(false);
  };

  if (done && !showComment) {
    return (
      <div className="jarvis-compact-feedback jarvis-compact-feedback--done">
        <CheckIcon size={12} />
        <span>Feedback registrado</span>
        <button type="button" className="jarvis-compact-feedback-more" onClick={() => setShowComment(true)}>
          + comentário
        </button>
      </div>
    );
  }

  return (
    <div className="jarvis-compact-feedback">
      {!done ? (
        <>
          <button type="button" className="jarvis-compact-feedback-btn" onClick={() => handlePick('like')} aria-label="Gostei">
            <ThumbUpIcon />
          </button>
          <button type="button" className="jarvis-compact-feedback-btn jarvis-compact-feedback-btn--down" onClick={() => handlePick('dislike')} aria-label="Não gostei">
            <ThumbDownIcon />
          </button>
        </>
      ) : (
        <>
          <input
            className="jarvis-compact-feedback-input"
            value={comment}
            onChange={e => setComment(e.target.value)}
            placeholder="O que achou? (opcional)"
            autoFocus
            onKeyDown={e => { if (e.key === 'Enter') handleAddComment(); }}
          />
          <button type="button" className="jarvis-compact-feedback-more" onClick={handleAddComment} disabled={!comment.trim()}>
            enviar
          </button>
        </>
      )}
    </div>
  );
}

// Mesmo componente/endpoint que o "📚 Plano de ação" do modal de compatibilidade
// (ver GapItem em MatchScoreModal.tsx) — só troca `job: Job` por `jobId:
// number` porque aqui só temos o id/título/empresa da vaga (JarvisCompatibilidadeHit),
// não o objeto Job inteiro. Usa a MESMA featureKey 'learning-plan' que o
// modal já usa — o feedback dado aqui cai no mesmo pool.
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
        {!loading && <BookIcon />}
        <span>{loading ? 'Gerando plano...' : plan ? (open ? 'Ocultar plano' : 'Ver plano') : 'Plano de ação'}</span>
      </button>
      {error && <p className="agenda-error match-gap-error">{error}</p>}
      {plan && open && (
        <div className="match-plan">
          {plan.resumo && <p className="match-plan-resumo">{plan.resumo}</p>}
          {plan.tempoEstimado && <span className="match-plan-tempo"><TimerIcon /> {plan.tempoEstimado}</span>}
          {plan.passos.length > 0 && (
            <ol className="match-plan-steps">
              {plan.passos.map((passo, i) => <li key={i}>{passo}</li>)}
            </ol>
          )}
          <CompactFeedback featureKey="learning-plan" />
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
                <h4 className="match-section-title match-section-title--good"><SuccessIcon /> Pontos fortes</h4>
                <ul className="match-list">
                  {h.pontosFortes.map((p, i) => <li key={i}>{p}</li>)}
                </ul>
              </div>
            )}
            {!!h.pontosFaltando?.length && (
              <div className="match-section">
                <h4 className="match-section-title match-section-title--gap"><WarningIcon /> Pontos a desenvolver</h4>
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
      {data.erro && <p className="jarvis-scan-warning"><WarningIcon /> {data.erro}</p>}
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
        {data.totalEncontradas > data.vagas.length ? ` (de ${data.totalEncontradas} encontradas)` : ''}
        {data.margemErroPercent != null ? ` — estimativa aproximada, margem de erro média de ±${data.margemErroPercent}%` : ''}:
      </p>
      {data.modeloDesatualizado && (
        <p className="jarvis-scan-warning">
          <WarningIcon /> Modelo não é retreinado há {data.modeloDiasDesdeTreino} dias — pode estar defasado em
          relação ao mercado atual.
        </p>
      )}
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
            {v.estimativa != null && data.margemErroPercent != null && (
              <p className="jarvis-hit-resumo" style={{ color: 'var(--text-muted)' }}>
                Provável faixa: {formatBRL(Math.round(v.estimativa * (1 - data.margemErroPercent / 100)))} –{' '}
                {formatBRL(Math.round(v.estimativa * (1 + data.margemErroPercent / 100)))}
              </p>
            )}
            {v.salarioInformado && <p className="jarvis-hit-resumo">Salário informado na vaga: {v.salarioInformado}</p>}
          </div>
        ))}
      </div>
    </>
  );
}

function DetalharVagasCard({ data }: { data: JarvisDetalharVagasData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">{data.erro ?? 'Não achei nenhuma vaga com esse id.'}</p>;
  }
  if (data.modo === 'comparacao') {
    return (
      <div className="jarvis-compare">
        {data.vagas.map(v => {
          const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
          return (
            <div key={v.id} className="jarvis-compare-col">
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
              <span className="jarvis-hit-score" style={{ color: meta.color, borderColor: meta.color }}>{meta.label}</span>
              <dl className="jarvis-compare-facts">
                <dt>Senioridade</dt><dd>{v.senioridade ?? '—'}</dd>
                <dt>Modalidade</dt><dd>{v.modalidade ?? '—'}</dd>
                <dt>Local</dt><dd>{[v.cidade, v.estado].filter(Boolean).join(' - ') || '—'}</dd>
                <dt>Salário informado</dt><dd>{v.salarioInformado ?? '—'}</dd>
                <dt>Salário estimado</dt><dd>{v.salarioEstimado != null ? formatBRL(v.salarioEstimado) : '—'}</dd>
                <dt>Fonte</dt><dd>{v.fonte}</dd>
              </dl>
              {v.tags.length > 0 && (
                <div className="jarvis-compare-tags">
                  {v.tags.map(t => <span key={t} className="jarvis-tag-pill">{t}</span>)}
                </div>
              )}
              {v.notas && <p className="jarvis-hit-resumo jarvis-hit-resumo--flex"><NoteIcon /> {v.notas}</p>}
            </div>
          );
        })}
      </div>
    );
  }
  const v = data.vagas[0];
  const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-head">
        <span className="jarvis-hit-score" style={{ color: meta.color, borderColor: meta.color }}>{meta.label}</span>
        <div className="jarvis-hit-title">
          <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
          <span className="jarvis-hit-company">{v.empresa}</span>
        </div>
      </div>
      <dl className="jarvis-compare-facts">
        <dt>Senioridade</dt><dd>{v.senioridade ?? '—'}</dd>
        <dt>Modalidade</dt><dd>{v.modalidade ?? '—'}</dd>
        <dt>Local</dt><dd>{[v.cidade, v.estado].filter(Boolean).join(' - ') || '—'}</dd>
        <dt>Salário informado</dt><dd>{v.salarioInformado ?? '—'}</dd>
        <dt>Salário estimado</dt><dd>{v.salarioEstimado != null ? formatBRL(v.salarioEstimado) : '—'}</dd>
        <dt>Fonte</dt><dd>{v.fonte}</dd>
      </dl>
      {v.tags.length > 0 && (
        <div className="jarvis-compare-tags">
          {v.tags.map(t => <span key={t} className="jarvis-tag-pill">{t}</span>)}
        </div>
      )}
      {v.notas && <p className="jarvis-hit-resumo jarvis-hit-resumo--flex"><NoteIcon /> {v.notas}</p>}
    </div>
  );
}

function VagasParecidasCard({ data }: { data: JarvisVagasParecidasData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">{data.erro ?? 'Não achei vagas parecidas.'}</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: meta.color, borderColor: meta.color }}>{meta.label}</span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
            {v.tagsEmComum.length > 0 && (
              <div className="jarvis-compare-tags">
                {v.tagsEmComum.map(t => <span key={t} className="jarvis-tag-pill">{t}</span>)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function VagasParadasCard({ data }: { data: JarvisVagasParadasData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">Nenhuma candidatura parada há mais de {data.diasMinimo} dias — tudo em dia.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: 'var(--yellow)', borderColor: 'var(--yellow)' }}>
                {v.diasParada}d parada
              </span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
            <p className="jarvis-hit-resumo" style={{ color: meta.color }}>{meta.label}</p>
          </div>
        );
      })}
    </div>
  );
}

function CartaCard({ data }: { data: JarvisCartaData }) {
  const [copiado, setCopiado] = useState(false);
  if (!data.carta) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui gerar a carta agora.'}</p>;
  }
  const handleCopiar = () => {
    navigator.clipboard.writeText(data.carta!).then(() => {
      setCopiado(true);
      setTimeout(() => setCopiado(false), 1500);
    });
  };
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo" style={{ whiteSpace: 'pre-line' }}>{data.carta}</p>
      <button type="button" className="jarvis-msg-action-btn" onClick={handleCopiar} title="Copiar carta" style={{ marginTop: '0.4rem' }}>
        {copiado ? <CheckIcon /> : <CopyIcon />} <span style={{ marginLeft: '0.3rem', fontSize: '0.72rem' }}>{copiado ? 'Copiado' : 'Copiar'}</span>
      </button>
    </div>
  );
}

function MetricasCard({ data }: { data: JarvisMetricasData }) {
  const itens: [string, string][] = [
    ['Total aplicadas', String(data.totalAplicadas)],
    ['Em andamento', String(data.emAndamento)],
    ['Recusadas', String(data.recusadas)],
    ['Aguardando retorno', String(data.aguardandoRetorno)],
    ['Taxa de resposta', data.taxaRespostaPercent != null ? `${data.taxaRespostaPercent}%` : '—'],
    ['Dias até andamento (média)', data.tempoMedioAteAndamentoDias != null ? `${data.tempoMedioAteAndamentoDias}d` : '—'],
    ['Dias até recusa (média)', data.tempoMedioAteRecusaDias != null ? `${data.tempoMedioAteRecusaDias}d` : '—'],
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

function PrazoCard({ data }: { data: JarvisPrazoData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">Nenhuma vaga fechando nos próximos {data.diasMaximo} dias.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: 'var(--red)', borderColor: 'var(--red)' }}>
                {v.diasRestantes === 0 ? 'fecha hoje' : `${v.diasRestantes}d`}
              </span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
            <p className="jarvis-hit-resumo" style={{ color: meta.color }}>{meta.label}</p>
          </div>
        );
      })}
    </div>
  );
}

function AcaoVagaRow({ v, badge, badgeColor }: { v: JarvisAcaoVaga; badge: string; badgeColor: string }) {
  return (
    <div key={v.id} className="jarvis-hit">
      <div className="jarvis-hit-head">
        <span className="jarvis-hit-score" style={{ color: badgeColor, borderColor: badgeColor }}>{badge}</span>
        <div className="jarvis-hit-title">
          <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
          <span className="jarvis-hit-company">{v.empresa}</span>
        </div>
      </div>
    </div>
  );
}

function OQueFazerAgoraCard({ data }: { data: JarvisOQueFazerAgoraData }) {
  const nada = data.candidaturasParadas.top.length === 0
    && data.prazosProximos.top.length === 0
    && data.vagasNovasComBomMatch.top.length === 0;
  if (nada) {
    return <p className="jarvis-scan-intro">Sem pendência urgente agora — tudo em dia.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.prazosProximos.top.map(v => (
        <AcaoVagaRow key={`prazo-${v.id}`} v={v} badge={v.diasRestantes === 0 ? 'fecha hoje' : `${v.diasRestantes}d`} badgeColor="var(--red)" />
      ))}
      {data.candidaturasParadas.top.map(v => (
        <AcaoVagaRow key={`parada-${v.id}`} v={v} badge={`${v.diasParada}d parada`} badgeColor="var(--yellow)" />
      ))}
      {data.vagasNovasComBomMatch.top.map(v => (
        <AcaoVagaRow key={`match-${v.id}`} v={v} badge={`${v.matchPercent}% match`} badgeColor="var(--accent)" />
      ))}
    </div>
  );
}

function CompararMercadoCard({ data }: { data: JarvisCompararMercadoData }) {
  if (data.erro) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro}</p>;
  }
  const faltando = data.tagsMaisPedidasQueFaltamNoPerfil ?? [];
  if (faltando.length === 0) {
    return <p className="jarvis-scan-intro">Seu perfil já cobre as tags mais pedidas do feed atual.</p>;
  }
  return (
    <div className="jarvis-tag-mercado-list">
      {faltando.map(t => (
        <div key={t.tag} className="jarvis-tag-mercado-item">
          <span className="tag tag--tech">{t.tag}</span>
          <span className="jarvis-tag-mercado-count">{t.vagasComEssaTag} vaga{t.vagasComEssaTag === 1 ? '' : 's'}</span>
        </div>
      ))}
    </div>
  );
}

function DuplicatasCard({ data }: { data: JarvisDuplicatasData }) {
  if (data.grupos.length === 0) {
    return <p className="jarvis-scan-intro">Não achei nenhuma duplicata provável.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.grupos.map((g, i) => (
        <div key={i} className="jarvis-hit">
          <div className="jarvis-hit-title"><span>{g.empresa}</span></div>
          {g.vagas.map(v => (
            <p key={v.id} className="jarvis-hit-resumo">
              <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a> — {v.fonte}
            </p>
          ))}
        </div>
      ))}
    </div>
  );
}

function FontesCard({ data }: { data: JarvisFontesData }) {
  if (data.fontes.length === 0) {
    return <p className="jarvis-scan-intro">Sem dados de fonte ainda.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.fontes.map(f => (
        <div key={f.fonte} className="jarvis-hit">
          <div className="jarvis-hit-head">
            <span className="jarvis-hit-score" style={{ color: 'var(--green)', borderColor: 'var(--green)' }}>
              {f.emAndamento} em andamento
            </span>
            <div className="jarvis-hit-title"><span>{f.fonte}</span></div>
          </div>
          <p className="jarvis-hit-resumo">{f.totalVagas} vagas no total · {f.aplicadas} aplicadas</p>
        </div>
      ))}
    </div>
  );
}

function HistoricoEmpresaCard({ data }: { data: JarvisHistoricoEmpresaData }) {
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">Não achei nenhuma vaga dessa empresa no seu histórico.</p>;
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: meta.color, borderColor: meta.color }}>{meta.label}</span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

// Confirmação visual de uma ferramenta que escreve — mostra o "antes/depois"
// pra deixar claro o que mudou de verdade no banco (a lista de vagas por
// trás do chat já recarrega sozinha, ver onJobsChanged em handleSend).
function MarcarStatusCard({ data }: { data: JarvisMarcarStatusData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui mudar o status dessa vaga.'}</p>;
  }
  const antes = data.statusAntes ? (STATUS_META[data.statusAntes]?.label ?? data.statusAntes) : '—';
  const depois = data.statusNovo ? (STATUS_META[data.statusNovo]?.label ?? data.statusNovo) : '—';
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo jarvis-hit-resumo--icon"><SuccessIcon /> {antes} → <strong>{depois}</strong></p>
    </div>
  );
}

function AtualizarNotaCard({ data }: { data: JarvisAtualizarNotaData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui atualizar a nota dessa vaga.'}</p>;
  }
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo jarvis-hit-resumo--icon"><SuccessIcon /> Nota atualizada</p>
      {data.notaNova && (
        <p className="jarvis-hit-resumo" style={{ display: 'flex', alignItems: 'center', gap: '0.35rem' }}>
          <NoteIcon /> {data.notaNova}
        </p>
      )}
    </div>
  );
}

function LembrarCard({ data }: { data: JarvisLembrarData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui guardar essa preferência.'}</p>;
  }
  return (
    <p className="jarvis-hit-resumo jarvis-hit-resumo--icon">
      <ThinkingIcon /> Vou lembrar: <strong>{data.texto}</strong>
    </p>
  );
}

// O backend do Job Radar nunca fala com a Agenda — esse card é quem cria de
// verdade, via useAgenda (mesmo hook que AgendaModal/InterviewModal já
// usam). Conecta primeiro se ainda não tiver token salvo, mesma UX do resto
// do app.
function BuscaSemanticaCard({ data }: { data: JarvisBuscaSemanticaData }) {
  if (data.erro) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro}</p>;
  }
  if (data.vagas.length === 0) {
    return (
      <p className="jarvis-scan-intro">
        Nenhuma vaga embeddada bateu com "{data.consulta}" — pode ser vaga recente ainda sem embedding
        (rode o backfill em ⚙️ Configurações) ou tente uma busca por palavra-chave.
      </p>
    );
  }
  return (
    <div className="jarvis-hits">
      {data.vagas.map(v => {
        const meta = STATUS_META[v.status] ?? { label: v.status, color: 'var(--text-muted)' };
        return (
          <div key={v.id} className="jarvis-hit">
            <div className="jarvis-hit-head">
              <span className="jarvis-hit-score" style={{ color: 'var(--accent)', borderColor: 'var(--accent)' }}>
                {v.similaridadePercent}%
              </span>
              <div className="jarvis-hit-title">
                <a href={v.url} target="_blank" rel="noopener noreferrer">{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa}</span>
              </div>
            </div>
            <p className="jarvis-hit-resumo" style={{ color: meta.color }}>{meta.label}</p>
          </div>
        );
      })}
    </div>
  );
}

function LembreteAgendaCard({ data }: { data: JarvisLembreteAgendaData }) {
  const { isConnected, savedEmail, login, createTask, linkTask } = useAgenda();
  const [step, setStep] = useState<'proposta' | 'connect' | 'criado'>('proposta');
  const [email, setEmail] = useState(savedEmail());
  const [password, setPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  const [loginLoading, setLoginLoading] = useState(false);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');

  if (data.erro) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro}</p>;
  }

  const handleCreate = async () => {
    if (!isConnected()) { setStep('connect'); return; }
    setSending(true);
    setSendError('');
    const result = await createTask({
      title: data.titulo,
      description: data.descricao || (data.urlVaga ? `🔗 ${data.urlVaga}` : undefined),
      dueAt: data.dueAt || undefined,
      priority: (data.prioridade as 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL') || 'NORMAL',
    });
    setSending(false);
    if (result !== 'unauthorized' && result !== 'error') {
      if (data.vagaId) linkTask(data.vagaId, result.id, data.dueAt ?? null);
      setStep('criado');
    } else if (result === 'unauthorized') {
      setStep('connect');
      setSendError('Sessão expirada — conecta de novo.');
    } else {
      setSendError('Erro ao criar o lembrete. Tenta de novo?');
    }
  };

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginLoading(true);
    setLoginError('');
    const result = await login(email, password);
    setLoginLoading(false);
    if (result === 'ok') {
      setStep('proposta');
      handleCreate();
    } else if (result === 'invalid') {
      setLoginError('E-mail ou senha incorretos.');
    } else {
      setLoginError('Não consegui conectar à Agenda. Ela está rodando?');
    }
  };

  if (step === 'criado') {
    return <p className="jarvis-hit-resumo jarvis-hit-resumo--icon"><SuccessIcon /> Lembrete criado na Agenda.</p>;
  }

  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        {data.empresaVaga && <span className="jarvis-hit-company">{data.tituloVaga} — {data.empresaVaga}</span>}
      </div>
      {data.descricao && <p className="jarvis-hit-resumo">{data.descricao}</p>}
      <p className="jarvis-hit-resumo" style={{ color: 'var(--text-muted)' }}>
        {data.dueAt ? new Date(data.dueAt).toLocaleString('pt-BR') : 'Sem data definida'} · Prioridade {data.prioridade ?? 'NORMAL'}
      </p>

      {step === 'connect' && (
        <form className="agenda-form" onSubmit={handleLogin} style={{ marginTop: '0.5rem' }}>
          <input
            className="agenda-input"
            type="email"
            value={email}
            onChange={e => setEmail(e.target.value)}
            placeholder="seu@email.com"
            required
          />
          <input
            className="agenda-input"
            type="password"
            value={password}
            onChange={e => setPassword(e.target.value)}
            placeholder="senha"
            required
            style={{ marginTop: '0.35rem' }}
          />
          {loginError && <p className="agenda-error">{loginError}</p>}
          <button type="submit" className="btn btn-primary" disabled={loginLoading} style={{ marginTop: '0.5rem' }}>
            {loginLoading ? 'Conectando...' : 'Conectar e criar'}
          </button>
        </form>
      )}

      {step === 'proposta' && (
        <>
          {sendError && <p className="agenda-error">{sendError}</p>}
          <button type="button" className="jarvis-msg-action-btn" onClick={handleCreate} disabled={sending} style={{ marginTop: '0.4rem' }}>
            {sending ? 'Criando...' : '📅 Criar lembrete na Agenda'}
          </button>
        </>
      )}
    </div>
  );
}

function FixarVagaCard({ data }: { data: JarvisFixarVagaData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui fixar/desafixar essa vaga.'}</p>;
  }
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo jarvis-hit-resumo--icon">
        <SuccessIcon /> {data.fixada ? 'Fixada no topo' : 'Desafixada'}
      </p>
    </div>
  );
}

function AdicionarVagaCard({ data }: { data: JarvisAdicionarVagaData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui adicionar essa vaga.'}</p>;
  }
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo jarvis-hit-resumo--icon">
        <SuccessIcon /> Adicionada ({STATUS_META[data.status ?? '']?.label ?? data.status})
      </p>
    </div>
  );
}

function ApagarVagaCard({ data }: { data: JarvisApagarVagaData }) {
  if (!data.sucesso) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro ?? 'Não consegui apagar essa vaga.'}</p>;
  }
  return (
    <div className="jarvis-hit">
      <div className="jarvis-hit-title">
        <span>{data.titulo}</span>
        <span className="jarvis-hit-company">{data.empresa}</span>
      </div>
      <p className="jarvis-hit-resumo jarvis-hit-resumo--icon"><SuccessIcon /> Apagada permanentemente</p>
    </div>
  );
}

// Rótulo pra cada ferramenta real, mostrado assim que o evento SSE
// "tool_call" chega (ver /assistant/chat/stream) — a narração de verdade,
// não mais só uma aproximação por palavra-chave da mensagem do usuário.
const TOOL_PHRASES: Record<string, string> = {
  listarVagas: 'Buscando suas vagas...',
  resumoFunil: 'Calculando o resumo do funil...',
  compatibilidadeComVagasRecentes: 'Analisando compatibilidade com o Gemini...',
  compatibilidadeComVagasDoFunil: 'Analisando compatibilidade com o Gemini...',
  estimativaSalarialDeVagas: 'Estimando a faixa salarial...',
  detalharVagas: 'Buscando os detalhes da vaga...',
  vagasParecidas: 'Procurando vagas parecidas...',
  vagasParadas: 'Verificando candidaturas paradas...',
  marcarStatusDeVaga: 'Atualizando o status da vaga...',
  atualizarNotaDeVaga: 'Salvando a nota...',
  gerarCartaDeApresentacao: 'Escrevendo a carta de apresentação...',
  metricasDeDesempenho: 'Calculando métricas de desempenho...',
  vagasComPrazoProximo: 'Verificando prazos de candidatura...',
  detectarDuplicatas: 'Procurando vagas duplicadas...',
  desempenhoPorFonte: 'Cruzando desempenho por fonte...',
  historicoDaEmpresa: 'Buscando histórico da empresa...',
  fixarVaga: 'Fixando a vaga...',
  adicionarVagaManual: 'Adicionando a vaga...',
  apagarVaga: 'Removendo a vaga...',
  lembrarPreferencia: 'Guardando a preferência...',
  oQueFazerAgora: 'Montando o panorama do dia...',
  compararStackComMercado: 'Comparando seu perfil com o mercado...',
  criarLembreteNaAgenda: 'Montando a proposta de lembrete...',
  buscarVagasPorSignificado: 'Buscando por significado...',
  perguntarUsuario: 'Preparando uma pergunta...',
};

// Frases que revezam enquanto espera a resposta — mesma ideia do texto de
// status que o Claude Code mostra enquanto trabalha. É o FALLBACK: usado
// antes do primeiro evento SSE chegar, ou se o navegador cair pro caminho
// sem streaming (ver handleSend). Lê palavras-chave da MENSAGEM que o
// usuário acabou de mandar e escolhe um conjunto de frases relacionado ao
// assunto, em vez de um ciclo genérico sempre igual não importa o pedido.
interface FraseContexto { userText: string; hasImage: boolean }

const FRASE_SETS: { test: (ctx: FraseContexto) => boolean; frases: string[] }[] = [
  { test: ctx => ctx.hasImage,
    frases: ['Abrindo o print...', 'Analisando a imagem...', 'Procurando a vaga correspondente...'] },
  { test: ctx => /interessad/i.test(ctx.userText),
    frases: ['Entrando na aba Interessado...', 'Olhando suas vagas marcadas...'] },
  { test: ctx => /andamento/i.test(ctx.userText),
    frases: ['Entrando na aba Em Andamento...', 'Conferindo o processo seletivo...'] },
  { test: ctx => /aplicad/i.test(ctx.userText),
    frases: ['Entrando na aba Aplicadas...', 'Conferindo suas candidaturas...'] },
  { test: ctx => /recusad/i.test(ctx.userText),
    frases: ['Entrando na aba Recusadas...', 'Revendo o que ficou pra trás...'] },
  { test: ctx => /(parecid|similar)/i.test(ctx.userText),
    frases: ['Comparando tags de vagas...', 'Procurando vagas parecidas...'] },
  { test: ctx => /(sal[aá]rio|quanto pagam|faixa salarial)/i.test(ctx.userText),
    frases: ['Rodando a estimativa salarial...', 'Comparando faixas de salário...'] },
  { test: ctx => /(compat[ií]vel|compatibilidade)/i.test(ctx.userText),
    frases: ['Comparando com seu perfil...', 'Calculando compatibilidade...', 'Lendo pontos fortes e fracos...'] },
  { test: ctx => /(marca|muda(r)? o status|marcar como)/i.test(ctx.userText),
    frases: ['Atualizando o status da vaga...', 'Salvando a mudança...'] },
  { test: ctx => /(detalh|explica a vaga|me conta sobre)/i.test(ctx.userText),
    frases: ['Buscando os detalhes da vaga...', 'Reunindo as informações salvas...'] },
  { test: ctx => /(funil|resumo|quantas vagas)/i.test(ctx.userText),
    frases: ['Consultando o funil...', 'Contando as vagas por status...'] },
];

const DEFAULT_PHRASES = ['Pensando...', 'Vasculhando o feed...'];

function escolherFrases(ctx: FraseContexto): string[] {
  const grupo = FRASE_SETS.find(s => s.test(ctx));
  return [...(grupo ? grupo.frases : DEFAULT_PHRASES), 'Escrevendo resposta...'];
}

function LoadingPhrase({ userText, hasImage, livePhrase }: { userText: string; hasImage: boolean; livePhrase?: string }) {
  const frases = escolherFrases({ userText, hasImage });
  const [index, setIndex] = useState(0);
  useEffect(() => {
    // Avança uma vez por frase e PARA na última ("Escrevendo resposta...")
    // em vez de voltar pro início — antes o ciclo era infinito (% frases.
    // length), então numa espera mais longa "Escrevendo resposta..." podia
    // aparecer, sumir e voltar 2-3 vezes antes da resposta chegar de
    // verdade, o que é enganoso (parecia que já tinha começado a escrever
    // e não tinha). Agora ela aparece uma vez só e fica ali até acabar.
    // Só roda o ciclo genérico enquanto não tem narração real chegando —
    // ver comentário no bloco TOOL_PHRASES acima.
    if (livePhrase) return;
    const id = setInterval(() => {
      setIndex(i => (i < frases.length - 1 ? i + 1 : i));
    }, 1800);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userText, hasImage, livePhrase]);
  // key={texto} força remontar o <span> a cada troca, pra animação de fade
  // rodar de novo em cada frase (senão só o texto trocaria sem transição).
  const texto = livePhrase ?? frases[index];
  return <span key={texto} className="jarvis-typing-phrase">{texto}</span>;
}

// Cronômetro tipo o do Claude Code — conta quanto tempo a espera está
// levando, começando do zero quando a mensagem de loading aparece. Só serve
// pra dar noção do tempo real, não afeta nada da lógica de espera em si.
function ElapsedTimer() {
  const [segundos, setSegundos] = useState(0);
  useEffect(() => {
    const inicio = Date.now();
    const id = setInterval(() => setSegundos(Math.floor((Date.now() - inicio) / 1000)), 1000);
    return () => clearInterval(id);
  }, []);
  const min = Math.floor(segundos / 60);
  const seg = segundos % 60;
  return (
    <span className="jarvis-elapsed-timer">
      {min > 0 ? `${min}m ${seg}s` : `${seg}s`}
    </span>
  );
}

// Raciocínio real do Gemini antes da resposta — recolhido por padrão (é
// texto de "rascunho mental", não a resposta em si, então não compete por
// atenção com ela). Só existe o botão quando thinking vem preenchido.
// Estilo pensado pra lembrar o bloco de raciocínio do próprio Claude Code
// (itálico, cor discreta, fluindo junto do texto) em vez de um card isolado
// com borda — e o ícone é desenhado (ThinkingIcon), não emoji do sistema,
// que em alguns SOs nem renderiza (foi assim que achamos esse ajuste).
function ThinkingBlock({ thinking }: { thinking: string }) {
  const [open, setOpen] = useState(false);
  return (
    <div className="jarvis-thinking">
      <button type="button" className="jarvis-thinking-toggle" onClick={() => setOpen(o => !o)}>
        <ThinkingIcon />
        <span>{open ? 'Ocultar raciocínio' : 'Ver raciocínio'}</span>
      </button>
      {open && <div className="jarvis-thinking-text">{renderMarkdownLite(thinking)}</div>}
    </div>
  );
}

// Pergunta interativa (ferramenta perguntarUsuario) — botões de verdade em
// vez do Hunter só perguntar em texto solto. `locked` é true quando essa
// pergunta já tem uma resposta na sequência da conversa (não é a última
// mensagem mais) — nesse caso mostra só o que foi escolhido, sem poder
// clicar de novo. `onAnswer` reaproveita o mesmo handleSend de sempre: a
// resposta escolhida vira uma mensagem de usuário comum, e o histórico
// completo (pergunta + resposta) volta pro backend na próxima chamada —
// não precisa de nenhum encanamento especial pra "retomar" o Gemini.
function PendingQuestionCard({ question, locked, answeredWith, onAnswer }: {
  question: JarvisPendingQuestion; locked: boolean; answeredWith?: string; onAnswer: (texto: string) => void;
}) {
  const [outro, setOutro] = useState('');
  const [showOutro, setShowOutro] = useState(false);

  return (
    <div className="jarvis-question">
      <p className="jarvis-question-text">{question.pergunta}</p>
      <div className="jarvis-question-options">
        {question.opcoes.map(op => (
          <button
            key={op}
            type="button"
            className={`jarvis-question-btn ${answeredWith === op ? 'jarvis-question-btn--picked' : ''}`}
            onClick={() => !locked && onAnswer(op)}
            disabled={locked}
          >
            {op}
          </button>
        ))}
        {question.permiteOutro && !locked && !showOutro && (
          <button type="button" className="jarvis-question-btn jarvis-question-btn--outro" onClick={() => setShowOutro(true)}>
            Outra opção...
          </button>
        )}
      </div>
      {showOutro && !locked && (
        <div className="jarvis-question-outro-row">
          <input
            className="jarvis-question-outro-input"
            value={outro}
            onChange={e => setOutro(e.target.value)}
            placeholder="Digite sua resposta..."
            autoFocus
            onKeyDown={e => { if (e.key === 'Enter' && outro.trim()) onAnswer(outro.trim()); }}
          />
          <button type="button" className="jarvis-question-btn" onClick={() => outro.trim() && onAnswer(outro.trim())} disabled={!outro.trim()}>
            enviar
          </button>
        </div>
      )}
      {locked && answeredWith && !question.opcoes.includes(answeredWith) && (
        <p className="jarvis-question-answered">Você respondeu: <strong>{answeredWith}</strong></p>
      )}
    </div>
  );
}

// Barra de ações embaixo de cada resposta do Hunter — copiar mensagem +
// feedback compacto, igual ao rodapé de mensagem do próprio Claude Code.
function ChatMessageActions({ text, featureKey }: { text: string; featureKey: string }) {
  const [copied, setCopied] = useState(false);

  const handleCopy = () => {
    navigator.clipboard.writeText(text).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    });
  };

  return (
    <div className="jarvis-msg-actions">
      <button type="button" className="jarvis-msg-action-btn" onClick={handleCopy} title="Copiar mensagem" aria-label="Copiar mensagem">
        {copied ? <CheckIcon /> : <CopyIcon />}
      </button>
      <CompactFeedback featureKey={featureKey} />
    </div>
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
    case 'detalharVagas':
      return <DetalharVagasCard data={result.data as JarvisDetalharVagasData} />;
    case 'vagasParecidas':
      return <VagasParecidasCard data={result.data as JarvisVagasParecidasData} />;
    case 'vagasParadas':
      return <VagasParadasCard data={result.data as JarvisVagasParadasData} />;
    case 'marcarStatusDeVaga':
      return <MarcarStatusCard data={result.data as JarvisMarcarStatusData} />;
    case 'atualizarNotaDeVaga':
      return <AtualizarNotaCard data={result.data as JarvisAtualizarNotaData} />;
    case 'gerarCartaDeApresentacao':
      return <CartaCard data={result.data as JarvisCartaData} />;
    case 'metricasDeDesempenho':
      return <MetricasCard data={result.data as JarvisMetricasData} />;
    case 'vagasComPrazoProximo':
      return <PrazoCard data={result.data as JarvisPrazoData} />;
    case 'detectarDuplicatas':
      return <DuplicatasCard data={result.data as JarvisDuplicatasData} />;
    case 'desempenhoPorFonte':
      return <FontesCard data={result.data as JarvisFontesData} />;
    case 'historicoDaEmpresa':
      return <HistoricoEmpresaCard data={result.data as JarvisHistoricoEmpresaData} />;
    case 'fixarVaga':
      return <FixarVagaCard data={result.data as JarvisFixarVagaData} />;
    case 'adicionarVagaManual':
      return <AdicionarVagaCard data={result.data as JarvisAdicionarVagaData} />;
    case 'apagarVaga':
      return <ApagarVagaCard data={result.data as JarvisApagarVagaData} />;
    case 'lembrarPreferencia':
      return <LembrarCard data={result.data as JarvisLembrarData} />;
    case 'oQueFazerAgora':
      return <OQueFazerAgoraCard data={result.data as JarvisOQueFazerAgoraData} />;
    case 'compararStackComMercado':
      return <CompararMercadoCard data={result.data as JarvisCompararMercadoData} />;
    case 'criarLembreteNaAgenda':
      return <LembreteAgendaCard data={result.data as JarvisLembreteAgendaData} />;
    case 'buscarVagasPorSignificado':
      return <BuscaSemanticaCard data={result.data as JarvisBuscaSemanticaData} />;
    default:
      return null;
  }
}

// ===================== Histórico de conversas =====================

// Só ativa o modo de edição do título quando clica no lápis (não no botão
// inteiro, que já serve pra abrir a conversa) — Enter/blur salva, Escape
// cancela sem mudar nada.
function EditableTitle({ title, onRename }: { title: string; onRename: (novo: string) => void }) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState(title);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (editing) { setDraft(title); inputRef.current?.focus(); inputRef.current?.select(); }
  }, [editing, title]);

  if (editing) {
    return (
      <input
        ref={inputRef}
        className="jarvis-history-item-title-input"
        value={draft}
        onChange={e => setDraft(e.target.value)}
        onClick={e => e.stopPropagation()}
        onBlur={() => { onRename(draft); setEditing(false); }}
        onKeyDown={e => {
          if (e.key === 'Enter') { onRename(draft); setEditing(false); }
          if (e.key === 'Escape') setEditing(false);
        }}
      />
    );
  }
  return (
    <span className="jarvis-history-item-title-row">
      <span className="jarvis-history-item-title">{title}</span>
      <span
        className="jarvis-history-item-rename"
        role="button"
        aria-label="Renomear conversa"
        onClick={e => { e.stopPropagation(); setEditing(true); }}
      >
        <PencilIcon />
      </span>
    </span>
  );
}

function HistoryView({
  conversations, activeId, onSelect, onDelete, onRename, onExport,
}: {
  conversations: Conversation[];
  activeId: string;
  onSelect: (id: string) => void;
  onDelete: (id: string, e: React.MouseEvent) => void;
  onRename: (id: string, title: string) => void;
  onExport: (c: Conversation) => void;
}) {
  const [busca, setBusca] = useState('');
  const ordered = [...conversations].sort((a, b) => b.updatedAt - a.updatedAt);
  const termo = busca.trim().toLowerCase();
  // Busca no título E no texto das mensagens — não só no título, senão não
  // acha uma conversa antiga só porque não lembra o nome dela.
  const filtradas = termo
    ? ordered.filter(c =>
        c.title.toLowerCase().includes(termo)
        || c.messages.some(m => 'text' in m && m.text?.toLowerCase().includes(termo)))
    : ordered;
  return (
    <div className="jarvis-history-wrap">
      {conversations.length > 4 && (
        <div className="jarvis-history-search">
          <SearchIcon />
          <input
            value={busca}
            onChange={e => setBusca(e.target.value)}
            placeholder="Buscar nas conversas..."
            aria-label="Buscar nas conversas"
          />
        </div>
      )}
      <div className="jarvis-history-list">
        {filtradas.length === 0 && (
          <p className="jarvis-history-empty">Nenhuma conversa encontrada.</p>
        )}
        {filtradas.map(c => (
          <button
            key={c.id}
            className={`jarvis-history-item ${c.id === activeId ? 'jarvis-history-item--active' : ''}`}
            onClick={() => onSelect(c.id)}
          >
            <div className="jarvis-history-item-main">
              <EditableTitle title={c.title} onRename={novo => onRename(c.id, novo)} />
              <span className="jarvis-history-item-time">{relativeTime(c.updatedAt)}</span>
            </div>
            <span
              className="jarvis-history-item-export"
              role="button"
              aria-label="Exportar conversa em markdown"
              onClick={e => { e.stopPropagation(); onExport(c); }}
            >
              <DownloadIcon />
            </span>
            <span
              className="jarvis-history-item-delete"
              role="button"
              aria-label="Apagar conversa"
              onClick={e => onDelete(c.id, e)}
            >
              <TrashIcon />
            </span>
          </button>
        ))}
      </div>
    </div>
  );
}

export function JarvisPanel({ onClose, onJobsChanged }: Props) {
  const { profile } = useCandidateProfile();
  // Mesmas featureKeys que MatchScoreModal.tsx usa pros cards de vaga — o
  // 👍/👎 dado ali OU aqui no chat cai no mesmo pool salvo em localStorage,
  // então o feedback vale nos dois lugares (antes o chat não tinha acesso
  // nenhum a esse histórico).
  const { buildContext: buildMatchFeedbackContext } = useAiFeedback('match-score');
  const { buildContext: buildPlanFeedbackContext } = useAiFeedback('learning-plan');
  // Feedback dado direto embaixo de cada resposta do Hunter no chat (não é
  // sobre um plano ou uma análise específica, é sobre a resposta em si) —
  // combinado com o de match-score e mandado junto em toda mensagem, pra
  // realmente influenciar tom/formato das próximas respostas (ver
  // SYSTEM_INSTRUCTION no backend).
  const { buildContext: buildChatFeedbackContext } = useAiFeedback('jarvis-chat');
  const combinedFeedbackContext = () =>
    [buildMatchFeedbackContext(), buildChatFeedbackContext()].filter(s => s.trim()).join('\n');
  // Aviso de cota baixa — mesmo endpoint que ⚙️ Configurações usa, refresh()
  // chamado de novo depois de cada resposta do Hunter pra refletir o consumo
  // em tempo real (não é só um número estático do momento em que abriu).
  const { keyPool, refresh: refreshAiStatus } = useAiStatus();
  const quotaBaixa = keyPool != null && keyPool.total > 0 && keyPool.availableToday <= Math.max(1, Math.ceil(keyPool.total * 0.15));
  const hunterMemory = useHunterMemory();
  const [memoryOpen, setMemoryOpen] = useState(false);
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
  const [listening, setListening] = useState(false);
  const [compactMode, setCompactMode] = useState(() => {
    try { return localStorage.getItem(COMPACT_MODE_KEY) === '1'; } catch { return false; }
  });
  const textareaRef = useRef<HTMLTextAreaElement>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const recognitionRef = useRef<SpeechRecognitionInstance | null>(null);
  const micSupported = typeof window !== 'undefined' && !!(window.SpeechRecognition || window.webkitSpeechRecognition);
  // "Parar" — cancela a requisição em andamento (ver botão de stop no rodapé
  // e o handler abaixo). O backend detecta a desconexão e para de gastar
  // cota nas próximas ferramentas de uma pergunta que dispara várias (ver
  // ChatProgressListener.isCancelled).
  const abortControllerRef = useRef<AbortController | null>(null);

  useEffect(() => {
    try { localStorage.setItem(COMPACT_MODE_KEY, compactMode ? '1' : '0'); } catch { /* ignore */ }
  }, [compactMode]);

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
      .map(c => {
        if (c.messages.length <= MAX_STORED_PER_CONVO) return c;
        // Reserva 1 vaga pro marcador — em vez de só cortar as mais antigas
        // sem deixar rastro, avisa quantas ficaram de fora. Recalcula do
        // zero a cada troca (não acumula marcador antigo), então o número
        // reflete certinho o que está sendo cortado AGORA a partir do que
        // ainda está em memória nessa sessão.
        const restantes = c.messages.slice(-(MAX_STORED_PER_CONVO - 1));
        const cortadas = c.messages.length - restantes.length;
        const marcador: Message = { id: -1, role: 'condensed', count: cortadas };
        return { ...c, messages: [marcador, ...restantes] };
      });
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
  // Atualiza a frase da bolha de loading em tempo real conforme os eventos
  // SSE chegam (ver handleSend/consumeSseStream) — a mesma bolha, só troca o
  // texto mostrado dentro dela.
  const setLoadingPhase = (phrase: string) => {
    patchActive(msgs => msgs.map(m => (m.role === 'assistant-loading' ? { ...m, livePhrase: phrase } : m)));
  };

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

  // Renomear conversa — usado pelo clique no lápis de cada item do histórico
  // (ver EditableTitle em HistoryView). Título vazio não salva (mantém o
  // anterior), evita ficar com item sem nome nenhum na lista.
  const handleRenameConversation = (id: string, title: string) => {
    const trimmed = title.trim();
    if (!trimmed) return;
    setConversations(prev => prev.map(c => (c.id === id ? { ...c, title: trimmed } : c)));
  };

  // Exporta a conversa inteira como .md — ignora mensagens de "carregando"
  // (não tem texto de verdade) e a pergunta interativa vira só o texto da
  // pergunta em si (o card com botões não faz sentido fora do app).
  const handleExportConversation = (c: Conversation) => {
    const corpo = c.messages
      .filter((m): m is Extract<Message, { role: 'user' | 'assistant' }> =>
        (m.role === 'user' || m.role === 'assistant') && !!m.text.trim())
      .map(m => `**${m.role === 'user' ? 'Você' : 'Hunter'}:**\n\n${m.text}`)
      .join('\n\n---\n\n');
    const md = `# ${c.title}\n\n${corpo}\n`;
    const blob = new Blob([md], { type: 'text/markdown;charset=utf-8' });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${c.title.replace(/[^\p{L}\p{N}]+/gu, '_').slice(0, 60) || 'conversa'}.md`;
    a.click();
    URL.revokeObjectURL(url);
  };

  // Esc fecha o painel de qualquer lugar (mesmo com foco no textarea) —
  // padrão comum de modal/painel lateral. Ctrl+K (App.tsx) faz o toggle;
  // aqui só o fechar mesmo.
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') handleRequestClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Foco automático no campo de mensagem assim que o painel abre — evita ter
  // que clicar no campo toda vez que abre com Ctrl+K ou pelo botão.
  useEffect(() => {
    textareaRef.current?.focus();
  }, []);

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

    const controller = new AbortController();
    abortControllerRef.current = controller;

    try {
      const res = await fetch('/api/jobs/assistant/chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        signal: controller.signal,
        body: JSON.stringify({
          history, candidateProfile: profile, feedbackContext: combinedFeedbackContext(),
          memoryContext: hunterMemory.buildContext(),
        }),
      });

      if (!res.ok || !res.body) {
        removeLoading();
        const err = await res.json().catch(() => null);
        addMessage({
          role: 'assistant',
          text: err?.error ?? 'Deu erro falando com o Hunter. Tenta de novo?',
        } as Omit<Message, 'id'>);
        return;
      }

      // Consome o stream — "tool_call" narra em tempo real (ver TOOL_PHRASES/
      // setLoadingPhase); "answer_chunk" vai construindo a resposta AO VIVO,
      // pedaço por pedaço, conforme o Gemini gera de verdade (ver
      // GeminiService.chatStream) — a bolha de loading vira uma mensagem de
      // verdade assim que o primeiro pedaço chega; "final" traz metadata
      // (toolResults/thinking/pendingQuestion) pra completar essa mesma
      // mensagem, sem duplicar o texto que já foi streamado. Se a conexão
      // cair no meio sem nenhum "final" chegar, finalData fica null e cai no
      // aviso de erro abaixo, em vez de travar esperando pra sempre.
      let finalData: JarvisChatResponse | null = null;
      let streamErrorMsg: string | null = null;
      let streamedMessageId: number | null = null;
      let streamedText = '';
      await consumeSseStream(res, (eventName, raw) => {
        if (eventName === 'tool_call') {
          const tool = (raw as { tool?: string }).tool;
          if (tool) setLoadingPhase(TOOL_PHRASES[tool] ?? `Usando ${tool}...`);
        } else if (eventName === 'answer_chunk') {
          const chunk = (raw as { text?: string }).text ?? '';
          streamedText += chunk;
          if (streamedMessageId === null) {
            removeLoading();
            const id = nextId++;
            streamedMessageId = id;
            patchActive(msgs => [...msgs, { id, role: 'assistant', text: streamedText } as Message]);
          } else {
            const id = streamedMessageId;
            const textoAtual = streamedText;
            patchActive(msgs => msgs.map(m => (m.id === id ? { ...m, text: textoAtual } : m)));
          }
        } else if (eventName === 'final') {
          finalData = raw as JarvisChatResponse;
        } else if (eventName === 'error') {
          streamErrorMsg = (raw as { error?: string }).error ?? 'Deu erro falando com o Hunter.';
        }
      });

      if (streamErrorMsg) {
        removeLoading();
        addMessage({ role: 'assistant', text: streamErrorMsg } as Omit<Message, 'id'>);
        return;
      }
      if (!finalData) {
        removeLoading();
        addMessage({ role: 'assistant', text: 'A conexão caiu no meio da resposta — tenta de novo?' } as Omit<Message, 'id'>);
        return;
      }

      const data = finalData as JarvisChatResponse;
      if (streamedMessageId !== null) {
        // Texto já foi construído ao vivo pelos "answer_chunk" — só completa
        // essa MESMA mensagem com a metadata, sem duplicar o texto.
        const id = streamedMessageId;
        patchActive(msgs => msgs.map(m => (m.id === id
          ? { ...m, toolResults: data.toolResults, thinking: data.thinking, pendingQuestion: data.pendingQuestion } as Message
          : m)));
      } else {
        // Nenhum "answer_chunk" chegou (ex: chatStream caiu pro modo
        // não-streaming internamente, ou a resposta foi um pendingQuestion,
        // que nunca é texto incremental) — mesmo caminho de sempre.
        removeLoading();
        addMessage({
          role: 'assistant',
          // BUG corrigido: quando tinha pendingQuestion, text ficava vazio —
          // a pergunta só existia visualmente (via PendingQuestionCard), nunca
          // ia pro histórico de texto puro que volta pro backend na PRÓXIMA
          // chamada (ver handleSend/historicoAnterior). Resultado: o Hunter
          // "esquecia" que tinha perguntado algo, porque a mensagem dele no
          // histórico aparecia como se não tivesse dito nada — daí ele
          // perguntava de novo e de novo, achando que ainda não tinha
          // perguntado. Agora o texto da pergunta vai pro histórico também,
          // só a INTERFACE prioriza mostrar o card em vez do texto solto.
          text: data.pendingQuestion ? data.pendingQuestion.pergunta : (data.reply ?? 'Não consegui gerar uma resposta dessa vez — tenta reformular?'),
          toolResults: data.toolResults,
          thinking: data.thinking,
          pendingQuestion: data.pendingQuestion,
        } as Omit<Message, 'id'>);
      }

      // Ferramentas que mudam dado de verdade no banco — avisa o App.tsx pra
      // recarregar a lista, senão o card só refletiria após um F5. Passa o
      // vagaId (quando a ferramenta devolveu) pra ligar o pulso visual
      // naquele card específico.
      const escritaOk = data.toolResults?.find(
        tr => (tr.tool === 'marcarStatusDeVaga' || tr.tool === 'atualizarNotaDeVaga' || tr.tool === 'fixarVaga'
          || tr.tool === 'adicionarVagaManual' || tr.tool === 'apagarVaga')
          && (tr.data as { sucesso?: boolean })?.sucesso
      );
      if (escritaOk) onJobsChanged?.((escritaOk.data as { vagaId?: number }).vagaId);

      // lembrarPreferencia não muda nada no banco — quem persiste é o
      // frontend mesmo (ver useHunterMemory), o backend só devolve o texto.
      const lembrou = data.toolResults?.find(
        tr => tr.tool === 'lembrarPreferencia' && (tr.data as { sucesso?: boolean })?.sucesso
      );
      if (lembrou) hunterMemory.add((lembrou.data as { texto: string }).texto);

      refreshAiStatus();
    } catch (e) {
      removeLoading();
      // AbortError: o próprio usuário clicou em "Parar" (ver handleStop) —
      // não é erro de verdade, não precisa de mensagem alarmante.
      if (e instanceof DOMException && e.name === 'AbortError') {
        addMessage({ role: 'assistant', text: 'Interrompido.' } as Omit<Message, 'id'>);
      } else {
        addMessage({ role: 'assistant', text: 'Deu erro de conexão com o backend. Tenta de novo?' } as Omit<Message, 'id'>);
      }
    } finally {
      setBusy(false);
      abortControllerRef.current = null;
    }
  };

  const handleStop = () => {
    abortControllerRef.current?.abort();
  };

  // Editar e reenviar — diferente de "regenerar" (que gastaria cota de IA
  // pra talvez repetir a mesma resposta): isso corrige o pedido mal
  // formulado, que é o caso real mais comum. Trunca a conversa a partir
  // dessa mensagem (ela e tudo que veio depois somem) e joga o texto de
  // volta no campo pra reenviar já editado.
  const handleEditMessage = (messageId: number) => {
    if (busy) return;
    const msg = messages.find(m => m.id === messageId);
    if (!msg || msg.role !== 'user') return;
    patchActive(msgs => {
      const idx = msgs.findIndex(m => m.id === messageId);
      return idx === -1 ? msgs : msgs.slice(0, idx);
    });
    setInput(msg.text);
    textareaRef.current?.focus();
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

  // Ditado por voz: clica, fala, o texto vai aparecendo no campo em tempo
  // real (resultados "interim" vão sendo substituídos até o trecho fechar
  // como "final"). Soma em cima do que já tinha digitado, não substitui.
  const handleToggleMic = () => {
    if (listening) {
      recognitionRef.current?.stop();
      return;
    }
    const Ctor = window.SpeechRecognition ?? window.webkitSpeechRecognition;
    if (!Ctor) return;
    const recognition = new Ctor();
    recognition.lang = 'pt-BR';
    recognition.continuous = true;
    recognition.interimResults = true;
    let textoBase = input;
    recognition.onresult = event => {
      let interim = '';
      let final = '';
      for (let i = event.resultIndex; i < event.results.length; i++) {
        const resultado = event.results[i];
        if (resultado.isFinal) final += resultado[0].transcript;
        else interim += resultado[0].transcript;
      }
      if (final) textoBase = (textoBase ? textoBase + ' ' : '') + final.trim();
      setInput((textoBase + (interim ? ' ' + interim : '')).trim());
      setHistoryNav(null);
    };
    recognition.onerror = () => setListening(false);
    recognition.onend = () => setListening(false);
    recognitionRef.current = recognition;
    recognition.start();
    setListening(true);
  };

  // Se o painel fechar/desmontar enquanto ainda está ouvindo, para o
  // microfone junto — senão fica gravando escondido sem o usuário perceber.
  useEffect(() => () => { recognitionRef.current?.stop(); }, []);

  return createPortal(
    <div className={`jarvis-panel ${closing ? 'jarvis-panel--closing' : ''}`} role="dialog" aria-modal="false" aria-label="Hunter, assistente do Job Radar">
      <div
        className={`jarvis-resize-handle ${resizing ? 'jarvis-resize-handle--active' : ''}`}
        onPointerDown={handleResizeStart}
        title="Arraste pra redimensionar"
      />
      <div className="jarvis-header">
        <span className="jarvis-header-title">
          {view === 'history' ? <><HistoryIcon size={16} /> Histórico</> : <><HunterIcon size={19} alive /> Hunter</>}
        </span>
        <div className="jarvis-header-actions">
          <button
            className="jarvis-header-icon-btn"
            onClick={() => setView(v => (v === 'history' ? 'chat' : 'history'))}
            title={view === 'history' ? 'Voltar pro chat' : 'Ver conversas anteriores'}
          >
            {view === 'history' ? <ChatBubbleIcon /> : <HistoryIcon />}
          </button>
          <button
            className={`jarvis-header-icon-btn ${compactMode ? 'jarvis-header-icon-btn--active' : ''}`}
            onClick={() => setCompactMode(c => !c)}
            title={compactMode ? 'Mostrar cards visuais' : 'Modo compacto (só texto)'}
            aria-label="Alternar modo compacto"
          >
            <CompactIcon />
          </button>
          {hunterMemory.items.length > 0 && (
            <div className="jarvis-memory-wrap">
              <button
                className={`jarvis-header-icon-btn ${memoryOpen ? 'jarvis-header-icon-btn--active' : ''}`}
                onClick={() => setMemoryOpen(o => !o)}
                title="Preferências que o Hunter lembra entre conversas"
                aria-label="Ver preferências lembradas"
              >
                <ThinkingIcon />
              </button>
              {memoryOpen && (
                <div className="jarvis-memory-popover">
                  <div className="jarvis-memory-popover-head">
                    <span>O que o Hunter lembra</span>
                    <button
                      className="jarvis-history-item-delete"
                      onClick={hunterMemory.clear}
                      title="Esquecer tudo"
                      aria-label="Esquecer tudo"
                    >
                      <TrashIcon />
                    </button>
                  </div>
                  <ul className="jarvis-memory-list">
                    {hunterMemory.items.map(item => (
                      <li key={item.texto}>
                        <span>{item.texto}</span>
                        <button
                          className="jarvis-memory-remove"
                          onClick={() => hunterMemory.remove(item.texto)}
                          aria-label={`Esquecer "${item.texto}"`}
                        >
                          <CloseIcon size={10} />
                        </button>
                      </li>
                    ))}
                  </ul>
                </div>
              )}
            </div>
          )}
          <button className="jarvis-header-icon-btn" onClick={handleNewConversation} title="Nova conversa"><PlusIcon /></button>
          <button className="jarvis-header-icon-btn" onClick={handleRequestClose} aria-label="Fechar"><CloseIcon size={13} /></button>
        </div>
      </div>

      {quotaBaixa && view === 'chat' && (
        <p className="jarvis-quota-warning">
          <WarningIcon /> Cota da IA quase no fim hoje ({keyPool!.availableToday} de {keyPool!.total} keys
          disponíveis) — respostas podem parar de funcionar até amanhã.
        </p>
      )}

      {view === 'history' ? (
        <HistoryView
          conversations={conversations}
          activeId={resolvedActiveId}
          onSelect={handleSelectConversation}
          onDelete={handleDeleteConversation}
          onRename={handleRenameConversation}
          onExport={handleExportConversation}
        />
      ) : (
        <>
          <div className="jarvis-messages" ref={scrollRef} role="log" aria-live="polite" aria-relevant="additions">
            {messages.map((m, idx) => {
              // "Novo turno" = mudou quem está falando (ou é a primeira
              // mensagem) — só nesse caso soma o respiro extra entre bolhas.
              // 'assistant-loading' conta como 'assistant' pra essa
              // comparação (é uma resposta ainda chegando, mesmo remetente).
              // Hoje a conversa sempre alterna user/assistant estritamente,
              // então isso quase sempre dá "novo turno" — mas fica pronto
              // pra qualquer sequência do mesmo remetente ficar mais colada
              // visualmente, sem repetir o respiro de sempre.
              const roleAnterior = idx > 0 ? messages[idx - 1].role : null;
              const efetivo = (r: Message['role']) => (r === 'assistant-loading' ? 'assistant' : r);
              const novoTurno = roleAnterior === null || efetivo(roleAnterior) !== efetivo(m.role);
              const turnoClass = novoTurno ? 'jarvis-msg-turn-gap' : '';

              if (m.role === 'condensed') {
                return (
                  <div key={m.id} className={`jarvis-condensed ${turnoClass}`}>
                    — {m.count} mensagem{m.count === 1 ? '' : 's'} mais antiga{m.count === 1 ? '' : 's'} omitida{m.count === 1 ? '' : 's'} —
                  </div>
                );
              }
              if (m.role === 'user') {
                return (
                  <div key={m.id} className={`jarvis-user-row ${turnoClass}`}>
                    <div className="jarvis-bubble jarvis-bubble--user">
                      {m.imageDataUrl && <img src={m.imageDataUrl} alt="Print anexado" className="jarvis-msg-image" />}
                      {m.text}
                    </div>
                    {!busy && (
                      <button
                        type="button"
                        className="jarvis-edit-msg-btn"
                        onClick={() => handleEditMessage(m.id)}
                        title="Editar e reenviar"
                        aria-label="Editar e reenviar essa mensagem"
                      >
                        <PencilIcon size={11} />
                      </button>
                    )}
                  </div>
                );
              }
              if (m.role === 'assistant-loading') {
                // A mensagem logo antes da de loading é sempre a pergunta que
                // disparou essa espera (ver handleSend) — usa o texto dela
                // pra escolher frases relacionadas ao que foi pedido, em vez
                // de um ciclo genérico sempre igual.
                const gatilho = messages[idx - 1];
                return (
                  <div key={m.id} className={`jarvis-msg-row ${turnoClass}`}>
                    <span className="jarvis-avatar"><HunterIcon size={22} alive /></span>
                    <div className="jarvis-bubble jarvis-bubble--assistant jarvis-bubble--loading">
                      <ElapsedTimer />
                      <LoadingPhrase
                        userText={gatilho?.role === 'user' ? gatilho.text : ''}
                        hasImage={gatilho?.role === 'user' && !!gatilho.imageDataUrl}
                        livePhrase={m.livePhrase}
                      />
                    </div>
                  </div>
                );
              }
              // Uma pergunta pendente só continua "ao vivo" (clicável +
              // animação de "?") enquanto for a última mensagem — assim que
              // o usuário responde, a resposta vira a próxima mensagem (role
              // 'user') e a pergunta passa a mostrar só o que foi escolhido.
              const isLastMsg = idx === messages.length - 1;
              const proximaMsg = messages[idx + 1];
              const respostaDada = m.pendingQuestion && proximaMsg?.role === 'user' ? proximaMsg.text : undefined;
              const perguntaAoVivo = !!m.pendingQuestion && isLastMsg;

              return (
                <div key={m.id} className={`jarvis-msg-row ${turnoClass}`}>
                  <span className="jarvis-avatar"><HunterIcon size={22} alive questioning={perguntaAoVivo} /></span>
                  <div className="jarvis-bubble jarvis-bubble--assistant">
                    {m.thinking && <ThinkingBlock thinking={m.thinking} />}
                    {/* Modo compacto esconde os cards visuais de ferramenta
                        (listas, dashboards, comparações) — a pergunta
                        interativa NUNCA soma nessa regra, ela é a própria
                        interface de resposta, não um "extra" decorativo. */}
                    {!compactMode && m.toolResults && m.toolResults.length > 0 && (
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
                    {m.pendingQuestion ? (
                      <PendingQuestionCard
                        question={m.pendingQuestion}
                        locked={!perguntaAoVivo}
                        answeredWith={respostaDada}
                        onAnswer={texto => handleSend(texto)}
                      />
                    ) : (
                      <>
                        {renderMarkdownLite(m.text)}
                        {!m.isGreeting && <ChatMessageActions text={m.text} featureKey="jarvis-chat" />}
                      </>
                    )}
                  </div>
                </div>
              );
            })}

            {messages.length <= 1 && (
              <div className="jarvis-suggestions">
                {SUGESTOES.map(s => (
                  <button key={s.text} className="jarvis-suggestion-card" onClick={() => handleSend(s.text)} disabled={busy}>
                    <span className="jarvis-suggestion-icon"><s.Icon /></span>
                    <span>{s.text}</span>
                  </button>
                ))}
              </div>
            )}
          </div>

          {attachError && <p className="jarvis-attach-error"><WarningIcon /> {attachError}</p>}

          {attachedImage && (
            <div className="jarvis-attach-preview">
              <img src={attachedImage.dataUrl} alt="Print a anexar" />
              <button type="button" className="jarvis-attach-remove" onClick={() => setAttachedImage(null)} aria-label="Remover imagem"><CloseIcon /></button>
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
              <ClipIcon />
            </button>
            <textarea
              ref={textareaRef}
              className="jarvis-input"
              rows={1}
              value={input}
              onChange={e => { setInput(e.target.value); setHistoryNav(null); }}
              onKeyDown={handleInputKeyDown}
              onPaste={handlePaste}
              placeholder="Pergunte algo pro Hunter..."
              disabled={busy}
            />
            {micSupported && (
              <button
                type="button"
                className={`jarvis-mic-btn ${listening ? 'jarvis-mic-btn--active' : ''}`}
                onClick={handleToggleMic}
                disabled={busy}
                aria-label={listening ? 'Parar ditado' : 'Ditar por voz'}
                title={listening ? 'Parar ditado' : 'Ditar por voz'}
              >
                <MicIcon />
              </button>
            )}
            {busy ? (
              <button type="button" className="jarvis-send-btn jarvis-send-btn--stop" onClick={handleStop} aria-label="Parar" title="Parar">
                <CloseIcon size={12} />
              </button>
            ) : (
              <button type="submit" className="jarvis-send-btn" disabled={!input.trim() && !attachedImage} aria-label="Enviar">➤</button>
            )}
          </form>
        </>
      )}
    </div>,
    document.body
  );
}

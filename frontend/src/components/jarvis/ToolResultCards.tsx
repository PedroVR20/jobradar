import { useState } from 'react';
import {
  JarvisAcaoVaga,
  JarvisAdicionarVagaData,
  JarvisApagarVagaData,
  JarvisAtualizarNotaData,
  JarvisBuscaSemanticaData,
  JarvisCartaData,
  JarvisCompararMercadoData,
  JarvisCompatibilidadeData,
  JarvisCompatibilidadeHit,
  JarvisDetalharVagasData,
  JarvisDuplicatasData,
  JarvisEmailVagasData,
  JarvisFixarVagaData,
  JarvisFontesData,
  JarvisHistoricoEmpresaData,
  JarvisLembrarData,
  JarvisLembreteAgendaData,
  JarvisListarVagasData,
  JarvisMarcarStatusData,
  JarvisMetricasData,
  JarvisOQueFazerAgoraData,
  JarvisPrazoData,
  JarvisResumoFunilData,
  JarvisSalarioData,
  JarvisSalarioVaga,
  JarvisToolResult,
  JarvisVagasParadasData,
  JarvisVagasParecidasData,
  LearningPlan,
} from '../../types/Job';
import { useAiFeedback } from '../../hooks/useAiFeedback';
import { useAgenda } from '../../hooks/useAgenda';
import {
  BookIcon, CheckIcon, CopyIcon, NoteIcon, SuccessIcon,
  ThinkingIcon, ThumbDownIcon, ThumbUpIcon, TimerIcon, WarningIcon,
} from '../HunterMiniIcons';

// Fase 5.2 — cards de resultado de cada ferramenta do Hunter, extraídos de
// JarvisPanel.tsx (que tinha passado de 3000 linhas). Cada função aqui
// renderiza o resultado de UMA ferramenta específica (ver o switch em
// ToolResultCard, o dispatcher no fim do arquivo) — puramente apresentação,
// sem lógica de orquestração do chat em si (isso continua em JarvisPanel.tsx).

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
export function CompactFeedback({ featureKey }: { featureKey: string }) {
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

// Vagas achadas em emails de alerta de vaga (LinkedIn, Glassdoor — ver
// GmailService) — NADA entra no banco sozinho, o usuário marca quais quer
// e clica em adicionar. Reaproveita o mesmo POST /api/jobs/manual que o
// botão "➕ Adicionar vaga" da tela principal usa, então cai no mesmo dedupe
// por URL (mandar a mesma vaga duas vezes não duplica).
function EmailVagasCard({ data, onJobsChanged }: { data: JarvisEmailVagasData; onJobsChanged?: (vagaId?: number) => void }) {
  const [selecionadas, setSelecionadas] = useState<Set<number>>(new Set());
  const [status, setStatus] = useState<'idle' | 'adicionando' | 'feito'>('idle');
  const [resultado, setResultado] = useState<{ ok: number; falhas: number } | null>(null);

  if (!data.conectado) {
    return (
      <p className="jarvis-scan-warning">
        <WarningIcon /> Gmail não conectado — abra ⚙️ Configurações e clique em "Conectar Gmail" primeiro.
      </p>
    );
  }
  if (data.erro) {
    return <p className="jarvis-scan-warning"><WarningIcon /> {data.erro}</p>;
  }
  if (data.vagas.length === 0) {
    return <p className="jarvis-scan-intro">Nenhuma vaga achada nos emails desse período.</p>;
  }

  const toggle = (idx: number) => {
    setSelecionadas(prev => {
      const next = new Set(prev);
      if (next.has(idx)) next.delete(idx); else next.add(idx);
      return next;
    });
  };

  const handleAdicionar = async () => {
    setStatus('adicionando');
    let ok = 0, falhas = 0;
    for (const idx of selecionadas) {
      const v = data.vagas[idx];
      try {
        const res = await fetch('/api/jobs/manual', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            title: v.titulo,
            company: v.empresa && v.empresa.trim() ? v.empresa : `${v.fonte} (empresa não identificada)`,
            url: v.url,
            source: `${v.fonte.toUpperCase()}_EMAIL`,
            status: 'NOVA',
          }),
        });
        if (res.ok) ok++; else falhas++;
      } catch {
        falhas++;
      }
    }
    setResultado({ ok, falhas });
    setStatus('feito');
    setSelecionadas(new Set());
    if (ok > 0) onJobsChanged?.();
  };

  return (
    <div className="jarvis-email-vagas">
      <ul className="jarvis-email-vagas-list">
        {data.vagas.map((v, idx) => (
          <li key={v.url}>
            <label className="jarvis-email-vaga-item">
              <input
                type="checkbox"
                checked={selecionadas.has(idx)}
                onChange={() => toggle(idx)}
                disabled={status === 'adicionando'}
              />
              <span className="jarvis-email-vaga-info">
                <a href={v.url} target="_blank" rel="noopener noreferrer" onClick={e => e.stopPropagation()}>{v.titulo}</a>
                <span className="jarvis-hit-company">{v.empresa ?? '—'} · {v.fonte}</span>
              </span>
            </label>
          </li>
        ))}
      </ul>
      {status !== 'feito' && (
        <button
          type="button"
          className="jarvis-retry-btn"
          onClick={handleAdicionar}
          disabled={selecionadas.size === 0 || status === 'adicionando'}
        >
          {status === 'adicionando' ? 'Adicionando...' : `Adicionar selecionadas (${selecionadas.size})`}
        </button>
      )}
      {resultado && (
        <p className="jarvis-hit-resumo jarvis-hit-resumo--icon">
          <SuccessIcon /> {resultado.ok} adicionada{resultado.ok === 1 ? '' : 's'}
          {resultado.falhas > 0 ? `, ${resultado.falhas} falharam` : ''}.
        </p>
      )}
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

export function ToolResultCard({ result, candidateProfile, planFeedbackContext, onJobsChanged }: {
  result: JarvisToolResult; candidateProfile: string; planFeedbackContext: string; onJobsChanged?: (vagaId?: number) => void;
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
    case 'verificarEmailsDeVagas':
      return <EmailVagasCard data={result.data as JarvisEmailVagasData} onJobsChanged={onJobsChanged} />;
    default:
      return null;
  }
}

import { useEffect, useMemo, useState } from 'react';
import { Job, JobStatus, seniorityMeta, workplaceMeta } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { useQuickMatchScores } from '../hooks/useQuickMatchScores';

interface Props {
  onSeen: (id: number) => Promise<void> | void;
  onSetStatus: (id: number, status: JobStatus) => Promise<void> | void;
  onClose: () => void;
  // Fase 9.1 — o botão de pré-triagem assistida só aparece com Gemini
  // configurado (mesmo gate que os outros botões de IA por vaga).
  aiEnabled: boolean;
}

// Fase 9.1 — resposta de POST /api/jobs/triage-batch.
type TriageVeredito = 'RECOMENDADA' | 'TALVEZ' | 'DESCARTAR';
interface TriageVerdict { jobId: number; veredito: TriageVeredito; motivo: string }

const VEREDITO_META: Record<TriageVeredito, { label: string; className: string }> = {
  RECOMENDADA: { label: '✅ Recomendada', className: 'triage-veredito--recomendada' },
  TALVEZ: { label: '🤔 Talvez', className: 'triage-veredito--talvez' },
  DESCARTAR: { label: '🚫 Descartar', className: 'triage-veredito--descartar' },
};

// Modo "Triagem rápida" — resposta ao gargalo real do app: milhares de vagas
// NOVAS acumuladas que ninguém revisa uma por uma na lista normal (rolar uma
// lista de 4700+ cards não é fluxo, é trabalho). Aqui é uma vaga de cada vez,
// decisão binária rápida (⭐ interessa / ❌ não interessa / pular).
//
// Fase 8.1 — antes disso, a ordem vinha SÓ do pré-filtro heurístico local
// (useQuickMatchScores, sobreposição simples de tags do perfil), ignorando
// completamente o ranking pessoal de verdade (PersonalRankingService, Naive
// Bayes treinado no histórico de interesse/aplicação/recusa — ver Fase 3.1)
// que a lista normal já usa via sort=personal. Agora pede sort=personal ao
// backend quando o modelo já tem dado suficiente (mesmo endpoint que
// alimenta o seletor de ordenação da lista normal); sem modelo treinado
// ainda, cai pro heurístico local como antes.
//
// Também corrigido aqui: a busca usava `fetch('/api/jobs?...')` esperando
// devolver um array — GET /api/jobs devolve página (JobPageResult) desde a
// Fase 6.3, então `data` era na verdade `{content, totalElements, ...}`, e
// espalhar isso com `[...jobs]` mais abaixo lançava TypeError em runtime
// (objeto simples não é iterável) toda vez que a triagem carregava com
// vaga disponível — a tela nunca chegava a mostrar um card. `size=300`
// porque triagem é uma sessão de decisão em lote, não precisa de mais que
// isso de uma vez (dá pra reabrir pra continuar).
export function TriageModal({ onSeen, onSetStatus, onClose, aiEnabled }: Props) {
  const { profile } = useCandidateProfile();
  const { scores, loading: scoring, refresh } = useQuickMatchScores();
  const [jobs, setJobs] = useState<Job[] | null>(null);
  const [rankingPersonalDisponivel, setRankingPersonalDisponivel] = useState(false);
  const [index, setIndex] = useState(0);
  const [decided, setDecided] = useState(0);

  // Fase 9.1 — pré-triagem assistida em lote: veredictos guardados por
  // jobId (não recalcula ao voltar/avançar entre vagas já trioadas nessa
  // sessão) e um erro específico dessa ação (não reaproveita nenhum outro
  // estado de erro do modal, é uma falha independente das outras).
  const [veredictos, setVeredictos] = useState<Record<number, TriageVerdict>>({});
  const [triandoLote, setTriandoLote] = useState(false);
  const [triageError, setTriageError] = useState('');

  useEffect(() => {
    fetch('/api/jobs/personal-ranking-status')
      .then(r => r.json())
      .then(status => {
        const disponivel = !!status.disponivel;
        setRankingPersonalDisponivel(disponivel);
        const sort = disponivel ? 'personal' : 'posted_desc';
        return fetch(`/api/jobs?onlyNew=true&sort=${sort}&size=300`);
      })
      .then(r => r.json())
      .then(data => setJobs(data.content ?? []))
      .catch(() => setJobs([]));
    if (profile.trim()) refresh(profile);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Com ranking pessoal disponível, a ordem já vem certa do backend — não
  // sobrescreve com o heurístico local (mais fraco: só sobreposição de tag,
  // sem levar em conta senioridade/modalidade/fonte que o modelo aprendeu).
  const ordenadas = useMemo(() => {
    if (!jobs) return [];
    if (rankingPersonalDisponivel || Object.keys(scores).length === 0) return jobs;
    return [...jobs].sort((a, b) => (scores[String(b.id)] ?? -1) - (scores[String(a.id)] ?? -1));
  }, [jobs, scores, rankingPersonalDisponivel]);

  const atual = ordenadas[index];
  const acabou = jobs !== null && (ordenadas.length === 0 || index >= ordenadas.length);

  const avancar = () => setIndex(i => i + 1);

  const handleDecide = async (acao: 'interessa' | 'descarta' | 'pula') => {
    if (!atual) return;
    setDecided(d => d + 1);
    if (acao === 'interessa') await onSetStatus(atual.id, 'INTERESSADO');
    else if (acao === 'descarta') await onSetStatus(atual.id, 'RECUSADA');
    else await onSeen(atual.id);
    avancar();
  };

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') { onClose(); return; }
      if (acabou || !atual) return;
      if (e.key === 'ArrowRight' || e.key.toLowerCase() === 'y') handleDecide('interessa');
      else if (e.key === 'ArrowLeft' || e.key.toLowerCase() === 'n') handleDecide('descarta');
      else if (e.key === ' ' || e.key.toLowerCase() === 's') { e.preventDefault(); handleDecide('pula'); }
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [atual, acabou]);

  const matchPercent = atual ? scores[String(atual.id)] : undefined;
  const veredictoAtual = atual ? veredictos[atual.id] : undefined;

  // Fase 9.1 — pega até MAX_LOTE (20, espelha AiTriageService.MAX_LOTE)
  // vagas a partir da posição atual que AINDA não têm veredito guardado
  // (evita re-triar vaga que já foi avaliada nessa sessão e gastar
  // orçamento de IA à toa).
  const triarProximoLote = async () => {
    if (!profile.trim()) return;
    const proximas = ordenadas.slice(index).filter(j => !(j.id in veredictos)).slice(0, 20);
    if (proximas.length === 0) return;

    setTriandoLote(true);
    setTriageError('');
    try {
      const res = await fetch('/api/jobs/triage-batch', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ jobIds: proximas.map(j => j.id), profile }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => null);
        setTriageError(data?.error ?? 'Não consegui rodar a pré-triagem agora.');
        return;
      }
      const data = await res.json();
      const novos: Record<number, TriageVerdict> = {};
      (data.veredictos as TriageVerdict[]).forEach(v => { novos[v.jobId] = v; });
      setVeredictos(prev => ({ ...prev, ...novos }));
    } catch {
      setTriageError('Erro de conexão com o backend.');
    } finally {
      setTriandoLote(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal triage-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>⚡</span>
          <h2>Triagem rápida</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {jobs === null ? (
          <p className="agenda-hint" style={{ padding: '1rem' }}>Carregando vagas não vistas...</p>
        ) : acabou ? (
          <div className="triage-done">
            <p className="triage-done-emoji">🎉</p>
            <p>{decided > 0 ? `Triou ${decided} vaga${decided === 1 ? '' : 's'}!` : 'Nenhuma vaga nova pra triar agora.'}</p>
            <button className="btn btn-primary" onClick={onClose}>Fechar</button>
          </div>
        ) : (
          <>
            <div className="triage-progress">
              <span>{index + 1} de {ordenadas.length}</span>
              {rankingPersonalDisponivel ? (
                <span className="triage-progress-hint">ordenado pelo seu ranking pessoal</span>
              ) : profile.trim() && (
                <span className="triage-progress-hint">
                  {scoring ? 'calculando pré-filtro...' : 'ordenado por provável match'}
                </span>
              )}
            </div>

            {aiEnabled && profile.trim() && (
              <div className="triage-batch-row">
                <button
                  type="button"
                  className="btn btn-ghost"
                  onClick={triarProximoLote}
                  disabled={triandoLote}
                  title="Pede pro Gemini uma opinião rápida (recomendada/talvez/descartar) pras próximas vagas do lote — você ainda decide cada uma"
                >
                  {triandoLote ? '🤖 Triando...' : '🤖 Pré-triagem assistida (próximas 20)'}
                </button>
                {triageError && <span className="agenda-error">{triageError}</span>}
              </div>
            )}

            <div className="triage-card">
              {matchPercent != null && matchPercent >= 50 && (
                <span className="badge-match" style={{ marginBottom: '0.4rem', display: 'inline-block' }}>
                  🎯 {matchPercent}% match
                </span>
              )}
              {veredictoAtual && (
                <span
                  className={`triage-veredito ${VEREDITO_META[veredictoAtual.veredito].className}`}
                  title={veredictoAtual.motivo}
                >
                  {VEREDITO_META[veredictoAtual.veredito].label} — {veredictoAtual.motivo}
                </span>
              )}
              <h3 className="triage-card-title">{atual.title}</h3>
              <p className="triage-card-company">🏢 {atual.company}</p>
              <p className="triage-card-meta">
                {atual.workplaceType && <span>{workplaceMeta[atual.workplaceType].icon} {workplaceMeta[atual.workplaceType].label}</span>}
                {atual.seniority !== 'NAO_INFORMADO' && <span>{seniorityMeta[atual.seniority].label}</span>}
                {atual.salary && <span>💰 {atual.salary}</span>}
                {(atual.city || atual.state) && <span>📍 {[atual.city, atual.state].filter(Boolean).join(' - ')}</span>}
              </p>
              {atual.tags.length > 0 && (
                <div className="card-tags">
                  {atual.tags.slice(0, 8).map(t => <span key={t} className="tag tag--normal">{t}</span>)}
                </div>
              )}
              <a href={atual.url} target="_blank" rel="noopener noreferrer" className="btn btn-ghost triage-card-link">
                Ver vaga completa →
              </a>
            </div>

            <div className="triage-actions">
              <button className="btn btn-danger" onClick={() => handleDecide('descarta')} title="Atalho: ← ou N">
                ❌ Não interessa
              </button>
              <button className="btn btn-ghost" onClick={() => handleDecide('pula')} title="Atalho: Espaço ou S">
                ⏭ Pular
              </button>
              <button className="btn btn-interested" onClick={() => handleDecide('interessa')} title="Atalho: → ou Y">
                ⭐ Interessa!
              </button>
            </div>
            <p className="triage-shortcuts-hint">← não interessa · espaço pula · → interessa · Esc fecha</p>
          </>
        )}
      </div>
    </div>
  );
}

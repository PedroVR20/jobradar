import { FormEvent, useRef, useState } from 'react';
import { Job, JobStatus, RejectedReason } from '../types/Job';
import { JobCard } from './JobCard';
import { SkeletonLines } from './SkeletonCard';
import { useEscapeToClose } from '../hooks/useEscapeToClose';

interface Props {
  onClose: () => void;
  onSeen: (id: number) => void;
  onApplied: (id: number) => void;
  onInProgress: (id: number) => void;
  onSetStatus: (id: number, status: JobStatus, motivo?: RejectedReason) => void;
  onTogglePin: (id: number) => void;
  onUpdateNotes: (id: number, notes: string) => void;
  onToast: (msg: string) => void;
  aiEnabled: boolean;
}

interface SemanticSearchResult {
  job: Job;
  similaridadePercent: number;
}

// Fase 9.2 do plano — a busca por significado já existia desde a Fase 6
// (o Hunter usa via ferramenta buscarVagasPorSignificado no chat), só não
// tinha um jeito de chegar nela sem conversar. Mesmo endpoint
// (GET /api/jobs/semantic-search), sem gastar cota de IA generativa: o
// embedding roda no Hunter-Embed local (ver EmbeddingProvider no backend).
export function SemanticSearchModal({
  onClose, onSeen, onApplied, onInProgress, onSetStatus, onTogglePin, onUpdateNotes, onToast, aiEnabled,
}: Props) {
  useEscapeToClose(onClose);
  const [consulta, setConsulta] = useState('');
  const [buscando, setBuscando] = useState(false);
  const [resultados, setResultados] = useState<SemanticSearchResult[] | null>(null);
  const [erro, setErro] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const buscar = async (e: FormEvent) => {
    e.preventDefault();
    const termo = consulta.trim();
    if (!termo) return;
    setBuscando(true);
    setErro(null);
    try {
      const res = await fetch(`/api/jobs/semantic-search?consulta=${encodeURIComponent(termo)}&limite=20`);
      const data = await res.json();
      if (!res.ok) {
        setErro(data.error ?? 'Não consegui buscar agora.');
        setResultados(null);
        return;
      }
      setResultados(data.resultados);
    } catch {
      setErro('Erro de conexão com o backend.');
      setResultados(null);
    } finally {
      setBuscando(false);
    }
  };

  // Remove da lista de resultados quando o status muda pra algo que não faz
  // mais sentido continuar vendo aqui (ex: recusou) — mesmo espírito do
  // DuplicatesModal, resultado de busca não é uma lista "viva" que
  // re-filtra sozinha, só reage a ações explícitas do usuário no card.
  const handleSetStatus = (id: number, status: JobStatus, motivo?: RejectedReason) => {
    onSetStatus(id, status, motivo);
    if (status === 'RECUSADA') {
      setResultados(prev => prev?.filter(r => r.job.id !== id) ?? null);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal semantic-search-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>🧠</span>
          <h2>Busca por significado</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Busca por SIGNIFICADO, não por texto exato — "infra em nuvem" encontra vagas de
          DevOps/SRE mesmo sem essas palavras no título. Roda 100% local (Hunter-Embed), não
          gasta cota de IA.
        </p>

        <form className="semantic-search-form" onSubmit={buscar}>
          <input
            ref={inputRef}
            className="search-input semantic-search-input"
            type="text"
            placeholder="ex: infraestrutura em nuvem, front-end com foco em acessibilidade..."
            value={consulta}
            onChange={e => setConsulta(e.target.value)}
            autoFocus
          />
          <button type="submit" className="btn btn-primary" disabled={buscando || !consulta.trim()}>
            {buscando ? 'Buscando…' : '🔍 Buscar'}
          </button>
        </form>

        {erro && <p className="agenda-error">{erro}</p>}

        {buscando ? (
          <SkeletonLines count={4} />
        ) : resultados === null ? null : resultados.length === 0 ? (
          <p className="agenda-hint">Nenhuma vaga parecida com isso no catálogo ativo.</p>
        ) : (
          <div className="semantic-search-results jobs-grid">
            {resultados.map(r => (
              <JobCard
                key={r.job.id}
                job={r.job}
                onSeen={onSeen}
                onApplied={onApplied}
                onInProgress={onInProgress}
                onSetStatus={handleSetStatus}
                onTogglePin={onTogglePin}
                onUpdateNotes={onUpdateNotes}
                onToast={onToast}
                aiEnabled={aiEnabled}
                sortMode="posted_desc"
                matchPercent={r.similaridadePercent}
                compact
              />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

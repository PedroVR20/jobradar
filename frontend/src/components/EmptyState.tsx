import { Filters, JobStatus, statusMeta, ViewMode } from '../types/Job';

// Espelha o mapeamento viewMode -> status que o backend/ViewTabs já usam
// pra filtrar cada aba — só pro rótulo amigável no vazio, não decide filtro.
const VIEW_MODE_TO_STATUS: Record<ViewMode, JobStatus | null> = {
  novas: 'NOVA',
  vistas: 'VISTA',
  interessado: 'INTERESSADO',
  aplicadas: 'APLICADA',
  andamento: 'ANDAMENTO',
  recusadas: 'RECUSADA',
};

interface Props {
  filters: Filters;
  onClearFilters: () => void;
  onClearDays: () => void;
  onFetchNow: () => void;
  fetching: boolean;
}

// Fase 4.7 — o vazio some sem ação nenhuma pro usuário: "Limpar filtros"
// já existia, mas aparecia igual mesmo quando NENHUM filtro estava ativo
// (ex: aba "Aplicadas" genuinamente sem nada ainda) — nesses casos limpar
// filtro não muda nada, é um botão morto. Diferencia os dois casos e, pra
// quem está na aba "Novas" sem filtro nenhum, sugere buscar agora em vez
// de só "limpar" algo que já está vazio.
function temFiltroAtivo(f: Filters): boolean {
  return Boolean(
    f.source || f.search || f.seniority || f.workplaceType || f.state ||
    f.days || f.beginnerMode || f.techStack.length > 0
  );
}

export function EmptyState({ filters, onClearFilters, onClearDays, onFetchNow, fetching }: Props) {
  const filtroAtivo = temFiltroAtivo(filters);

  if (filtroAtivo) {
    return (
      <div className="empty-box">
        <p>😶 Nenhuma vaga encontrada com esses filtros.</p>
        <div className="empty-box-actions">
          <button className="btn btn-primary" onClick={onClearFilters}>
            Limpar filtros
          </button>
          {filters.days && (
            <button className="btn btn-ghost" onClick={onClearDays}>
              Ampliar período
            </button>
          )}
        </div>
      </div>
    );
  }

  // Sem filtro nenhum ativo: a aba em si que está vazia — a ação certa
  // depende de qual aba é. "Novas" pode genuinamente ter vaga esperando
  // (basta buscar); as outras (interessado/aplicadas/andamento/recusadas)
  // ficam vazias por não ter sido usada ainda, não é um problema a corrigir.
  if (filters.viewMode === 'novas') {
    return (
      <div className="empty-box">
        <p>🔭 Nenhuma vaga nova no momento.</p>
        <button className="btn btn-primary" onClick={onFetchNow} disabled={fetching}>
          {fetching ? 'Buscando...' : '🔄 Buscar agora'}
        </button>
      </div>
    );
  }

  const status = VIEW_MODE_TO_STATUS[filters.viewMode];
  const rotulo = status ? statusMeta[status] : 'essa aba';
  return (
    <div className="empty-box">
      <p>🗂️ Nenhuma vaga em "{rotulo}" ainda.</p>
      <p className="empty-box-hint">Marque uma vaga da aba "Novas" pra ela aparecer aqui.</p>
    </div>
  );
}

import { Stats, Seniority, seniorityMeta } from '../types/Job';

interface Props {
  stats: Stats;
  onFetch: () => void;
  fetching: boolean;
  activeSeniority: string;
  onSeniorityClick: (seniority: string) => void;
}

const sourceColors: Record<string, string> = {
  REMOTIVE: '#6366f1',
  ARBEITNOW: '#10b981',
  WWR: '#f59e0b',
  GUPY: '#ec4899',
};

const seniorityOrder: Seniority[] = ['ESTAGIO', 'JUNIOR', 'PLENO', 'SENIOR', 'NAO_INFORMADO'];

export function StatsBar({ stats, onFetch, fetching, activeSeniority, onSeniorityClick }: Props) {
  return (
    <div className="stats-bar">
      <div className="stats-grid">
        <div className="stat-card">
          <span className="stat-value">{stats.total}</span>
          <span className="stat-label">Total de vagas</span>
        </div>
        <div className="stat-card highlight">
          <span className="stat-value new">{stats.novas}</span>
          <span className="stat-label">🔴 Não vistas</span>
        </div>
        <div className="stat-card">
          <span className="stat-value applied">{stats.aplicadas}</span>
          <span className="stat-label">✅ Aplicadas</span>
        </div>
        <div className="stat-card">
          <span className="stat-value today">{stats.hojeCount}</span>
          <span className="stat-label">📅 Últimas 24h</span>
        </div>
      </div>

      {/* Distribuição por senioridade — clique para filtrar */}
      {stats.porSenioridade && (
        <div className="seniority-row">
          {seniorityOrder.map(key => {
            const meta = seniorityMeta[key];
            const count = stats.porSenioridade[key] ?? 0;
            const isActive = activeSeniority === key;
            return (
              <button
                key={key}
                className={`seniority-pill ${isActive ? 'seniority-pill--active' : ''}`}
                style={{
                  borderColor: meta.color,
                  background: isActive ? meta.color + '33' : 'transparent',
                }}
                onClick={() => onSeniorityClick(isActive ? '' : key)}
                title={isActive ? 'Clique para remover o filtro' : `Filtrar por ${meta.short}`}
              >
                <span className="source-dot" style={{ background: meta.color }} />
                <span className="source-name">{meta.short}</span>
                <span className="source-count">{count}</span>
              </button>
            );
          })}
        </div>
      )}

      <div className="sources-row">
        {Object.entries(stats.porFonte).map(([source, count]) => (
          <div
            key={source}
            className="source-pill"
            style={{ borderColor: sourceColors[source] }}
          >
            <span
              className="source-dot"
              style={{ background: sourceColors[source] }}
            />
            <span className="source-name">{source}</span>
            <span className="source-count">{count}</span>
          </div>
        ))}

        <button
          className="fetch-btn"
          onClick={onFetch}
          disabled={fetching}
        >
          {fetching ? '⏳ Buscando...' : '🔄 Buscar agora'}
        </button>
      </div>
    </div>
  );
}

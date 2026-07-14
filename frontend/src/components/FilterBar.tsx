import { Filters, seniorityMeta, workplaceMeta } from '../types/Job';

interface Props {
  filters: Filters;
  onChange: (filters: Filters) => void;
  onClear: () => void;
  total: number;
  states: string[];
}

export function FilterBar({ filters, onChange, onClear, total, states }: Props) {
  const set = (partial: Partial<Filters>) =>
    onChange({ ...filters, ...partial });

  const hasActiveFilters =
    filters.search !== '' ||
    filters.source !== '' ||
    filters.seniority !== '' ||
    filters.workplaceType !== '' ||
    filters.state !== '' ||
    filters.days !== '';

  return (
    <div className="filter-bar">
      <div className="filter-row">
        <input
          className="search-input"
          type="text"
          placeholder="🔍 Buscar por título, empresa, tech... (vários termos = E)"
          value={filters.search}
          onChange={e => set({ search: e.target.value })}
        />

        <select
          className="filter-select"
          value={filters.source}
          onChange={e => set({ source: e.target.value })}
        >
          <option value="">Todas as fontes</option>
          <option value="REMOTIVE">Remotive</option>
          <option value="ARBEITNOW">Arbeitnow (EU)</option>
          <option value="WWR">We Work Remotely</option>
          <option value="GUPY">Gupy (BR)</option>
          <option value="EURECA">Eureca (BR)</option>
        </select>

        <select
          className="filter-select"
          value={filters.seniority}
          onChange={e => set({ seniority: e.target.value })}
        >
          <option value="">Todos os níveis</option>
          <option value="ESTAGIO">{seniorityMeta.ESTAGIO.label}</option>
          <option value="JUNIOR">{seniorityMeta.JUNIOR.label}</option>
          <option value="PLENO">{seniorityMeta.PLENO.label}</option>
          <option value="SENIOR">{seniorityMeta.SENIOR.label}</option>
          <option value="NAO_INFORMADO">Nível não informado</option>
        </select>

        <select
          className="filter-select"
          value={filters.workplaceType}
          onChange={e => set({ workplaceType: e.target.value })}
        >
          <option value="">Qualquer modalidade</option>
          <option value="REMOTO">{workplaceMeta.REMOTO.icon} 100% Remoto</option>
          <option value="HIBRIDO">{workplaceMeta.HIBRIDO.icon} Híbrido</option>
          <option value="PRESENCIAL">{workplaceMeta.PRESENCIAL.icon} Presencial</option>
        </select>

        <select
          className="filter-select"
          value={filters.state}
          onChange={e => set({ state: e.target.value })}
          disabled={states.length === 0}
        >
          <option value="">Todos os estados (BR)</option>
          {states.map(state => (
            <option key={state} value={state}>{state}</option>
          ))}
        </select>

        <select
          className="filter-select"
          value={filters.days}
          onChange={e => set({ days: e.target.value })}
        >
          <option value="">Qualquer data</option>
          <option value="1">Últimas 24h</option>
          <option value="3">Últimos 3 dias</option>
          <option value="7">Últimos 7 dias</option>
          <option value="14">Últimos 14 dias</option>
          <option value="30">Últimos 30 dias</option>
        </select>

        <select
          className="filter-select"
          value={filters.sort}
          onChange={e => set({ sort: e.target.value as Filters['sort'] })}
        >
          <option value="posted_desc">📅 Publicação ↓ (recentes)</option>
          <option value="posted_asc">📅 Publicação ↑ (antigas)</option>
          <option value="fetched_desc">🔄 Adicionadas recentemente</option>
        </select>

        {hasActiveFilters && (
          <button className="clear-filters-btn" onClick={onClear}>
            ✕ Limpar filtros
          </button>
        )}

        <span className="result-count">{total} vagas</span>
      </div>
    </div>
  );
}

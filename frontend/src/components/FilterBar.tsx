import { useEffect, useRef, useState } from 'react';
import { Filters, seniorityMeta, workplaceMeta } from '../types/Job';

interface Props {
  filters: Filters;
  onChange: (filters: Filters) => void;
  onClear: () => void;
  total: number;
  states: string[];
}

const LS_KEY = 'jobradar:tech-pills';

function loadPills(): string[] {
  try {
    return JSON.parse(localStorage.getItem(LS_KEY) ?? '[]');
  } catch {
    return [];
  }
}

function savePills(pills: string[]) {
  localStorage.setItem(LS_KEY, JSON.stringify(pills));
}

export function FilterBar({ filters, onChange, onClear, total, states }: Props) {
  const set = (partial: Partial<Filters>) => onChange({ ...filters, ...partial });

  const [pills, setPills] = useState<string[]>(loadPills);
  const [inputValue, setInputValue] = useState('');
  const inputRef = useRef<HTMLInputElement>(null);

  // sincroniza se outro tab mudar o localStorage
  useEffect(() => {
    const handler = () => setPills(loadPills());
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, []);

  const addPill = () => {
    const name = inputValue.trim().toLowerCase();
    if (!name || pills.includes(name)) {
      setInputValue('');
      return;
    }
    const updated = [...pills, name];
    setPills(updated);
    savePills(updated);
    setInputValue('');
  };

  const removePill = (name: string) => {
    const updated = pills.filter(p => p !== name);
    setPills(updated);
    savePills(updated);
    if (filters.techStack === name) set({ techStack: '' });
  };

  const togglePill = (name: string) =>
    set({ techStack: filters.techStack === name ? '' : name });

  const hasActiveFilters =
    filters.search !== '' ||
    filters.source !== '' ||
    filters.seniority !== '' ||
    filters.workplaceType !== '' ||
    filters.state !== '' ||
    filters.days !== '' ||
    filters.beginnerMode ||
    filters.techStack !== '';

  return (
    <div className="filter-bar">
      {/* Modo Iniciante + Tech Pills personalizadas */}
      <div className="filter-row filter-row--top">
        <button
          className={`beginner-mode-btn ${filters.beginnerMode ? 'beginner-mode-btn--active' : ''}`}
          onClick={() => set({ beginnerMode: !filters.beginnerMode, seniority: '' })}
          title="Mostra apenas vagas de Estágio e Júnior"
        >
          🎓 Modo Iniciante {filters.beginnerMode ? '(ativo)' : ''}
        </button>

        <div className="tech-stacks">
          {pills.map(name => (
            <span
              key={name}
              className={`tech-pill ${filters.techStack === name ? 'tech-pill--active' : ''}`}
            >
              <button
                className="tech-pill-label"
                onClick={() => togglePill(name)}
                title={`Filtrar por ${name}`}
              >
                {name}
              </button>
              <button
                className="tech-pill-remove"
                onClick={() => removePill(name)}
                title={`Remover pill "${name}"`}
                aria-label={`Remover ${name}`}
              >
                ×
              </button>
            </span>
          ))}

          {/* Input para criar nova pill */}
          <form
            className="tech-pill-form"
            onSubmit={e => { e.preventDefault(); addPill(); }}
          >
            <input
              ref={inputRef}
              className="tech-pill-input"
              type="text"
              placeholder="+ tecnologia"
              value={inputValue}
              onChange={e => setInputValue(e.target.value)}
              maxLength={30}
              title="Digite uma tecnologia e pressione Enter para criar um filtro rápido"
            />
          </form>
        </div>
      </div>

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
          onChange={e => set({ seniority: e.target.value, beginnerMode: false })}
          disabled={filters.beginnerMode}
          title={filters.beginnerMode ? 'Desative o Modo Iniciante para filtrar por nível manualmente' : ''}
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

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
  const [confirmDelete, setConfirmDelete] = useState<string | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  // posição do modal arrastável (offset relativo ao centro inicial)
  const [dragPos, setDragPos] = useState({ x: 0, y: 0 });
  const dragOrigin = useRef<{ mouseX: number; mouseY: number; posX: number; posY: number } | null>(null);

  // sincroniza se outro tab mudar o localStorage
  useEffect(() => {
    const handler = () => setPills(loadPills());
    window.addEventListener('storage', handler);
    return () => window.removeEventListener('storage', handler);
  }, []);

  const openConfirm = (name: string) => {
    setConfirmDelete(name);
    setDragPos({ x: 0, y: 0 }); // reseta posição ao abrir
  };

  const addPill = () => {
    const name = inputValue.trim().toLowerCase();
    if (!name || pills.includes(name)) { setInputValue(''); return; }
    const updated = [...pills, name];
    setPills(updated);
    savePills(updated);
    setInputValue('');
  };

  const removePill = (name: string) => {
    const updated = pills.filter(p => p !== name);
    setPills(updated);
    savePills(updated);
    // remove da seleção ativa se estiver selecionado
    if (filters.techStack.includes(name)) {
      set({ techStack: filters.techStack.filter(p => p !== name) });
    }
    setConfirmDelete(null);
  };

  // clique: adiciona ao array se não está, remove se já está (toggle multi-select OR)
  const togglePill = (name: string) =>
    set({
      techStack: filters.techStack.includes(name)
        ? filters.techStack.filter(p => p !== name)
        : [...filters.techStack, name],
    });

  // inicia o drag quando o usuário pressiona o cabeçalho do modal
  const handleDragStart = (e: React.MouseEvent) => {
    e.preventDefault();
    dragOrigin.current = {
      mouseX: e.clientX,
      mouseY: e.clientY,
      posX: dragPos.x,
      posY: dragPos.y,
    };

    const onMove = (ev: MouseEvent) => {
      if (!dragOrigin.current) return;
      setDragPos({
        x: dragOrigin.current.posX + (ev.clientX - dragOrigin.current.mouseX),
        y: dragOrigin.current.posY + (ev.clientY - dragOrigin.current.mouseY),
      });
    };

    const onUp = () => {
      dragOrigin.current = null;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };

    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  const hasActiveFilters =
    filters.search !== '' ||
    filters.source !== '' ||
    filters.seniority !== '' ||
    filters.workplaceType !== '' ||
    filters.state !== '' ||
    filters.days !== '' ||
    filters.beginnerMode ||
    filters.techStack.length > 0;

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
              className={`tech-pill ${filters.techStack.includes(name) ? 'tech-pill--active' : ''}`}
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
                onClick={() => openConfirm(name)}
                title={`Remover filtro "${name}"`}
                aria-label={`Remover ${name}`}
              >
                ×
              </button>
            </span>
          ))}

          <form className="tech-pill-form" onSubmit={e => { e.preventDefault(); addPill(); }}>
            <input
              ref={inputRef}
              className="tech-pill-input"
              type="text"
              placeholder="+ tecnologia"
              value={inputValue}
              onChange={e => setInputValue(e.target.value)}
              maxLength={30}
              title="Digite uma tecnologia e pressione Enter"
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

        <select className="filter-select" value={filters.source} onChange={e => set({ source: e.target.value })}>
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

        <select className="filter-select" value={filters.workplaceType} onChange={e => set({ workplaceType: e.target.value })}>
          <option value="">Qualquer modalidade</option>
          <option value="REMOTO">{workplaceMeta.REMOTO.icon} 100% Remoto</option>
          <option value="HIBRIDO">{workplaceMeta.HIBRIDO.icon} Híbrido</option>
          <option value="PRESENCIAL">{workplaceMeta.PRESENCIAL.icon} Presencial</option>
        </select>

        <select className="filter-select" value={filters.state} onChange={e => set({ state: e.target.value })} disabled={states.length === 0}>
          <option value="">Todos os estados (BR)</option>
          {states.map(state => <option key={state} value={state}>{state}</option>)}
        </select>

        <select className="filter-select" value={filters.days} onChange={e => set({ days: e.target.value })}>
          <option value="">Qualquer data</option>
          <option value="1">Últimas 24h</option>
          <option value="3">Últimos 3 dias</option>
          <option value="7">Últimos 7 dias</option>
          <option value="14">Últimos 14 dias</option>
          <option value="30">Últimos 30 dias</option>
        </select>

        <select className="filter-select" value={filters.sort} onChange={e => set({ sort: e.target.value as Filters['sort'] })}>
          <option value="posted_desc">📅 Publicação ↓ (recentes)</option>
          <option value="posted_asc">📅 Publicação ↑ (antigas)</option>
          <option value="fetched_desc">🔄 Adicionadas recentemente</option>
        </select>

        {hasActiveFilters && (
          <button className="clear-filters-btn" onClick={onClear}>✕ Limpar filtros</button>
        )}

        <span className="result-count">{total} vagas</span>
      </div>

      {/* Modal de confirmação — arrastável */}
      {confirmDelete && (
        <div className="pill-confirm-overlay" onClick={() => setConfirmDelete(null)}>
          <div
            className="pill-confirm-box"
            style={{ transform: `translate(calc(-50% + ${dragPos.x}px), calc(-50% + ${dragPos.y}px))` }}
            onClick={e => e.stopPropagation()}
          >
            {/* Cabeçalho que serve como alça de drag */}
            <div className="pill-confirm-header" onMouseDown={handleDragStart}>
              <span className="pill-confirm-grip">⠿⠿⠿</span>
              <span className="pill-confirm-title">Remover filtro</span>
            </div>

            <div className="pill-confirm-body">
              <p className="pill-confirm-text">
                Tem certeza que quer remover&nbsp;
                <code className="pill-confirm-name">{confirmDelete}</code>?
              </p>
              <p className="pill-confirm-hint">Você pode recriar esse filtro a qualquer momento.</p>
            </div>

            <div className="pill-confirm-actions">
              <button className="btn btn-ghost" onClick={() => setConfirmDelete(null)}>
                Cancelar
              </button>
              <button className="btn btn-danger" onClick={() => removePill(confirmDelete)}>
                Remover
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

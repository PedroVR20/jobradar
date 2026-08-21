import { KeyboardEvent, useState } from 'react';
import { ViewMode, Stats } from '../types/Job';

interface Props {
  viewMode: ViewMode;
  onChange: (mode: ViewMode) => void;
  stats: Stats | null;
  onDropJob?: (jobId: number, tab: ViewMode) => void;
}

// Fase 8.2 — droppable em toda aba de status (antes só
// aplicadas/andamento/recusadas aceitavam soltar). Vencidas/Arquivadas
// ficam de fora de propósito: nenhuma das duas é um status que dá pra
// "escolher" arrastando — vencida é automática por data, arquivada é
// automática por inatividade (a única ação manual nela é reativar, que já
// tem botão próprio no card).
const tabs: { key: ViewMode; label: string; droppable?: boolean }[] = [
  { key: 'novas', label: '🔴 Novas', droppable: true },
  { key: 'vistas', label: '👁 Já vistas', droppable: true },
  { key: 'interessado', label: '⭐ Interessado', droppable: true },
  { key: 'aplicadas', label: '✅ Aplicadas', droppable: true },
  { key: 'andamento', label: '🔄 Em Andamento', droppable: true },
  { key: 'recusadas', label: '❌ Recusadas', droppable: true },
  { key: 'vencidas', label: '⏰ Vencidas' },
  { key: 'arquivadas', label: '🗄️ Arquivadas' },
];

// Fase 16.1 — cada aba lê a contagem DIRETA que o backend já calcula com a
// mesma Specification que a listagem de verdade usa (ver JobController.
// getStats) — antes "Aplicadas" e "Já vistas" eram aproximadas por
// subtração de outros campos, e a aproximação de "Já vistas" chegou a
// mostrar 5x o valor real (153 no badge, 31 na listagem).
function countFor(tab: ViewMode, stats: Stats | null): number | null {
  if (!stats) return null;
  switch (tab) {
    case 'novas': return stats.novas;
    case 'interessado': return stats.interessadas;
    case 'aplicadas': return stats.aplicadasAba;
    case 'andamento': return stats.emAndamento;
    case 'recusadas': return stats.recusadas;
    case 'vistas': return stats.vistasAba;
    case 'vencidas': return stats.vencidas;
    case 'arquivadas': return stats.arquivadas;
  }
}

// Fase 16.4 — role="tablist"/"tab" + navegação por seta (padrão ARIA APG
// pra tabs, ver https://www.w3.org/WAI/ARIA/apg/patterns/tabs/) — antes
// isso era um <div> de <button>s sem NENHUMA semântica de aba pra leitor de
// tela (parecia um grupo de botões soltos, não um seletor de visão única
// entre 8 opções mutuamente exclusivas). Tabindex "roving" (0 só na aba
// ativa, -1 nas outras) é o padrão pra isso funcionar bem com Tab (só um
// parada no grupo inteiro) E setas (move dentro do grupo).
export function ViewTabs({ viewMode, onChange, stats, onDropJob }: Props) {
  const [dragOverTab, setDragOverTab] = useState<ViewMode | null>(null);

  const focusTab = (index: number) => {
    const el = document.getElementById(`view-tab-${tabs[index].key}`);
    el?.focus();
  };

  const handleKeyDown = (e: KeyboardEvent, index: number) => {
    if (e.key === 'ArrowRight' || e.key === 'ArrowLeft') {
      e.preventDefault();
      const next = e.key === 'ArrowRight'
        ? (index + 1) % tabs.length
        : (index - 1 + tabs.length) % tabs.length;
      onChange(tabs[next].key);
      focusTab(next);
    } else if (e.key === 'Home') {
      e.preventDefault();
      onChange(tabs[0].key);
      focusTab(0);
    } else if (e.key === 'End') {
      e.preventDefault();
      onChange(tabs[tabs.length - 1].key);
      focusTab(tabs.length - 1);
    }
  };

  return (
    <div className="view-tabs" role="tablist" aria-label="Filtrar vagas por status">
      {tabs.map((tab, index) => {
        const count = countFor(tab.key, stats);
        const isDropTarget = tab.droppable && !!onDropJob;
        const active = viewMode === tab.key;
        return (
          <button
            key={tab.key}
            id={`view-tab-${tab.key}`}
            role="tab"
            aria-selected={active}
            tabIndex={active ? 0 : -1}
            onKeyDown={e => handleKeyDown(e, index)}
            className={`view-tab ${active ? 'view-tab--active' : ''} ${dragOverTab === tab.key ? 'view-tab--drop-hover' : ''}`}
            onClick={() => onChange(tab.key)}
            onDragOver={isDropTarget ? e => { e.preventDefault(); setDragOverTab(tab.key); } : undefined}
            onDragLeave={isDropTarget ? () => setDragOverTab(null) : undefined}
            onDrop={isDropTarget ? e => {
              e.preventDefault();
              setDragOverTab(null);
              const jobId = Number(e.dataTransfer.getData('text/job-id'));
              if (jobId && onDropJob) onDropJob(jobId, tab.key);
            } : undefined}
          >
            {tab.label}
            {count !== null && <span className="view-tab-count">{count}</span>}
          </button>
        );
      })}
    </div>
  );
}

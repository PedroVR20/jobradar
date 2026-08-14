import { useState } from 'react';
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

function countFor(tab: ViewMode, stats: Stats | null): number | null {
  if (!stats) return null;
  switch (tab) {
    case 'novas': return stats.novas;
    case 'interessado': return stats.interessadas;
    case 'aplicadas': return Math.max(0, stats.aplicadas - stats.emAndamento - stats.recusadasDeAplicadas);
    case 'andamento': return stats.emAndamento;
    case 'recusadas': return stats.recusadas;
    case 'vistas': return Math.max(0, stats.total - stats.novas - stats.interessadas - stats.aplicadas);
    case 'vencidas': return stats.vencidas;
    case 'arquivadas': return stats.arquivadas;
  }
}

export function ViewTabs({ viewMode, onChange, stats, onDropJob }: Props) {
  const [dragOverTab, setDragOverTab] = useState<ViewMode | null>(null);

  return (
    <div className="view-tabs">
      {tabs.map(tab => {
        const count = countFor(tab.key, stats);
        const isDropTarget = tab.droppable && !!onDropJob;
        return (
          <button
            key={tab.key}
            className={`view-tab ${viewMode === tab.key ? 'view-tab--active' : ''} ${dragOverTab === tab.key ? 'view-tab--drop-hover' : ''}`}
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

import { useEffect, useState } from 'react';
import { Metrics } from '../types/Job';
import { SkeletonLines } from './SkeletonCard';
import { useEscapeToClose } from '../hooks/useEscapeToClose';

interface Props {
  onClose: () => void;
}

export function MetricsModal({ onClose }: Props) {
  useEscapeToClose(onClose);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/jobs/metrics')
      .then(r => r.json())
      .then(setMetrics)
      .finally(() => setLoading(false));
  }, []);

  const maxWeek = metrics
    ? Math.max(1, ...metrics.aplicacoesPorSemana.map(w => w.count))
    : 1;

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal metrics-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>📊</span>
          <h2>Métricas de candidatura</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {loading ? (
          <SkeletonLines count={5} />
        ) : !metrics || metrics.totalAplicadas === 0 ? (
          <p className="agenda-hint">Ainda não há vagas aplicadas pra gerar métricas.</p>
        ) : (
          <div className="metrics-body">
            <div className="metrics-grid">
              <div className="metric-card">
                <span className="metric-value">{metrics.totalAplicadas}</span>
                <span className="metric-label">Total aplicadas</span>
              </div>
              <div className="metric-card">
                <span className="metric-value metric-value--progress">{metrics.emAndamento}</span>
                <span className="metric-label">🔄 Em andamento</span>
              </div>
              <div className="metric-card">
                <span className="metric-value metric-value--waiting">{metrics.aguardandoRetorno}</span>
                <span className="metric-label">⏳ Aguardando retorno</span>
              </div>
              <div className="metric-card">
                <span className="metric-value metric-value--rejected">{metrics.recusadas}</span>
                <span className="metric-label">❌ Recusadas</span>
              </div>
            </div>

            <div className="metric-highlight">
              <span className="metric-highlight-value">
                {metrics.taxaResposta !== null ? `${metrics.taxaResposta}%` : '—'}
              </span>
              <span className="metric-highlight-label">
                Taxa de resposta — % das aplicações que já tiveram algum retorno (avanço ou recusa)
              </span>
            </div>

            <div className="metrics-section">
              <h3 className="metrics-section-title">Aplicações por semana</h3>
              <div className="metrics-chart">
                {metrics.aplicacoesPorSemana.map(w => (
                  <div
                    key={w.semana}
                    className="metrics-bar-col"
                    title={`${w.count} aplicação(ões) na semana de ${w.semana}`}
                  >
                    <span className="metrics-bar-value">{w.count}</span>
                    <div className="metrics-bar-track">
                      <div
                        className="metrics-bar"
                        style={{ height: `${Math.max(4, (w.count / maxWeek) * 100)}%` }}
                      />
                    </div>
                    <span className="metrics-bar-label">{w.semana}</span>
                  </div>
                ))}
              </div>
            </div>

            {(metrics.tempoMedioAteAndamentoDias !== null || metrics.tempoMedioAteRecusaDias !== null) && (
              <div className="metrics-section">
                <h3 className="metrics-section-title">Tempo médio de resposta</h3>
                <div className="metrics-times">
                  {metrics.tempoMedioAteAndamentoDias !== null && (
                    <span className="metrics-time-pill">
                      🔄 {metrics.tempoMedioAteAndamentoDias}d até entrar em andamento
                    </span>
                  )}
                  {metrics.tempoMedioAteRecusaDias !== null && (
                    <span className="metrics-time-pill">
                      ❌ {metrics.tempoMedioAteRecusaDias}d até recusa
                    </span>
                  )}
                </div>
              </div>
            )}

            <p className="agenda-hint metrics-note">
              Tempos médios e o gráfico semanal só contam vagas aplicadas depois desta atualização —
              candidaturas antigas não têm essa marcação de data.
            </p>
          </div>
        )}
      </div>
    </div>
  );
}

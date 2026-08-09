import { useEffect, useState } from 'react';
import { createPortal } from 'react-dom';
import { Job, SalaryEstimate } from '../types/Job';

interface Props {
  job: Job;
  onClose: () => void;
}

export function SalaryEstimateModal({ job, onClose }: Props) {
  const [estimate, setEstimate] = useState<SalaryEstimate | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  useEffect(() => {
    fetch(`/api/jobs/${job.id}/salary-estimate`)
      .then(r => r.json())
      .then((data: SalaryEstimate) => setEstimate(data))
      .catch(() => setError('Erro ao buscar a estimativa. Tente de novo.'))
      .finally(() => setLoading(false));
  }, [job.id]);

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal salary-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>💰</span>
          <h2>Faixa salarial estimada</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <p className="agenda-hint">
          Baseado em outras vagas de <strong>{job.seniority !== 'NAO_INFORMADO' ? job.seniority : 'nível parecido'}</strong> com
          tecnologias em comum já cadastradas no Job Radar — dado real do nosso banco, não uma estimativa da IA.
        </p>

        {loading ? (
          <p className="agenda-hint">Calculando...</p>
        ) : error ? (
          <p className="agenda-error">{error}</p>
        ) : !estimate?.available ? (
          <p className="agenda-hint">
            Ainda não temos vagas parecidas suficientes (com salário em R$ informado) pra estimar uma faixa
            confiável pra essa combinação de senioridade + tecnologias.
          </p>
        ) : (
          <div className="salary-result">
            <div className="salary-range">
              <span className="salary-range-value">R$ {estimate.min?.toLocaleString('pt-BR')}</span>
              <span className="salary-range-sep">–</span>
              <span className="salary-range-value">R$ {estimate.max?.toLocaleString('pt-BR')}</span>
            </div>
            <div className="salary-median">
              Mediana: <strong>R$ {estimate.median?.toLocaleString('pt-BR')}</strong>
            </div>
            <p className="agenda-hint salary-sample">
              Baseado em {estimate.sampleSize} vaga{estimate.sampleSize === 1 ? '' : 's'} parecida
              {estimate.sampleSize === 1 ? '' : 's'} — amostra pequena vira estimativa menos confiável, use como
              referência, não como número final.
            </p>
          </div>
        )}

        <div className="modal-actions">
          <button className="btn btn-primary" onClick={onClose}>Fechar</button>
        </div>
      </div>
    </div>,
    document.body
  );
}

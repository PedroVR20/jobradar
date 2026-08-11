import { FormEvent, useState } from 'react';
import { RetrainResult } from '../types/Job';

interface Props {
  onClose: () => void;
}

type Step = 'code' | 'ready' | 'loading' | 'result';

function fmtBrl(v: number): string {
  return `R$ ${v.toLocaleString('pt-BR', { maximumFractionDigits: 0 })}`;
}

function fmtPct(v: number): string {
  return `${v.toFixed(1)}%`;
}

// 🟢 quando o número novo melhorou em relação ao anterior, 🔴 quando piorou
// — R² quanto maior melhor, MAE/erro% quanto menor melhor.
function diffBadge(before: number, after: number, higherIsBetter: boolean): string {
  if (before === after) return '';
  const better = higherIsBetter ? after > before : after < before;
  return better ? ' 🟢' : ' 🔴';
}

// Modal atrás de um código simples (não é segurança de verdade, é só um
// jeito de não ter um botão "retreinar" clicável à toa) — desbloqueia o
// botão de retreino de verdade do modelo de salário. Ver
// SalaryModelTrainerService no backend (Ridge regression em Java puro,
// entra em uso na hora, sem precisar reconstruir o container).
export function RetrainModal({ onClose }: Props) {
  const [code, setCode] = useState('');
  const [step, setStep] = useState<Step>('code');
  const [error, setError] = useState('');
  const [result, setResult] = useState<RetrainResult | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [forcing, setForcing] = useState(false);

  const handleVerify = async (e: FormEvent) => {
    e.preventDefault();
    if (!code.trim() || verifying) return;
    setError('');
    setVerifying(true);
    try {
      const res = await fetch('/api/jobs/admin/verify-retrain-code', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code }),
      });
      const data = await res.json();
      if (data.valid) {
        setStep('ready');
      } else {
        setError('Código incorreto.');
      }
    } catch {
      setError('Erro de conexão com o backend.');
    } finally {
      setVerifying(false);
    }
  };

  const handleRetrain = async (force = false) => {
    setError('');
    if (force) setForcing(true); else setStep('loading');
    try {
      const res = await fetch('/api/jobs/admin/retrain-salary-model', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ code, force }),
      });
      const data = await res.json();
      if (!res.ok) {
        setError(data.error ?? 'Erro ao retreinar.');
        setStep('ready');
        return;
      }
      setResult(data as RetrainResult);
      setStep('result');
    } catch {
      setError('Erro de conexão com o backend.');
      setStep('ready');
    } finally {
      setForcing(false);
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal retrain-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>🔒</span>
          <h2>Retreinar modelo de salário</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {step === 'code' && (
          <form onSubmit={handleVerify} className="retrain-step">
            <p className="agenda-hint">Digite o código pra desbloquear o retreino.</p>
            <input
              type="password"
              className="agenda-input"
              value={code}
              onChange={e => setCode(e.target.value)}
              placeholder="Código secreto"
              autoFocus
            />
            {error && <p className="agenda-error">{error}</p>}
            <button type="submit" className="btn btn-primary" disabled={verifying || !code.trim()}>
              {verifying ? 'Verificando...' : 'Desbloquear'}
            </button>
          </form>
        )}

        {(step === 'ready' || step === 'loading') && (
          <div className="retrain-step">
            <p className="agenda-hint">
              Isso reexporta as vagas com salário do banco e treina um modelo novo (regressão Ridge com
              validação cruzada pra escolher a regularização) — leva menos de 1 segundo. O modelo atual só
              é substituído se o treino terminar com sucesso.
            </p>
            {error && <p className="agenda-error">{error}</p>}
            <button type="button" className="btn btn-primary" onClick={() => handleRetrain()} disabled={step === 'loading'}>
              {step === 'loading' ? '⏳ Treinando...' : '🎓 Retreinar agora'}
            </button>
          </div>
        )}

        {step === 'result' && result && (
          <div className="retrain-step">
            <p className="agenda-hint">
              {result.applied
                ? 'Retreino concluído — comparação com o modelo anterior:'
                : 'Retreino concluído, mas o erro% piorou — mantive o modelo anterior em produção. Comparação:'}
            </p>
            {!result.applied && (
              <p className="agenda-error">
                ⚠️ Não troquei o modelo automaticamente porque o erro% ficou pior do que o atual.
              </p>
            )}
            <table className="retrain-table">
              <thead>
                <tr><th /><th>Antes</th><th>{result.applied ? 'Agora' : 'Se trocar'}</th></tr>
              </thead>
              <tbody>
                <tr>
                  <td>Amostras</td>
                  <td>{result.previous?.nSamples ?? '—'}</td>
                  <td>{result.updated.nSamples}</td>
                </tr>
                <tr>
                  <td>R²</td>
                  <td>{result.previous ? result.previous.r2.toFixed(3) : '—'}</td>
                  <td>{result.updated.r2.toFixed(3)}{result.previous && diffBadge(result.previous.r2, result.updated.r2, true)}</td>
                </tr>
                <tr>
                  <td>MAE</td>
                  <td>{result.previous ? fmtBrl(result.previous.maeBrl) : '—'}</td>
                  <td>{fmtBrl(result.updated.maeBrl)}{result.previous && diffBadge(result.previous.maeBrl, result.updated.maeBrl, false)}</td>
                </tr>
                <tr>
                  <td>Erro %</td>
                  <td>{result.previous ? fmtPct(result.previous.maePercent) : '—'}</td>
                  <td>{fmtPct(result.updated.maePercent)}{result.previous && diffBadge(result.previous.maePercent, result.updated.maePercent, false)}</td>
                </tr>
              </tbody>
            </table>
            <p className="agenda-hint settings-note">
              {result.applied
                ? 'O modelo novo já está em uso — não precisa reiniciar nada. Se algum outro número piorou (R²/MAE) mesmo com o erro% melhor, não é necessariamente ruim: pode ser ruído da divisão treino/teste.'
                : 'Isso pode ser ruído da divisão treino/teste (comum quando poucas vagas novas entraram desde o último retreino) — mas se quiser trocar mesmo assim, o botão abaixo força a troca com esse resultado.'}
            </p>
            {error && <p className="agenda-error">{error}</p>}
            <div className="modal-actions">
              {!result.applied && (
                <button type="button" className="btn btn-danger" onClick={() => handleRetrain(true)} disabled={forcing}>
                  {forcing ? 'Aplicando...' : '⚠️ Usar mesmo assim'}
                </button>
              )}
              <button type="button" className="btn btn-ghost" onClick={onClose}>Fechar</button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

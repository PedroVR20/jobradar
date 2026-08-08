import { AiStatus } from '../types/Job';

interface Props {
  aiStatus: AiStatus;
  aiLoading: boolean;
  onClose: () => void;
}

const AI_FEATURES = [
  { icon: '✉️', label: 'Carta de apresentação personalizada', hint: 'Botão "🤖 Gerar carta" em cada vaga' },
  { icon: '🧩', label: 'Segunda opinião em duplicatas', hint: 'Selo "confirmado por IA" no painel de Duplicatas' },
  { icon: '🏷️', label: 'Classificação de senioridade/stack', hint: 'Só entra em ação quando o título é ambíguo — selo "🤖" no card' },
];

export function SettingsModal({ aiStatus, aiLoading, onClose }: Props) {
  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal settings-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>⚙️</span>
          <h2>Configurações</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        <div className="settings-section">
          <h3 className="settings-section-title">🤖 Inteligência Artificial (Gemini)</h3>

          <div className={`ai-status-pill ${aiStatus.enabled ? 'ai-status-pill--on' : 'ai-status-pill--off'}`}>
            <span className="ai-status-dot" />
            {aiLoading ? 'Verificando...' : aiStatus.enabled ? 'IA ativa' : 'IA desativada'}
            {aiStatus.enabled && aiStatus.model && (
              <span className="ai-status-model">· modelo {aiStatus.model}</span>
            )}
          </div>

          <p className="agenda-hint">
            {aiStatus.enabled
              ? 'Configurada via GEMINI_API_KEY no .env do backend. Gratuita (free tier do Google AI Studio).'
              : 'Não configurada. Para ativar, gere uma chave gratuita em aistudio.google.com/apikey e defina GEMINI_API_KEY no .env do backend.'}
          </p>

          <ul className="settings-feature-list">
            {AI_FEATURES.map(f => (
              <li key={f.label} className={`settings-feature ${aiStatus.enabled ? '' : 'settings-feature--off'}`}>
                <span className="settings-feature-icon">{f.icon}</span>
                <div className="settings-feature-text">
                  <span className="settings-feature-label">{f.label}</span>
                  <span className="settings-feature-hint">{f.hint}</span>
                </div>
                <span className="settings-feature-state">{aiStatus.enabled ? '✅' : '—'}</span>
              </li>
            ))}
          </ul>

          <p className="agenda-hint settings-note">
            Sem chave configurada, o Job Radar continua funcionando normalmente — esses 3 recursos
            ficam apenas ocultos até você configurar a IA.
          </p>
        </div>
      </div>
    </div>
  );
}

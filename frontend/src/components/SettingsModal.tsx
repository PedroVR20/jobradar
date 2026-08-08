import { useRef, useState } from 'react';
import { AiStatus } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';

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
  const { profile, setProfile } = useCandidateProfile();
  const [text, setText] = useState(profile);
  const [saved, setSaved] = useState(false);
  const saveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const savedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const handleChange = (value: string) => {
    setText(value);
    setSaved(false);
    if (saveTimeout.current) clearTimeout(saveTimeout.current);
    saveTimeout.current = setTimeout(() => {
      setProfile(value);
      setSaved(true);
      if (savedTimeout.current) clearTimeout(savedTimeout.current);
      savedTimeout.current = setTimeout(() => setSaved(false), 2000);
    }, 800);
  };

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

        <div className="settings-section">
          <div className="settings-profile-head">
            <h3 className="settings-section-title">📄 Meu perfil (currículo/stack)</h3>
            <span className="notes-save-status">
              {saved ? '✓ Salvo' : text !== profile ? 'Salvando...' : ''}
            </span>
          </div>
          <p className="agenda-hint">
            Cole aqui um resumo da sua experiência, stack e projetos relevantes — fica salvo só no seu
            navegador e preenche automaticamente o campo "Contexto adicional" toda vez que você gerar
            uma carta de apresentação, sem precisar colar de novo pra cada vaga.
          </p>
          <textarea
            className="agenda-input settings-profile-textarea"
            value={text}
            onChange={e => handleChange(e.target.value)}
            placeholder="Ex: Desenvolvedor(a) fullstack com 3 anos de experiência, Java/Spring no back e React/TypeScript no front. Projeto X (breve descrição). Certificação Y..."
            rows={6}
          />
        </div>
      </div>
    </div>
  );
}

import { ChangeEvent, DragEvent, useEffect, useRef, useState } from 'react';
import { AiStatus } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';

interface Props {
  aiStatus: AiStatus;
  aiLoading: boolean;
  // Re-busca o status ao abrir o modal — o número de requisições de hoje
  // muda a cada carta/duplicata verificada, então o valor buscado uma vez
  // no carregamento da página (em App.tsx) fica velho rápido.
  onRefreshAiStatus: () => void;
  onClose: () => void;
}

const AI_FEATURES = [
  { icon: '✉️', label: 'Carta de apresentação personalizada', hint: 'Botão "🤖 Gerar carta" em cada vaga' },
  { icon: '🧩', label: 'Segunda opinião em duplicatas', hint: 'Selo "confirmado por IA" no painel de Duplicatas' },
  { icon: '🏷️', label: 'Classificação de senioridade/stack', hint: 'Só entra em ação quando o título é ambíguo — selo "🤖" no card' },
];

function formatSize(chars: number): string {
  return `${chars.toLocaleString('pt-BR')} caracteres`;
}

export function SettingsModal({ aiStatus, aiLoading, onRefreshAiStatus, onClose }: Props) {
  useEffect(() => {
    onRefreshAiStatus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { profile, fileName, setProfile } = useCandidateProfile();
  const [saved, setSaved] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [extracting, setExtracting] = useState(false);
  const [extractError, setExtractError] = useState('');
  const [manualOpen, setManualOpen] = useState(false);
  const [manualText, setManualText] = useState(profile);
  const fileInputRef = useRef<HTMLInputElement>(null);
  const saveTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);
  const savedTimeout = useRef<ReturnType<typeof setTimeout> | null>(null);

  const flashSaved = () => {
    setSaved(true);
    if (savedTimeout.current) clearTimeout(savedTimeout.current);
    savedTimeout.current = setTimeout(() => setSaved(false), 2000);
  };

  const handleFile = async (file: File) => {
    setExtractError('');
    setExtracting(true);
    try {
      // pdfjs-dist + mammoth (>1MB juntos) só são baixados aqui, na hora que
      // alguém realmente solta um arquivo — não pesam no carregamento normal do app.
      const { extractResumeText, ResumeExtractionError } = await import('../utils/extractResumeText');
      try {
        const text = await extractResumeText(file);
        setProfile(text, file.name);
        setManualText(text);
        flashSaved();
      } catch (e) {
        setExtractError(e instanceof ResumeExtractionError ? e.message : 'Erro inesperado ao ler o arquivo.');
      }
    } catch {
      setExtractError('Não foi possível carregar o leitor de arquivos. Tente recarregar a página.');
    } finally {
      setExtracting(false);
    }
  };

  const handleInputChange = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ''; // permite selecionar o mesmo arquivo de novo depois
    if (file) handleFile(file);
  };

  const handleDrop = (e: DragEvent<HTMLDivElement>) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) handleFile(file);
  };

  const handleRemove = () => {
    setProfile('', null);
    setManualText('');
    setExtractError('');
  };

  // Edição manual do texto já extraído (ou colado direto) — debounce igual
  // ao editor de notas do card, não mexe no nome do arquivo de origem.
  const handleManualChange = (value: string) => {
    setManualText(value);
    setSaved(false);
    if (saveTimeout.current) clearTimeout(saveTimeout.current);
    saveTimeout.current = setTimeout(() => {
      setProfile(value);
      flashSaved();
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

          {aiStatus.enabled && aiStatus.requestsToday !== null && (
            <p className="agenda-hint settings-usage-hint">
              📊 <strong>{aiStatus.requestsToday}</strong> requisições ao Gemini hoje (contagem aproximada,
              zera se o backend reiniciar). O free tier do <code>{aiStatus.model}</code> tem limite por
              minuto <strong>e</strong> por dia — se aparecer "limite atingido", a mensagem diz qual dos
              dois foi e quanto esperar.
            </p>
          )}

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
            <span className="notes-save-status">{saved ? '✓ Salvo' : ''}</span>
          </div>
          <p className="agenda-hint">
            Envie seu currículo uma vez — a IA lê o arquivo e usa o conteúdo pra preencher
            automaticamente o "Contexto adicional" toda vez que você gerar uma carta de apresentação,
            sem precisar colar de novo pra cada vaga.
          </p>

          {profile && fileName ? (
            <div className="profile-file-card">
              <span className="profile-file-icon">📄</span>
              <div className="profile-file-info">
                <span className="profile-file-name">{fileName}</span>
                <span className="profile-file-meta">{formatSize(profile.length)} extraídos</span>
              </div>
              <div className="profile-file-actions">
                <button
                  type="button"
                  className="btn btn-ghost profile-file-btn"
                  onClick={() => fileInputRef.current?.click()}
                  disabled={extracting}
                >
                  🔁 Trocar
                </button>
                <button
                  type="button"
                  className="btn btn-danger profile-file-btn"
                  onClick={handleRemove}
                  disabled={extracting}
                >
                  🗑
                </button>
              </div>
            </div>
          ) : (
            <div
              className={`profile-dropzone ${dragOver ? 'profile-dropzone--dragover' : ''} ${extracting ? 'profile-dropzone--busy' : ''}`}
              onClick={() => !extracting && fileInputRef.current?.click()}
              onDragOver={e => { e.preventDefault(); setDragOver(true); }}
              onDragLeave={() => setDragOver(false)}
              onDrop={handleDrop}
              role="button"
              tabIndex={0}
            >
              {extracting ? (
                <>
                  <span className="profile-dropzone-icon">⏳</span>
                  <span className="profile-dropzone-title">Lendo currículo...</span>
                </>
              ) : (
                <>
                  <span className="profile-dropzone-icon">📤</span>
                  <span className="profile-dropzone-title">Arraste seu currículo aqui ou clique para escolher</span>
                  <span className="profile-dropzone-hint">PDF ou DOCX, até 8MB</span>
                </>
              )}
            </div>
          )}

          <input
            ref={fileInputRef}
            type="file"
            accept=".pdf,.docx,application/pdf,application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            className="profile-file-input"
            onChange={handleInputChange}
          />

          {extractError && <p className="agenda-error">{extractError}</p>}

          <button
            type="button"
            className="profile-manual-toggle"
            onClick={() => setManualOpen(o => !o)}
          >
            {manualOpen ? '▾' : '▸'} {profile ? 'Ver/editar texto' : 'Ou cole o texto manualmente'}
          </button>

          {manualOpen && (
            <textarea
              className="agenda-input settings-profile-textarea"
              value={manualText}
              onChange={e => handleManualChange(e.target.value)}
              placeholder="Cole aqui um resumo da sua experiência, stack e projetos relevantes..."
              rows={6}
            />
          )}
        </div>
      </div>
    </div>
  );
}

import { ChangeEvent, DragEvent, FormEvent, useEffect, useRef, useState } from 'react';
import { AiStatus } from '../types/Job';
import { useCandidateProfile } from '../hooks/useCandidateProfile';
import { GitHubFetchError, useGitHubProfile } from '../hooks/useGitHubProfile';
import { useGmail } from '../hooks/useGmail';
import { RetrainModal } from './RetrainModal';

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
];

function formatSize(chars: number): string {
  return `${chars.toLocaleString('pt-BR')} caracteres`;
}

function relativeTime(ts: number): string {
  const diffMin = Math.floor((Date.now() - ts) / 60000);
  if (diffMin < 1) return 'agora mesmo';
  if (diffMin < 60) return `há ${diffMin}min`;
  const h = Math.floor(diffMin / 60);
  if (h < 24) return `há ${h}h`;
  const d = Math.floor(h / 24);
  return d === 1 ? 'ontem' : `há ${d}d`;
}

export function SettingsModal({ aiStatus, aiLoading, onRefreshAiStatus, onClose }: Props) {
  useEffect(() => {
    onRefreshAiStatus();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const { profile, fileName, setProfile } = useCandidateProfile();
  const github = useGitHubProfile();
  const gmail = useGmail();
  const [githubInput, setGithubInput] = useState('');
  const [githubLoading, setGithubLoading] = useState(false);
  const [githubError, setGithubError] = useState('');
  const [saved, setSaved] = useState(false);
  const [retrainOpen, setRetrainOpen] = useState(false);
  const [dragOver, setDragOver] = useState(false);
  const [extracting, setExtracting] = useState(false);
  const [extractError, setExtractError] = useState('');
  const [manualOpen, setManualOpen] = useState(false);
  const [manualText, setManualText] = useState(profile);
  const [backfillStatus, setBackfillStatus] = useState<'idle' | 'sending' | 'started' | 'error'>('idle');
  const [backfillMsg, setBackfillMsg] = useState('');
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

  const handleAnalyzeGithub = async (e: FormEvent) => {
    e.preventDefault();
    if (!githubInput.trim() || githubLoading) return;
    setGithubLoading(true);
    setGithubError('');
    try {
      await github.analyze(githubInput);
      setGithubInput('');
    } catch (err) {
      setGithubError(err instanceof GitHubFetchError ? err.message : 'Erro inesperado ao acessar o GitHub.');
    } finally {
      setGithubLoading(false);
    }
  };

  const handleReanalyzeGithub = async () => {
    if (githubLoading) return;
    setGithubLoading(true);
    setGithubError('');
    try {
      await github.analyze(github.username);
    } catch (err) {
      setGithubError(err instanceof GitHubFetchError ? err.message : 'Erro inesperado ao acessar o GitHub.');
    } finally {
      setGithubLoading(false);
    }
  };

  const handleBackfillEmbeddings = async () => {
    setBackfillStatus('sending');
    setBackfillMsg('');
    try {
      const res = await fetch('/api/jobs/admin/backfill-embeddings', { method: 'POST' });
      if (res.status === 202) {
        const data = await res.json();
        setBackfillStatus('started');
        setBackfillMsg(`Rodando em segundo plano — ${data.vagasSemEmbedding} vaga(s) na fila.`);
      } else if (res.status === 409) {
        setBackfillStatus('started');
        setBackfillMsg('Já tinha um backfill em andamento.');
      } else {
        const err = await res.json().catch(() => null);
        setBackfillStatus('error');
        setBackfillMsg(err?.error ?? 'Erro ao iniciar o backfill.');
      }
    } catch {
      setBackfillStatus('error');
      setBackfillMsg('Erro de conexão com o backend.');
    }
  };

  return (
    <>
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
              ? aiStatus.keyPool && aiStatus.keyPool.total > 1
                ? `Configurada via GEMINI_API_KEYS no .env do backend, com rodízio automático entre as ${aiStatus.keyPool.total} keys quando uma bate no limite. Gratuita (free tier do Google AI Studio).`
                : 'Configurada via GEMINI_API_KEY no .env do backend. Gratuita (free tier do Google AI Studio).'
              : 'Não configurada. Para ativar, gere uma chave gratuita em aistudio.google.com/apikey e defina GEMINI_API_KEY (ou GEMINI_API_KEYS, com várias) no .env do backend.'}
          </p>

          {aiStatus.enabled && aiStatus.keyPool && aiStatus.keyPool.total > 1 && (
            <div className={`key-pool-pill ${aiStatus.keyPool.availableToday === 0 ? 'key-pool-pill--empty' : ''}`}>
              🔑 <strong>{aiStatus.keyPool.availableToday}</strong> de <strong>{aiStatus.keyPool.total}</strong> keys
              disponíveis hoje
              {aiStatus.keyPool.exhaustedToday > 0 && (
                <span className="key-pool-exhausted"> · {aiStatus.keyPool.exhaustedToday} esgotada(s)</span>
              )}
            </div>
          )}

          {aiStatus.enabled && aiStatus.requestsToday !== null && (
            <p className="agenda-hint settings-usage-hint">
              📊 <strong>{aiStatus.requestsToday}</strong> requisições ao Gemini hoje
              {aiStatus.keyPool && aiStatus.keyPool.total > 1 ? ' (somando todas as keys)' : ''} — contagem
              aproximada, zera se o backend reiniciar. O free tier do <code>{aiStatus.model}</code> tem
              limite por minuto <strong>e</strong> por dia — se aparecer "limite atingido", a mensagem diz
              qual dos dois foi e quanto esperar
              {aiStatus.keyPool && aiStatus.keyPool.total > 1
                ? ', ou avisa quando todas as keys da pool se esgotaram.'
                : '.'}
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
            Sem chave configurada, o Job Radar continua funcionando normalmente — esses 2 recursos
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

        <div className="settings-section">
          <h3 className="settings-section-title">🐙 GitHub (opcional)</h3>
          <p className="agenda-hint">
            Nem todo projeto seu vai caber no currículo — cole seu usuário ou link do GitHub e o Hunter
            busca seus repositórios públicos (nome, descrição, linguagem, tags) pra usar como contexto
            extra nas análises. Só repositórios <strong>públicos</strong>, obviamente — privados a API
            nem devolve.
          </p>

          {github.username ? (
            <div className="profile-file-card">
              <span className="profile-file-icon">🐙</span>
              <div className="profile-file-info">
                <span className="profile-file-name">@{github.username}</span>
                <span className="profile-file-meta">
                  {github.repoCount} repositório{github.repoCount === 1 ? '' : 's'} público{github.repoCount === 1 ? '' : 's'}
                  {github.fetchedAt ? ` · atualizado ${relativeTime(github.fetchedAt)}` : ''}
                </span>
              </div>
              <div className="profile-file-actions">
                <button
                  type="button"
                  className="btn btn-ghost profile-file-btn"
                  onClick={handleReanalyzeGithub}
                  disabled={githubLoading}
                >
                  {githubLoading ? '⏳' : '🔁 Atualizar'}
                </button>
                <button type="button" className="btn btn-danger profile-file-btn" onClick={github.clear} disabled={githubLoading}>
                  🗑
                </button>
              </div>
            </div>
          ) : (
            <form className="github-input-row" onSubmit={handleAnalyzeGithub}>
              <input
                className="agenda-input"
                value={githubInput}
                onChange={e => setGithubInput(e.target.value)}
                placeholder="seu-usuario ou github.com/seu-usuario"
                disabled={githubLoading}
              />
              <button type="submit" className="btn btn-ai" disabled={githubLoading || !githubInput.trim()}>
                {githubLoading ? 'Analisando...' : '🔍 Analisar'}
              </button>
            </form>
          )}

          {githubError && <p className="agenda-error">{githubError}</p>}
        </div>

        <div className="settings-section">
          <h3 className="settings-section-title">💾 Backup dos dados</h3>
          <p className="agenda-hint">
            Baixa todas as vagas salvas no Job Radar (título, status, notas, tudo) num arquivo —
            útil antes de mexer em algo arriscado, ou pra levar os dados pra outra ferramenta.
          </p>
          <div className="settings-export-actions">
            <a className="btn btn-ghost" href="/api/jobs/export?format=json" download>
              ⬇ Exportar JSON
            </a>
            <a className="btn btn-ghost" href="/api/jobs/export?format=csv" download>
              ⬇ Exportar CSV
            </a>
          </div>
        </div>

        {aiStatus.enabled && (
          <div className="settings-section">
            <h3 className="settings-section-title">🔎 Busca semântica do Hunter</h3>
            <p className="agenda-hint">
              O Hunter acha vagas por SIGNIFICADO (não só por palavra exata) usando embeddings — vagas
              novas já são processadas sozinhas a cada busca. Rode isso uma vez pra cobrir o catálogo que
              já existia antes dessa função existir. Usa uma cota separada da do chat, não some com o
              limite diário do Gemini.
            </p>
            <button type="button" className="btn btn-ghost" onClick={handleBackfillEmbeddings} disabled={backfillStatus === 'sending'}>
              {backfillStatus === 'sending' ? 'Iniciando...' : '🔎 Processar vagas antigas'}
            </button>
            {backfillMsg && (
              <p className={backfillStatus === 'error' ? 'agenda-error' : 'agenda-hint settings-usage-hint'}>{backfillMsg}</p>
            )}
          </div>
        )}

        <div className="settings-section">
          <h3 className="settings-section-title">📧 Gmail</h3>
          {!gmail.loading && !gmail.configured && (
            <p className="agenda-hint">
              Integração não configurada — precisa de um client OAuth do Google Cloud Console
              (variáveis <code>GOOGLE_OAUTH_CLIENT_ID</code>/<code>GOOGLE_OAUTH_CLIENT_SECRET</code> no <code>.env</code>).
            </p>
          )}
          {!gmail.loading && gmail.configured && !gmail.connected && (
            <>
              <p className="agenda-hint">
                Conecta o Hunter ao seu Gmail (só-leitura) pra ele achar vagas nos emails de alerta
                (LinkedIn, Glassdoor) e sugerir importar pro Job Radar — você sempre confirma cada
                uma antes de qualquer coisa entrar no banco, nada é adicionado sozinho.
              </p>
              <button type="button" className="btn btn-ghost" onClick={gmail.connect} disabled={gmail.connecting}>
                {gmail.connecting ? 'Abrindo o Google...' : '📧 Conectar Gmail'}
              </button>
            </>
          )}
          {!gmail.loading && gmail.connected && (
            <>
              <p className="agenda-hint">
                ✅ Conectado{gmail.email ? ` como ${gmail.email}` : ''}. Peça pro Hunter no chat, ex:
                "vê se tem vaga nova no meu email" (ou <code>/emails</code>).
              </p>
              <button type="button" className="btn btn-ghost" onClick={gmail.disconnect}>
                Desconectar Gmail
              </button>
            </>
          )}
          {gmail.error && <p className="agenda-error">{gmail.error}</p>}
        </div>

        <div className="settings-section">
          <h3 className="settings-section-title">🔒 Área avançada</h3>
          <p className="agenda-hint">
            Retreina o modelo de estimativa de salário com as vagas mais recentes do banco — protegido
            por um código simples pra não ter um botão sensível clicável à toa.
          </p>
          <button type="button" className="btn btn-ghost" onClick={() => setRetrainOpen(true)}>
            🎓 Retreinar modelo de salário
          </button>
        </div>
      </div>
    </div>
    {retrainOpen && <RetrainModal onClose={() => setRetrainOpen(false)} />}
    </>
  );
}

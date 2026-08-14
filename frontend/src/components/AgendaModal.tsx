import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job } from '../types/Job';
import { useAgenda, AgendaTaskPayload } from '../hooks/useAgenda';
import { useEscapeToClose } from '../hooks/useEscapeToClose';

interface Props {
  job: Job;
  onClose: () => void;
  onSuccess: () => void;
}

const NOTIFY_BEFORE_MINUTES = 24 * 60;

function buildPayload(job: Job, title: string, withDeadline: boolean): AgendaTaskPayload {
  const description = `🔗 ${job.url}${job.notes ? `\n\n📝 ${job.notes}` : ''}`;
  const dueAt = withDeadline && job.expiresAt
    ? `${job.expiresAt}T23:59:00-03:00`
    : null;
  return {
    title,
    description,
    dueAt: dueAt ?? undefined,
    priority: 'HIGH',
    icon: 'work',
    notifyBeforeMinutes: dueAt ? NOTIFY_BEFORE_MINUTES : undefined,
  };
}

export function AgendaModal({ job, onClose, onSuccess }: Props) {
  useEscapeToClose(onClose);
  const { isConnected, savedEmail, login, createTask, linkTask } = useAgenda();

  // Etapa: 'connect' → 'confirm' → 'sending'
  const [step, setStep] = useState<'connect' | 'confirm'>(isConnected() ? 'confirm' : 'connect');
  const [email, setEmail] = useState(savedEmail());
  const [password, setPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  const [loginLoading, setLoginLoading] = useState(false);

  const defaultTitle = `Candidatura: ${job.title} @ ${job.company}`;
  const [taskTitle, setTaskTitle] = useState(defaultTitle);
  const [withDeadline, setWithDeadline] = useState(!!job.expiresAt);
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginLoading(true);
    setLoginError('');
    const result = await login(email, password);
    setLoginLoading(false);
    if (result === 'ok') {
      setStep('confirm');
    } else if (result === 'invalid') {
      setLoginError('E-mail ou senha incorretos.');
    } else {
      setLoginError('Não foi possível conectar à Agenda. Verifique se ela está rodando em localhost:4200.');
    }
  };

  const handleCreate = async () => {
    setSending(true);
    setSendError('');
    const payload = buildPayload(job, taskTitle, withDeadline);
    const result = await createTask(payload);
    setSending(false);
    if (result !== 'unauthorized' && result !== 'error') {
      linkTask(job.id, result.id, payload.dueAt ?? null);
      onSuccess();
    } else if (result === 'unauthorized') {
      setStep('connect');
      setPassword('');
      setSendError('Sessão expirada. Faça login novamente.');
    } else {
      setSendError('Erro ao criar tarefa. Tente novamente.');
    }
  };

  return createPortal(
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal agenda-modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <span>📅</span>
          <h2>
            {step === 'connect' ? 'Conectar à Agenda' : 'Salvar na Agenda'}
          </h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {step === 'connect' && (
          <form className="agenda-form" onSubmit={handleLogin}>
            <p className="agenda-hint">
              Entre com suas credenciais da Agenda Pessoal para criar uma tarefa.
            </p>
            <label className="agenda-label">
              E-mail
              <input
                className="agenda-input"
                type="email"
                value={email}
                onChange={e => setEmail(e.target.value)}
                placeholder="seu@email.com"
                required
                autoFocus
              />
            </label>
            <label className="agenda-label">
              Senha
              <input
                className="agenda-input"
                type="password"
                value={password}
                onChange={e => setPassword(e.target.value)}
                placeholder="••••••••"
                required
              />
            </label>
            {loginError && <p className="agenda-error">{loginError}</p>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={onClose}>Cancelar</button>
              <button type="submit" className="btn btn-primary" disabled={loginLoading}>
                {loginLoading ? 'Conectando...' : 'Conectar'}
              </button>
            </div>
          </form>
        )}

        {step === 'confirm' && (
          <div className="agenda-confirm">
            <p className="agenda-hint">
              Será criada uma tarefa de prioridade alta na sua Agenda.
            </p>

            <label className="agenda-label">
              Título da tarefa
              <input
                className="agenda-input"
                type="text"
                value={taskTitle}
                onChange={e => setTaskTitle(e.target.value)}
                maxLength={255}
              />
            </label>

            {job.expiresAt && (
              <label className="agenda-checkbox-label">
                <input
                  type="checkbox"
                  checked={withDeadline}
                  onChange={e => setWithDeadline(e.target.checked)}
                />
                Prazo: {job.expiresAt} às 23:59
              </label>
            )}

            <div className="agenda-preview">
              <span className="agenda-preview-row">🔗 <a href={job.url} target="_blank" rel="noopener noreferrer">{job.url}</a></span>
              {job.notes && <span className="agenda-preview-row">📝 {job.notes}</span>}
              <span className="agenda-preview-row">🏷️ Prioridade: Alta · Ícone: work</span>
            </div>

            {sendError && <p className="agenda-error">{sendError}</p>}

            <div className="modal-actions">
              <button className="btn btn-ghost" onClick={onClose}>Cancelar</button>
              <button
                className="btn btn-primary"
                onClick={handleCreate}
                disabled={sending || !taskTitle.trim()}
              >
                {sending ? 'Criando...' : '✅ Criar tarefa'}
              </button>
            </div>
          </div>
        )}
      </div>
    </div>,
    document.body
  );
}

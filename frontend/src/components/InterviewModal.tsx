import { useState } from 'react';
import { createPortal } from 'react-dom';
import { Job } from '../types/Job';
import { useAgenda } from '../hooks/useAgenda';

interface Props {
  job: Job;
  onClose: () => void;
  onSuccess: () => void;
}

const NOTIFY_BEFORE_MINUTES = 120; // 2h antes — entrevista pede aviso mais próximo que o follow-up genérico

export function InterviewModal({ job, onClose, onSuccess }: Props) {
  const { isConnected, savedEmail, login, createTask, linkInterviewTask } = useAgenda();

  const [step, setStep] = useState<'connect' | 'schedule'>(isConnected() ? 'schedule' : 'connect');
  const [email, setEmail] = useState(savedEmail());
  const [password, setPassword] = useState('');
  const [loginError, setLoginError] = useState('');
  const [loginLoading, setLoginLoading] = useState(false);

  const [date, setDate] = useState('');
  const [time, setTime] = useState('10:00');
  const [notes, setNotes] = useState('');
  const [sending, setSending] = useState(false);
  const [sendError, setSendError] = useState('');

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoginLoading(true);
    setLoginError('');
    const result = await login(email, password);
    setLoginLoading(false);
    if (result === 'ok') {
      setStep('schedule');
    } else if (result === 'invalid') {
      setLoginError('E-mail ou senha incorretos.');
    } else {
      setLoginError('Não foi possível conectar à Agenda. Verifique se ela está rodando em localhost:4200.');
    }
  };

  const handleSchedule = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!date) return;
    setSending(true);
    setSendError('');
    const dueAt = `${date}T${time}:00-03:00`;
    const description = `🔗 ${job.url}${notes ? `\n\n📝 ${notes}` : ''}`;
    const result = await createTask({
      title: `Entrevista: ${job.company} — ${job.title}`,
      description,
      dueAt,
      priority: 'CRITICAL',
      icon: 'event',
      notifyBeforeMinutes: NOTIFY_BEFORE_MINUTES,
    });
    setSending(false);
    if (result !== 'unauthorized' && result !== 'error') {
      linkInterviewTask(job.id, result.id, dueAt);
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
          <span>🎤</span>
          <h2>{step === 'connect' ? 'Conectar à Agenda' : 'Marcar entrevista'}</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {step === 'connect' && (
          <form className="agenda-form" onSubmit={handleLogin}>
            <p className="agenda-hint">
              Entre com suas credenciais da Agenda Pessoal pra agendar a entrevista.
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

        {step === 'schedule' && (
          <form className="agenda-form" onSubmit={handleSchedule}>
            <p className="agenda-hint">
              Cria uma tarefa CRÍTICA na Agenda com aviso 2h antes do horário.
            </p>
            <label className="agenda-label">
              Data
              <input
                className="agenda-input"
                type="date"
                value={date}
                onChange={e => setDate(e.target.value)}
                required
                autoFocus
              />
            </label>
            <label className="agenda-label">
              Horário
              <input
                className="agenda-input"
                type="time"
                value={time}
                onChange={e => setTime(e.target.value)}
                required
              />
            </label>
            <label className="agenda-label">
              Notas (link da chamada, entrevistador...)
              <textarea
                className="agenda-input"
                value={notes}
                onChange={e => setNotes(e.target.value)}
                rows={2}
              />
            </label>
            {sendError && <p className="agenda-error">{sendError}</p>}
            <div className="modal-actions">
              <button type="button" className="btn btn-ghost" onClick={onClose}>Cancelar</button>
              <button type="submit" className="btn btn-primary" disabled={sending || !date}>
                {sending ? 'Criando...' : '🎤 Marcar entrevista'}
              </button>
            </div>
          </form>
        )}
      </div>
    </div>,
    document.body
  );
}

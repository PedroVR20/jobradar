import { useState } from 'react';
import { useAgenda } from '../hooks/useAgenda';

interface Props {
  syncing: boolean;
  onSync: () => void;
}

export function AgendaStatusBar({ syncing, onSync }: Props) {
  const { isConnected, savedEmail, login, disconnect } = useAgenda();
  const [, forceUpdate] = useState(0);
  const [formOpen, setFormOpen] = useState(false);
  const [email, setEmail] = useState(savedEmail());
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');

  const connected = isConnected();

  const handleLogin = async (e: React.FormEvent) => {
    e.preventDefault();
    setLoading(true);
    setError('');
    const result = await login(email, password);
    setLoading(false);
    if (result === 'ok') {
      setPassword('');
      setFormOpen(false);
      forceUpdate(n => n + 1);
    } else if (result === 'invalid') {
      setError('E-mail ou senha incorretos.');
    } else {
      setError('Não conectou. A Agenda está rodando em localhost:4200?');
    }
  };

  const handleDisconnect = () => {
    disconnect();
    forceUpdate(n => n + 1);
  };

  if (connected) {
    return (
      <div className="agenda-status agenda-status--connected">
        <span className="agenda-status-dot" />
        <span className="agenda-status-label" title={savedEmail()}>Agenda conectada</span>
        <button
          className="agenda-status-btn"
          onClick={onSync}
          disabled={syncing}
          title="Sincronizar status das vagas com a Agenda"
        >
          {syncing ? '⏳' : '🔄'}
        </button>
        <button
          className="agenda-status-btn"
          onClick={handleDisconnect}
          title="Desconectar da Agenda"
        >
          ✕
        </button>
      </div>
    );
  }

  return (
    <div className="agenda-status agenda-status--disconnected">
      <button className="agenda-status-toggle" onClick={() => setFormOpen(o => !o)}>
        ⚪ Conectar Agenda
      </button>
      {formOpen && (
        <form className="agenda-status-form" onSubmit={handleLogin}>
          <input
            className="agenda-input"
            type="email"
            placeholder="e-mail"
            value={email}
            onChange={e => setEmail(e.target.value)}
            required
            autoFocus
          />
          <input
            className="agenda-input"
            type="password"
            placeholder="senha"
            value={password}
            onChange={e => setPassword(e.target.value)}
            required
          />
          <button type="submit" className="btn btn-primary" disabled={loading}>
            {loading ? '...' : 'Conectar'}
          </button>
          {error && <span className="agenda-error agenda-error--inline">{error}</span>}
        </form>
      )}
    </div>
  );
}

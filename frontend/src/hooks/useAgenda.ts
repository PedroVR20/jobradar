const AGENDA_API = 'http://localhost:8081';
const TOKEN_KEY = 'agenda_token';
const EMAIL_KEY = 'agenda_email';

export interface AgendaTaskPayload {
  title: string;
  description?: string;
  dueAt?: string | null;
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';
  icon?: string;
}

export function useAgenda() {
  const getToken = () => localStorage.getItem(TOKEN_KEY);
  const isConnected = () => !!getToken();
  const savedEmail = () => localStorage.getItem(EMAIL_KEY) ?? '';

  const login = async (email: string, password: string): Promise<'ok' | 'invalid' | 'error'> => {
    try {
      const res = await fetch(`${AGENDA_API}/api/v1/auth/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, password }),
      });
      if (res.status === 401) return 'invalid';
      if (!res.ok) return 'error';
      const data = await res.json();
      localStorage.setItem(TOKEN_KEY, data.accessToken);
      localStorage.setItem(EMAIL_KEY, email);
      return 'ok';
    } catch {
      return 'error';
    }
  };

  const disconnect = () => {
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(EMAIL_KEY);
  };

  // Retorna 'ok' | 'unauthorized' | 'error'
  const createTask = async (payload: AgendaTaskPayload): Promise<'ok' | 'unauthorized' | 'error'> => {
    const token = getToken();
    if (!token) return 'unauthorized';
    try {
      const res = await fetch(`${AGENDA_API}/api/v1/tasks`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify(payload),
      });
      if (res.status === 401) {
        disconnect();
        return 'unauthorized';
      }
      return res.status === 201 ? 'ok' : 'error';
    } catch {
      return 'error';
    }
  };

  return { isConnected, savedEmail, login, disconnect, createTask };
}

const AGENDA_API = 'http://localhost:8081';
const TOKEN_KEY = 'agenda_token';
const EMAIL_KEY = 'agenda_email';
const TASK_KEY = (jobId: number) => `agenda_task_${jobId}`;

export type AgendaTaskStatus = 'PENDING' | 'IN_PROGRESS' | 'DONE' | 'NOT_DONE';

export interface AgendaTaskPayload {
  title: string;
  description?: string;
  dueAt?: string | null;
  priority?: 'LOW' | 'NORMAL' | 'HIGH' | 'CRITICAL';
  icon?: string;
  notifyBeforeMinutes?: number | null;
}

export type CreateTaskResult = { id: string } | 'unauthorized' | 'error';

export interface LinkedTask {
  id: string;
  dueAt: string | null;
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

  const linkTask = (jobId: number, taskId: string, dueAt: string | null = null) => {
    localStorage.setItem(TASK_KEY(jobId), JSON.stringify({ id: taskId, dueAt }));
  };

  const getLinkedTask = (jobId: number): LinkedTask | null => {
    const raw = localStorage.getItem(TASK_KEY(jobId));
    if (!raw) return null;
    try {
      return JSON.parse(raw) as LinkedTask;
    } catch {
      return null;
    }
  };

  const getLinkedTaskId = (jobId: number): string | null => getLinkedTask(jobId)?.id ?? null;

  const createTask = async (payload: AgendaTaskPayload): Promise<CreateTaskResult> => {
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
      if (res.status !== 201) return 'error';
      const data = await res.json();
      return { id: data.id as string };
    } catch {
      return 'error';
    }
  };

  const syncTaskStatus = async (jobId: number, status: AgendaTaskStatus): Promise<void> => {
    const token = getToken();
    const taskId = getLinkedTaskId(jobId);
    if (!token || !taskId) return;
    try {
      await fetch(`${AGENDA_API}/api/v1/tasks/${taskId}/status`, {
        method: 'PATCH',
        headers: {
          'Content-Type': 'application/json',
          'Authorization': `Bearer ${token}`,
        },
        body: JSON.stringify({ status }),
      });
    } catch {
      // silent — sync failure doesn't affect Job Radar flow
    }
  };

  // Lê o status atual de uma tarefa na Agenda — usado pra sincronizar no sentido inverso
  // (Agenda → Job Radar), quando o usuário move a tarefa direto por lá.
  const getTaskStatus = async (taskId: string): Promise<AgendaTaskStatus | null> => {
    const token = getToken();
    if (!token) return null;
    try {
      const res = await fetch(`${AGENDA_API}/api/v1/tasks/${taskId}`, {
        headers: { 'Authorization': `Bearer ${token}` },
      });
      if (res.status === 401) {
        disconnect();
        return null;
      }
      if (!res.ok) return null;
      const data = await res.json();
      return data.status as AgendaTaskStatus;
    } catch {
      return null;
    }
  };

  return { isConnected, savedEmail, login, disconnect, createTask, linkTask, getLinkedTask, getLinkedTaskId, syncTaskStatus, getTaskStatus };
}

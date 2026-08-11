import { useCallback, useEffect, useState } from 'react';

export interface GmailStatus {
  configured: boolean;
  connected: boolean;
  email: string | null;
}

const DEFAULT_STATUS: GmailStatus = { configured: false, connected: false, email: null };

// Busca uma vez ao montar (mesmo padrão de useAiStatus) — refresh exposto à
// parte pra chamar de novo assim que a tela volta a ficar visível depois do
// fluxo de OAuth (navegação de ida e volta pro Google, ver connect() abaixo),
// que é a única forma real do status mudar durante o uso.
export function useGmail() {
  const [status, setStatus] = useState<GmailStatus>(DEFAULT_STATUS);
  const [loading, setLoading] = useState(true);
  const [connecting, setConnecting] = useState(false);
  const [error, setError] = useState('');

  const refresh = useCallback(() => {
    setLoading(true);
    return fetch('/api/gmail/status')
      .then(r => r.json())
      .then((data: GmailStatus) => setStatus(data))
      .catch(() => setStatus(DEFAULT_STATUS))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  // Volta do fluxo OAuth: o backend redireciona o navegador de volta pra cá
  // com "?gmail=conectado" ou "?gmail=erro" (ver GmailController.callback) —
  // detecta isso, atualiza o status e limpa o parâmetro da URL pra não ficar
  // reprocessando num F5.
  useEffect(() => {
    const params = new URLSearchParams(window.location.search);
    const resultado = params.get('gmail');
    if (!resultado) return;
    if (resultado === 'erro') setError('Não foi possível conectar ao Gmail. Tente de novo.');
    refresh();
    params.delete('gmail');
    const novaUrl = window.location.pathname + (params.toString() ? `?${params}` : '');
    window.history.replaceState({}, '', novaUrl);
  }, [refresh]);

  // Redireciona o navegador INTEIRO pro consentimento do Google — não é uma
  // chamada fetch (o Google recusa OAuth dentro de iframe/XHR por segurança),
  // então isso navega a aba pra fora do app e volta via o redirect do backend.
  const connect = async () => {
    setConnecting(true);
    setError('');
    try {
      const res = await fetch('/api/gmail/auth-url');
      if (!res.ok) {
        const err = await res.json().catch(() => null);
        setError(err?.error ?? 'Não foi possível iniciar a conexão com o Gmail.');
        setConnecting(false);
        return;
      }
      const data = await res.json();
      window.location.href = data.url;
    } catch {
      setError('Erro de conexão tentando falar com o backend.');
      setConnecting(false);
    }
  };

  const disconnect = async () => {
    await fetch('/api/gmail/disconnect', { method: 'POST' });
    await refresh();
  };

  return { ...status, loading, connecting, error, connect, disconnect, refresh };
}

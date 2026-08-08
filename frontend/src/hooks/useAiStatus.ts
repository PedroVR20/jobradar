import { useCallback, useEffect, useState } from 'react';
import { AiStatus } from '../types/Job';

// Busca uma vez, ao montar o App — não por card, pra não disparar N requisições
// idênticas quando a lista tem várias vagas na tela (ver App.tsx). `refresh`
// é exposto à parte pra quem precisa de um número atualizado (ex: o contador
// de requisições de hoje em Configurações, que muda a cada carta gerada e
// ficaria velho se só buscasse uma vez no carregamento da página).
export function useAiStatus() {
  const [status, setStatus] = useState<AiStatus>({ enabled: false, model: null, requestsToday: null });
  const [loading, setLoading] = useState(true);

  const refresh = useCallback(() => {
    setLoading(true);
    return fetch('/api/jobs/ai-status')
      .then(r => r.json())
      .then((data: AiStatus) => setStatus(data))
      .catch(() => setStatus({ enabled: false, model: null, requestsToday: null }))
      .finally(() => setLoading(false));
  }, []);

  useEffect(() => {
    refresh();
  }, [refresh]);

  return { ...status, loading, refresh };
}

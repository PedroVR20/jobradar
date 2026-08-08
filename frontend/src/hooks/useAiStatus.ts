import { useEffect, useState } from 'react';
import { AiStatus } from '../types/Job';

// Busca uma vez, ao montar o App — não por card, pra não disparar N requisições
// idênticas quando a lista tem várias vagas na tela (ver App.tsx).
export function useAiStatus() {
  const [status, setStatus] = useState<AiStatus>({ enabled: false, model: null });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch('/api/jobs/ai-status')
      .then(r => r.json())
      .then((data: AiStatus) => setStatus(data))
      .catch(() => setStatus({ enabled: false, model: null }))
      .finally(() => setLoading(false));
  }, []);

  return { ...status, loading };
}

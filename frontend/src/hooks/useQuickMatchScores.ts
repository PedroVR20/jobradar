import { useCallback, useState } from 'react';

// Pontuação heurística (sem IA) de todas as vagas não vistas contra o perfil
// salvo em Configurações — POST /api/jobs/quick-match-scores. Usado tanto
// pro badge "🎯 X% match" nos cards quanto pra ordenar o modo Triagem rápida
// (ver TriageModal). Buscado sob demanda (não no carregamento do app,
// diferente de useAiStatus) porque só faz sentido gastar essa chamada
// quando o usuário tem perfil salvo E está numa tela que usa o resultado.
export function useQuickMatchScores() {
  const [scores, setScores] = useState<Record<string, number>>({});
  const [loading, setLoading] = useState(false);

  const refresh = useCallback(async (profile: string) => {
    if (!profile.trim()) {
      setScores({});
      return {};
    }
    setLoading(true);
    try {
      const res = await fetch('/api/jobs/quick-match-scores', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ profile }),
      });
      const data = await res.json() as { scores: Record<string, number> };
      setScores(data.scores ?? {});
      return data.scores ?? {};
    } catch {
      setScores({});
      return {};
    } finally {
      setLoading(false);
    }
  }, []);

  return { scores, loading, refresh };
}

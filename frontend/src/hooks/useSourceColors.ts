import { useState } from 'react';

// Cores escolhidas pelo usuário pra fontes "não oficiais" (digitadas na
// adição manual de vaga, tipo "InfoJobs") — não existe conta/backend pra
// isso, então fica salvo por navegador no localStorage.
const LS_KEY = 'jobradar:custom-source-colors';

function load(): Record<string, string> {
  try {
    return JSON.parse(localStorage.getItem(LS_KEY) ?? '{}');
  } catch {
    return {};
  }
}

function save(map: Record<string, string>) {
  localStorage.setItem(LS_KEY, JSON.stringify(map));
}

export function useSourceColors() {
  const [colors, setColors] = useState<Record<string, string>>(load);

  const getColor = (source: string): string | null => colors[source] ?? null;

  const setColor = (source: string, color: string) => {
    const updated = { ...colors, [source]: color };
    setColors(updated);
    save(updated);
  };

  return { getColor, setColor };
}

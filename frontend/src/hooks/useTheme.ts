import { useEffect, useState } from 'react';

// Fase 4.1 — tema claro. Só dois estados (dark/light), sem "auto" à parte:
// a primeira visita já resolve pela preferência do sistema
// (prefers-color-scheme), e a partir daí é 100% a escolha explícita do
// usuário, persistida — não fica "brigando" com o SO depois que a pessoa
// mexeu no toggle uma vez.
const STORAGE_KEY = 'jobradar:theme';
export type Theme = 'dark' | 'light';

function temaInicial(): Theme {
  try {
    const salvo = localStorage.getItem(STORAGE_KEY);
    if (salvo === 'dark' || salvo === 'light') return salvo;
  } catch {
    // localStorage indisponível (modo privado restrito) — cai pro sistema
  }
  return window.matchMedia?.('(prefers-color-scheme: light)').matches ? 'light' : 'dark';
}

export function useTheme() {
  const [theme, setThemeState] = useState<Theme>(temaInicial);

  useEffect(() => {
    document.documentElement.setAttribute('data-theme', theme);
  }, [theme]);

  const setTheme = (t: Theme) => {
    setThemeState(t);
    try {
      localStorage.setItem(STORAGE_KEY, t);
    } catch {
      // sem storage, tema só vale pra essa sessão de página — ok
    }
  };

  const toggle = () => setTheme(theme === 'dark' ? 'light' : 'dark');

  return { theme, setTheme, toggle };
}

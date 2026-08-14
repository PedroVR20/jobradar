import { useEffect } from 'react';

// Fase 4.6 — Esc fechando o modal já existia só em 2 dos 12 modais do app
// (JarvisPanel, TriageModal, cada um reimplementando o listener). Extraído
// pra hook reutilizável e aplicado nos outros 10 — navegação por teclado
// básica que faltava na maioria dos modais.
export function useEscapeToClose(onClose: () => void) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    window.addEventListener('keydown', handler);
    return () => window.removeEventListener('keydown', handler);
  }, [onClose]);
}

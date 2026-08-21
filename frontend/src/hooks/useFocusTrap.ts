import { useEffect, useRef } from 'react';

const FOCUSABLE_SELECTOR =
  'a[href], button:not([disabled]), textarea:not([disabled]), input:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])';

// Fase 16.4 — nenhum dos 14 modais do app prendia o foco por teclado: Tab
// vazava pro conteúdo por trás do overlay (a lista de vagas continuava
// tabulável com o modal "por cima" só visualmente), e ao fechar o foco não
// voltava pro elemento que abriu o modal (usuário perdia o lugar na página).
// Esse hook resolve os dois: ao montar, guarda quem tinha foco e move o
// foco pro modal (primeiro elemento focável, ou o container se não achar
// nenhum); Tab/Shift+Tab dentro do modal ficam presos entre o primeiro e o
// último elemento focável; ao desmontar, devolve o foco de onde veio.
export function useFocusTrap<T extends HTMLElement>(active = true) {
  const containerRef = useRef<T>(null);
  const previouslyFocused = useRef<HTMLElement | null>(null);

  useEffect(() => {
    if (!active) return;
    previouslyFocused.current = document.activeElement as HTMLElement | null;

    const container = containerRef.current;
    if (container) {
      // Vários modais já usam autoFocus num campo específico (ex: input de
      // busca, senha) — React aplica isso de forma síncrona no commit,
      // ANTES desse efeito rodar. Se o foco já está em algo DENTRO do
      // modal, respeita (não força pro primeiro elemento focável, que às
      // vezes é só o botão ✕ do header e atropelaria o autoFocus real).
      const jaFocadoDentro = document.activeElement instanceof HTMLElement && container.contains(document.activeElement);
      if (!jaFocadoDentro) {
        const first = container.querySelector<HTMLElement>(FOCUSABLE_SELECTOR);
        (first ?? container).focus();
      }
    }

    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.key !== 'Tab' || !container) return;
      const focusables = Array.from(container.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
        .filter(el => el.offsetParent !== null); // só os visíveis de verdade
      if (focusables.length === 0) return;

      const first = focusables[0];
      const last = focusables[focusables.length - 1];
      const activeEl = document.activeElement;

      if (e.shiftKey && activeEl === first) {
        e.preventDefault();
        last.focus();
      } else if (!e.shiftKey && activeEl === last) {
        e.preventDefault();
        first.focus();
      }
    };

    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('keydown', handleKeyDown);
      previouslyFocused.current?.focus();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [active]);

  return containerRef;
}

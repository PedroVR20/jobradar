import { useCallback, useState } from 'react';

// Preferências que o Hunter foi instruído a lembrar entre conversas (não
// dentro de uma conversa só — isso o histórico normal já resolve) — ver
// ferramenta lembrarPreferencia no backend. Puramente local: o backend nunca
// guarda nada disso, só recebe a lista pronta a cada mensagem (memoryContext)
// e devolve o texto pra guardar quando o Hunter decide chamar a ferramenta.
const MEMORY_KEY = 'jobradar:hunter-memory';
const MAX_ITEMS = 30;

interface MemoryItem { texto: string; criadoEm: number }

function load(): MemoryItem[] {
  try {
    return JSON.parse(localStorage.getItem(MEMORY_KEY) ?? '[]');
  } catch {
    return [];
  }
}

function persist(items: MemoryItem[]) {
  localStorage.setItem(MEMORY_KEY, JSON.stringify(items));
}

export function useHunterMemory() {
  const [items, setItems] = useState<MemoryItem[]>(load);

  // Substitui se já existir texto igual (idempotente — o Hunter pode tentar
  // lembrar a mesma coisa de novo em conversas diferentes).
  const add = useCallback((texto: string) => {
    setItems(prev => {
      const semDuplicata = prev.filter(i => i.texto !== texto);
      const updated = [...semDuplicata, { texto, criadoEm: Date.now() }].slice(-MAX_ITEMS);
      persist(updated);
      return updated;
    });
  }, []);

  const remove = useCallback((texto: string) => {
    setItems(prev => {
      const updated = prev.filter(i => i.texto !== texto);
      persist(updated);
      return updated;
    });
  }, []);

  const clear = useCallback(() => {
    setItems([]);
    persist([]);
  }, []);

  const buildContext = useCallback(
    () => items.map(i => `- ${i.texto}`).join('\n'),
    [items]
  );

  return { items, add, remove, clear, buildContext };
}

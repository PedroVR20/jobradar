import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ShortcutsModal } from './ShortcutsModal';

// Fase 16.4 — cobre o padrão de dialog acessível aplicado nos 14 modais do
// app (role="dialog"/aria-modal/aria-labelledby + foco movido pra dentro
// do modal ao abrir, ver useFocusTrap). Usa ShortcutsModal como
// representante do padrão (é o mais simples, sem fetch assíncrono) — não
// precisa duplicar o mesmo teste nos outros 13, a lógica é idêntica.
describe('ShortcutsModal — dialog acessível', () => {
  it('expõe role="dialog"/aria-modal e o título via aria-labelledby', () => {
    render(<ShortcutsModal onClose={vi.fn()} />);

    const dialog = screen.getByRole('dialog', { name: 'Atalhos de teclado' });
    expect(dialog).toBeInTheDocument();
    expect(dialog).toHaveAttribute('aria-modal', 'true');
  });

  it('move o foco pra dentro do modal ao montar (useFocusTrap)', () => {
    render(<ShortcutsModal onClose={vi.fn()} />);

    const dialog = screen.getByRole('dialog');
    expect(dialog.contains(document.activeElement)).toBe(true);
  });
});

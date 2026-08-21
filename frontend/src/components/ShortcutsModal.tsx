import { useEscapeToClose } from '../hooks/useEscapeToClose';
import { useFocusTrap } from '../hooks/useFocusTrap';

interface Props {
  onClose: () => void;
}

// Fase 16.8 — os atalhos abaixo já funcionavam (Fase 8.3: navegação por
// teclado no grid; Ctrl+K: abre o Hunter; drag-and-drop: mover vaga entre
// abas) sem NENHUMA forma de descobrir que existiam além de tropeçar neles
// por acaso. "?" é a tecla que a maioria dos apps já usa pra isso — sem
// precisar aprender um atalho novo só pra ver os atalhos.
const GRUPOS: { titulo: string; itens: { teclas: string[]; label: string }[] }[] = [
  {
    titulo: 'Navegar entre vagas',
    itens: [
      { teclas: ['j', '↓'], label: 'Focar a próxima vaga' },
      { teclas: ['k', '↑'], label: 'Focar a vaga anterior' },
      { teclas: ['Esc'], label: 'Tirar o foco' },
    ],
  },
  {
    titulo: 'Agir na vaga focada',
    itens: [
      { teclas: ['y'], label: 'Marcar interesse' },
      { teclas: ['n'], label: 'Recusar' },
      { teclas: ['v'], label: 'Marcar como vista' },
      { teclas: ['Enter'], label: 'Abrir a vaga em outra aba' },
    ],
  },
  {
    titulo: 'Geral',
    itens: [
      { teclas: ['Ctrl', 'K'], label: 'Abrir/fechar o Hunter' },
      { teclas: ['?'], label: 'Mostrar esta folha de atalhos' },
    ],
  },
  {
    titulo: 'Mouse',
    itens: [
      { teclas: ['arraste'], label: 'Arraste um card pra cima de uma aba pra mover a vaga pra lá' },
    ],
  },
];

export function ShortcutsModal({ onClose }: Props) {
  useEscapeToClose(onClose);
  const dialogRef = useFocusTrap<HTMLDivElement>();

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div
        className="modal shortcuts-modal"
        onClick={e => e.stopPropagation()}
        ref={dialogRef}
        role="dialog"
        aria-modal="true"
        aria-labelledby="shortcuts-modal-title"
        tabIndex={-1}
      >
        <div className="modal-header">
          <span>⌨️</span>
          <h2 id="shortcuts-modal-title">Atalhos de teclado</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>

        {GRUPOS.map(grupo => (
          <div key={grupo.titulo} className="shortcuts-group">
            <h3 className="shortcuts-group-title">{grupo.titulo}</h3>
            {grupo.itens.map(item => (
              <div key={item.label} className="shortcuts-row">
                <span className="shortcuts-keys">
                  {item.teclas.map(t => <kbd key={t} className="shortcuts-kbd">{t}</kbd>)}
                </span>
                <span className="shortcuts-label">{item.label}</span>
              </div>
            ))}
          </div>
        ))}

        <p className="agenda-hint">Desativado enquanto qualquer modal está aberto ou você está digitando num campo.</p>
      </div>
    </div>
  );
}

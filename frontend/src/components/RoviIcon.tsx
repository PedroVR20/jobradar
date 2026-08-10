// Mascote do assistente (rebatizado de "Jarvis" pra "Rovi" só no frontend —
// os nomes internos, classes de CSS "jarvis-*" e os serviços do backend
// continuam iguais, é puramente cosmético/visual). SVG desenhado à mão em
// vez de baixar um PNG: sem custo, sem dependência externa, escala nítido
// em qualquer tamanho e — o motivo principal — permite animar de verdade
// os "olhos" piscando e a engrenagem girando via CSS, o que um PNG estático
// não faria sem múltiplos frames.
export function RoviIcon({ size = 20 }: { size?: number }) {
  const teeth = Array.from({ length: 8 });
  return (
    <svg
      className="rovi-icon"
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      {/* Antena com pontinha que pulsa, tipo "sinal de rádio" ligado */}
      <line x1="16" y1="9" x2="16" y2="4" stroke="var(--accent)" strokeWidth="1.6" strokeLinecap="round" />
      <circle className="rovi-antenna-tip" cx="16" cy="3.2" r="1.8" fill="var(--green)" />

      {/* Cabeça */}
      <rect x="6" y="9" width="20" height="16" rx="6" fill="var(--accent)" />

      {/* Visor com os olhos */}
      <rect x="9.5" y="14" width="13" height="7" rx="3.5" className="rovi-visor" />
      <circle className="rovi-eye" cx="13.2" cy="17.5" r="1.6" />
      <circle className="rovi-eye" cx="18.8" cy="17.5" r="1.6" />

      {/* Engrenagem no canto — gira quando passa o mouse no botão */}
      <g className="rovi-gear" transform="translate(23.5, 23)">
        <circle r="3.4" className="rovi-gear-body" />
        {teeth.map((_, i) => (
          <rect
            key={i}
            x="-0.7" y="-4.6" width="1.4" height="1.8" rx="0.4"
            className="rovi-gear-tooth"
            transform={`rotate(${i * 45})`}
          />
        ))}
        <circle r="1.2" className="rovi-gear-hole" />
      </g>
    </svg>
  );
}

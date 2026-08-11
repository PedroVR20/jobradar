// Mascote do assistente — rebatizado de "Rovi" pra "Hunter" a pedido do
// usuário, em memória do cachorro dele (falecido há 2 anos). Só cosmético
// no frontend, mesma regra de sempre: nomes internos, classes "jarvis-*" e
// os serviços do backend continuam iguais. SVG desenhado à mão em vez de
// baixar um PNG: sem custo, sem dependência externa, escala nítido em
// qualquer tamanho e — o motivo principal — permite animar de verdade os
// "olhos" piscando e a engrenagem girando via CSS, o que um PNG estático
// não faria sem múltiplos frames.
interface Props {
  size?: number;
  // Pisca continuamente sozinho, sem precisar de hover — usado nos lugares
  // onde o Hunter "é o personagem" (botão do header, painel de chat) pra
  // dar vida mesmo parado. Fica de fora por padrão no ícone pequeno
  // repetido em cada card de vaga (menu 🤖 IA) — dezenas piscando ao mesmo
  // tempo na tela seria barulho visual, não charme.
  alive?: boolean;
  // Mostra uma "?" saltitando perto da cabeça — usado só na mensagem que
  // tem uma pergunta interativa pendente (ferramenta perguntarUsuario),
  // pra deixar visualmente óbvio que ele está esperando o usuário escolher
  // algo, não só "pensando" normal.
  questioning?: boolean;
}

export function HunterIcon({ size = 20, alive = false, questioning = false }: Props) {
  const teeth = Array.from({ length: 8 });
  return (
    <svg
      className={`hunter-icon ${alive ? 'hunter-icon--alive' : ''}`}
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      aria-hidden="true"
    >
      {/* Cabeça inteira (antena + cabeça + visor + olhos) num grupo só, pra
          balançar junto quando "viva" — se só a cabeça girasse sem o visor/
          olhos junto, ficaria parecendo que a cara descolou da caixa. */}
      <g className="hunter-head-group">
        {/* Antena com pontinha que pulsa, tipo "sinal de rádio" ligado */}
        <line x1="16" y1="9" x2="16" y2="4" stroke="var(--accent)" strokeWidth="1.6" strokeLinecap="round" />
        <circle className="hunter-antenna-tip" cx="16" cy="3.2" r="1.8" fill="var(--green)" />

        {/* Cabeça */}
        <rect x="6" y="9" width="20" height="16" rx="6" fill="var(--accent)" />

        {/* Visor com os olhos */}
        <rect x="9.5" y="14" width="13" height="7" rx="3.5" className="hunter-visor" />
        <circle className="hunter-eye" cx="13.2" cy="17.5" r="1.6" />
        <circle className="hunter-eye" cx="18.8" cy="17.5" r="1.6" />
      </g>

      {/* "?" saltitando — só quando questioning=true, fora do grupo da
          cabeça de propósito (não deve girar/tremer junto com o balanço). */}
      {questioning && (
        <g className="hunter-question">
          <circle cx="26" cy="6.5" r="5.4" className="hunter-question-badge" />
          <text x="26" y="9.4" textAnchor="middle" className="hunter-question-mark">?</text>
        </g>
      )}

      {/* Engrenagem no canto — gira quando passa o mouse no botão */}
      <g className="hunter-gear" transform="translate(23.5, 23)">
        <circle r="3.4" className="hunter-gear-body" />
        {teeth.map((_, i) => (
          <rect
            key={i}
            x="-0.7" y="-4.6" width="1.4" height="1.8" rx="0.4"
            className="hunter-gear-tooth"
            transform={`rotate(${i * 45})`}
          />
        ))}
        <circle r="1.2" className="hunter-gear-hole" />
      </g>
    </svg>
  );
}

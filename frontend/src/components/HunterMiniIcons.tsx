// Ícones pequenos desenhados à mão pro chat do Hunter — mesma filosofia do
// HunterIcon.tsx: nada de emoji do sistema operacional (renderiza diferente
// em cada SO/navegador, e em alguns nem renderiza — foi assim que a gente
// achou o bug do "🧠" virando uma bolinha sem forma pro usuário). Traço
// simples via stroke, currentColor, escala nítida em qualquer tamanho.
interface IconProps {
  size?: number;
}

export function ThumbUpIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path
        d="M7 8.5V17H4.5C3.67 17 3 16.33 3 15.5V10C3 9.17 3.67 8.5 4.5 8.5H7ZM7 8.5L10.5 3.5C11 3 11.8 3 12.2 3.6C12.5 4.05 12.6 4.6 12.45 5.13L11.7 7.8H15.3C16.25 7.8 16.95 8.68 16.72 9.6L15.4 15.1C15.22 15.85 14.55 16.4 13.77 16.4H9C8.1 16.4 7.3 16 7 15.3"
        stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"
      />
    </svg>
  );
}

export function ThumbDownIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path
        d="M13 11.5V3H15.5C16.33 3 17 3.67 17 4.5V10C17 10.83 16.33 11.5 15.5 11.5H13ZM13 11.5L9.5 16.5C9 17 8.2 17 7.8 16.4C7.5 15.95 7.4 15.4 7.55 14.87L8.3 12.2H4.7C3.75 12.2 3.05 11.32 3.28 10.4L4.6 4.9C4.78 4.15 5.45 3.6 6.23 3.6H11C11.9 3.6 12.7 4 13 4.7"
        stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round"
      />
    </svg>
  );
}

export function CopyIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <rect x="7" y="7" width="10" height="10" rx="2" stroke="currentColor" strokeWidth="1.4" />
      <path d="M13 7V5C13 3.9 12.1 3 11 3H5C3.9 3 3 3.9 3 5V11C3 12.1 3.9 13 5 13H7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  );
}

export function CheckIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M4 10.5L8 14.5L16 6" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

// Balãozinho de pensamento com 3 pontinhos — não é um cérebro literal de
// propósito, combina mais com a estética de robô do resto do mascote (a
// mesma linguagem visual da antena/engrenagem) do que um emoji de anatomia.
export function ThinkingIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path
        d="M4 9C4 5.7 6.7 3 10 3C13.3 3 16 5.7 16 9C16 12.3 13.3 15 10 15C9.3 15 8.6 14.9 8 14.6L4.5 16L5.4 13C4.5 12 4 10.6 4 9Z"
        stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round"
      />
      <circle cx="7.3" cy="9" r="0.9" fill="currentColor" />
      <circle cx="10" cy="9" r="0.9" fill="currentColor" />
      <circle cx="12.7" cy="9" r="0.9" fill="currentColor" />
    </svg>
  );
}

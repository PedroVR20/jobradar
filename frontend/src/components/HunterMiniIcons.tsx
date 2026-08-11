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

export function BookIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M10 5.5C9 4.5 7 4 4.5 4.2C4 4.24 3.6 4.65 3.6 5.15V14.15C3.6 14.75 4.15 15.2 4.7 15.1C6.7 14.8 8.7 15.3 10 16.2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M10 5.5C11 4.5 13 4 15.5 4.2C16 4.24 16.4 4.65 16.4 5.15V14.15C16.4 14.75 15.85 15.2 15.3 15.1C13.3 14.8 11.3 15.3 10 16.2" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      <line x1="10" y1="5.5" x2="10" y2="16.2" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

export function SuccessIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <circle cx="10" cy="10" r="7" stroke="currentColor" strokeWidth="1.3" />
      <path d="M6.8 10.2L8.8 12.2L13.2 7.6" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function WarningIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M10 3.2L17.3 15.8C17.6 16.35 17.2 17 16.6 17H3.4C2.8 17 2.4 16.35 2.7 15.8L10 3.2Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <line x1="10" y1="8.3" x2="10" y2="11.7" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <circle cx="10" cy="14.1" r="0.9" fill="currentColor" />
    </svg>
  );
}

export function ClipIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path
        d="M6 11L12.5 4.5C14 3 16.3 3 17.7 4.5C19 6 19 8.2 17.7 9.5L10.3 17C9.3 18 7.6 18 6.6 17C5.6 16 5.6 14.3 6.6 13.3L13 6.9C13.5 6.4 14.3 6.4 14.8 6.9C15.2 7.4 15.2 8.1 14.8 8.6L9.5 13.9"
        stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round"
      />
    </svg>
  );
}

// Estrelinha girando — indicador de "trabalhando" enquanto espera resposta,
// no lugar dos 3 pontinhos. Não é uma cópia do ícone de nenhuma marca
// específica, só a mesma ideia geral (um símbolo girando = "processando").
export function SparkleIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path
        d="M10 1.5L11.6 8.4L18.5 10L11.6 11.6L10 18.5L8.4 11.6L1.5 10L8.4 8.4L10 1.5Z"
        fill="currentColor"
      />
    </svg>
  );
}

// Modo compacto (esconde os cards visuais de ferramenta, só texto) — três
// linhas de tamanhos diferentes, sugerindo "texto corrido" em vez de blocos.
export function CompactIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <line x1="3" y1="5.5" x2="17" y2="5.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="3" y1="10" x2="13" y2="10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="3" y1="14.5" x2="15.5" y2="14.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function NoteIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M13.5 3.5L16.5 6.5L7 16L3.5 16.5L4 13L13.5 3.5Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
      <line x1="11.5" y1="5.5" x2="14.5" y2="8.5" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

export function MicIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <rect x="7" y="2.5" width="6" height="10" rx="3" stroke="currentColor" strokeWidth="1.4" />
      <path d="M4.5 9.5C4.5 12.8 7 15.2 10 15.2C13 15.2 15.5 12.8 15.5 9.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <line x1="10" y1="15.2" x2="10" y2="17.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <line x1="6.8" y1="17.8" x2="13.2" y2="17.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
    </svg>
  );
}

export function CloseIcon({ size = 12 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <line x1="4" y1="4" x2="16" y2="16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="16" y1="4" x2="4" y2="16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function HistoryIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <circle cx="10" cy="10.5" r="7" stroke="currentColor" strokeWidth="1.3" />
      <path d="M10 6.5V10.5L13 12.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.5 3.5L4 5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}

export function ChatBubbleIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M3.5 5.5C3.5 4.4 4.4 3.5 5.5 3.5H14.5C15.6 3.5 16.5 4.4 16.5 5.5V11.5C16.5 12.6 15.6 13.5 14.5 13.5H8.5L5 16.5V13.5H5.5C4.4 13.5 3.5 12.6 3.5 11.5V5.5Z" stroke="currentColor" strokeWidth="1.3" strokeLinejoin="round" />
    </svg>
  );
}

export function PlusIcon({ size = 14 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <line x1="10" y1="4" x2="10" y2="16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="4" y1="10" x2="16" y2="10" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function TrashIcon({ size = 13 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M4 6H16" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M7 6V4.5C7 3.9 7.4 3.5 8 3.5H12C12.6 3.5 13 3.9 13 4.5V6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M5.5 6L6.2 15.5C6.25 16.1 6.75 16.5 7.3 16.5H12.7C13.25 16.5 13.75 16.1 13.8 15.5L14.5 6" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function TargetIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <circle cx="10" cy="10" r="7" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="10" cy="10" r="4" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="10" cy="10" r="1.2" fill="currentColor" />
    </svg>
  );
}

export function CycleIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <path d="M16 8.5C15.5 5.5 13 3.5 10 3.5C7 3.5 4.5 5.5 4 8.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M4 11.5C4.5 14.5 7 16.5 10 16.5C13 16.5 15.5 14.5 16 11.5" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
      <path d="M13.5 8.5H16.2V5.8" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
      <path d="M6.5 11.5H3.8V14.2" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function ChartIcon({ size = 15 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <line x1="4" y1="16" x2="4" y2="9" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="10" y1="16" x2="10" y2="4" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
      <line x1="16" y1="16" x2="16" y2="12" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

export function TimerIcon({ size = 12 }: IconProps) {
  return (
    <svg width={size} height={size} viewBox="0 0 20 20" fill="none" xmlns="http://www.w3.org/2000/svg" aria-hidden="true">
      <circle cx="10" cy="11" r="6.5" stroke="currentColor" strokeWidth="1.3" />
      <path d="M10 7.5V11L12.5 12.8" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
      <line x1="8" y1="2.5" x2="12" y2="2.5" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
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

// Fase 10.1 — carregado uma vez antes de toda suíte (ver vite.config.ts
// test.setupFiles) — registra os matchers do jest-dom (toBeInTheDocument,
// toHaveTextContent etc) globalmente, sem precisar importar em todo arquivo.
import '@testing-library/jest-dom';

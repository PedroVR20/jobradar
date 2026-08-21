/// <reference types="vitest/config" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  },
  // Fase 10.1 — primeiros testes de frontend. jsdom simula o DOM (não roda
  // navegador de verdade, então continua rápido o bastante pra rodar no CI
  // a cada push). setupFiles registra os matchers do jest-dom (toBeInTheDocument
  // etc) uma vez, pra não precisar importar em todo arquivo de teste.
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
})

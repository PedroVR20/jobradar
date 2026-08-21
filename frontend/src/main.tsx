import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import App from './App'

// Fase 4.3 — fontes empacotadas no bundle (@fontsource) em vez de
// carregadas via Google Fonts CDN: sem requisição render-blocking pra
// fora, funciona offline/rede restrita, e não vaza o IP do usuário pro
// Google a cada carregamento da página. Só os pesos que o App.css usa de
// verdade (ver font-weight/font-family nas regras existentes).
import '@fontsource/inter/400.css'
import '@fontsource/inter/500.css'
import '@fontsource/inter/600.css'
import '@fontsource/inter/700.css'
import '@fontsource/jetbrains-mono/400.css'
import '@fontsource/jetbrains-mono/500.css'

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>
)

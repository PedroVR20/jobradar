import { useState } from 'react';

// Perfil/currículo do candidato, salvo uma vez em Configurações e reaproveitado
// como contexto padrão toda vez que uma carta de apresentação é gerada — evita
// ter que colar o mesmo resumo de experiência a cada vaga. Fica só no navegador
// (localStorage), nunca é enviado a lugar nenhum além do prompt do Gemini
// quando você gera uma carta.
const PROFILE_KEY = 'jobradar:candidate-profile';

export function useCandidateProfile() {
  const [profile, setProfileState] = useState(() => localStorage.getItem(PROFILE_KEY) ?? '');

  const setProfile = (text: string) => {
    setProfileState(text);
    if (text.trim()) localStorage.setItem(PROFILE_KEY, text);
    else localStorage.removeItem(PROFILE_KEY);
  };

  return { profile, setProfile };
}

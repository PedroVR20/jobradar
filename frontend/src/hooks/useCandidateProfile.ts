import { useState } from 'react';

// Perfil/currículo do candidato, salvo uma vez em Configurações (upload de
// PDF/DOCX ou colado manualmente) e reaproveitado como contexto padrão toda
// vez que uma carta de apresentação é gerada — evita ter que colar o mesmo
// resumo de experiência a cada vaga. O texto extraído fica só no navegador
// (localStorage); o arquivo original nunca é enviado a lugar nenhum, só é
// lido localmente pra extrair o texto (ver utils/extractResumeText.ts).
const PROFILE_KEY = 'jobradar:candidate-profile';
const PROFILE_FILENAME_KEY = 'jobradar:candidate-profile-filename';

export function useCandidateProfile() {
  const [profile, setProfileState] = useState(() => localStorage.getItem(PROFILE_KEY) ?? '');
  const [fileName, setFileNameState] = useState(() => localStorage.getItem(PROFILE_FILENAME_KEY) ?? '');

  // sourceFileName: passa o nome do arquivo quando o texto veio de um
  // upload (mostra o "chip" de arquivo); omite quando é edição manual do
  // texto já extraído, pra não perder a referência de onde ele veio;
  // passa null explicitamente pra limpar tudo (botão "Remover").
  const setProfile = (text: string, sourceFileName?: string | null) => {
    setProfileState(text);
    if (text.trim()) localStorage.setItem(PROFILE_KEY, text);
    else localStorage.removeItem(PROFILE_KEY);

    if (sourceFileName !== undefined) {
      const name = sourceFileName ?? '';
      setFileNameState(name);
      if (name) localStorage.setItem(PROFILE_FILENAME_KEY, name);
      else localStorage.removeItem(PROFILE_FILENAME_KEY);
    }
  };

  return { profile, fileName, setProfile };
}

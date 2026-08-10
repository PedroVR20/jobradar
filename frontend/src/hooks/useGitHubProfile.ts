import { useState } from 'react';

// Enriquece o perfil do candidato com os repositórios públicos do GitHub —
// currículo raramente lista TODOS os projetos, e o Hunter consegue avaliar
// compatibilidade melhor vendo o que a pessoa realmente construiu. Busca
// direto do navegador pra API pública do GitHub (api.github.com aceita CORS
// em GET, sem precisar de token) — mesma regra de privacidade do currículo:
// nunca passa pelo backend, só o resumo texto vai junto do request quando
// uma análise de IA é gerada. Só repositórios PÚBLICOS por natureza da API —
// privados nunca aparecem, nem tem como.
const USERNAME_KEY = 'jobradar:github-username';
const SUMMARY_KEY = 'jobradar:github-summary';
const REPO_COUNT_KEY = 'jobradar:github-repo-count';
const FETCHED_AT_KEY = 'jobradar:github-fetched-at';

// Não manda os 100 repositórios pro prompt — custaria tokens à toa e a
// maioria dos perfis tem poucos realmente relevantes. Prioriza por estrelas
// (sinal de relevância real) e depois por atividade recente.
const MAX_REPOS_IN_SUMMARY = 20;

export class GitHubFetchError extends Error {}

interface RawRepo {
  name: string;
  description: string | null;
  language: string | null;
  topics?: string[];
  stargazers_count: number;
  pushed_at: string;
  fork: boolean;
  private: boolean;
}

function parseUsername(input: string): string {
  const trimmed = input.trim();
  const urlMatch = trimmed.match(/github\.com\/([^/?#]+)/i);
  const raw = urlMatch ? urlMatch[1] : trimmed;
  return raw.replace(/^@/, '').replace(/\/+$/, '');
}

async function fetchPublicRepos(username: string): Promise<RawRepo[]> {
  const userRes = await fetch(`https://api.github.com/users/${encodeURIComponent(username)}`);
  if (userRes.status === 404) throw new GitHubFetchError('Usuário não encontrado no GitHub — confere se digitou certo.');
  if (userRes.status === 403) throw new GitHubFetchError('Limite de chamadas à API do GitHub atingido agora (é público, sem login — tem um teto por hora). Tenta de novo daqui a pouco.');
  if (!userRes.ok) throw new GitHubFetchError('Não foi possível acessar o GitHub agora. Tenta de novo em instantes.');

  const reposRes = await fetch(`https://api.github.com/users/${encodeURIComponent(username)}/repos?sort=updated&per_page=100&type=owner`);
  if (!reposRes.ok) throw new GitHubFetchError('Não foi possível buscar os repositórios agora. Tenta de novo em instantes.');
  const repos = (await reposRes.json()) as RawRepo[];
  // forks não são autoria da pessoa (código de outro projeto) — fora do resumo,
  // que é sobre o que ela de fato construiu.
  return repos.filter(r => !r.fork);
}

function buildSummaryText(username: string, repos: RawRepo[]): string {
  const ordenados = [...repos].sort((a, b) =>
    b.stargazers_count - a.stargazers_count ||
    new Date(b.pushed_at).getTime() - new Date(a.pushed_at).getTime()
  );
  const top = ordenados.slice(0, MAX_REPOS_IN_SUMMARY);
  const linhas = top.map(r => {
    const partes = [`- ${r.name}`];
    if (r.language) partes.push(`(${r.language})`);
    if (r.description) partes.push(`— ${r.description}`);
    if (r.topics && r.topics.length > 0) partes.push(`[tags: ${r.topics.join(', ')}]`);
    if (r.stargazers_count > 0) partes.push(`★${r.stargazers_count}`);
    return partes.join(' ');
  });
  const total = repos.length;
  const nota = total > MAX_REPOS_IN_SUMMARY ? `, mostrando os ${MAX_REPOS_IN_SUMMARY} mais relevantes (estrelas/atividade)` : '';
  return `Repositórios públicos no GitHub de @${username} (${total} repositório${total === 1 ? '' : 's'}${nota}):\n${linhas.join('\n')}`;
}

// Concatena currículo + resumo do GitHub pro texto que realmente vai no
// prompt de IA — o campo visível (textarea do currículo) continua só com o
// currículo, essa junção acontece só na hora de montar o request, igual o
// feedbackContext já funciona (nunca aparece na tela, só entra na chamada).
export function combineWithGitHub(profileText: string, githubSummary: string): string {
  if (!githubSummary.trim()) return profileText;
  return profileText.trim() ? `${profileText}\n\n${githubSummary}` : githubSummary;
}

export function useGitHubProfile() {
  const [username, setUsernameState] = useState(() => localStorage.getItem(USERNAME_KEY) ?? '');
  const [summary, setSummaryState] = useState(() => localStorage.getItem(SUMMARY_KEY) ?? '');
  const [repoCount, setRepoCountState] = useState(() => {
    const raw = localStorage.getItem(REPO_COUNT_KEY);
    return raw ? parseInt(raw, 10) : 0;
  });
  const [fetchedAt, setFetchedAtState] = useState<number | null>(() => {
    const raw = localStorage.getItem(FETCHED_AT_KEY);
    return raw ? parseInt(raw, 10) : null;
  });

  const clear = () => {
    setUsernameState('');
    setSummaryState('');
    setRepoCountState(0);
    setFetchedAtState(null);
    localStorage.removeItem(USERNAME_KEY);
    localStorage.removeItem(SUMMARY_KEY);
    localStorage.removeItem(REPO_COUNT_KEY);
    localStorage.removeItem(FETCHED_AT_KEY);
  };

  const analyze = async (input: string) => {
    const uname = parseUsername(input);
    if (!uname) throw new GitHubFetchError('Informe seu usuário do GitHub.');
    const repos = await fetchPublicRepos(uname);
    const summaryText = buildSummaryText(uname, repos);
    const now = Date.now();
    setUsernameState(uname);
    setSummaryState(summaryText);
    setRepoCountState(repos.length);
    setFetchedAtState(now);
    localStorage.setItem(USERNAME_KEY, uname);
    localStorage.setItem(SUMMARY_KEY, summaryText);
    localStorage.setItem(REPO_COUNT_KEY, String(repos.length));
    localStorage.setItem(FETCHED_AT_KEY, String(now));
  };

  return { username, summary, repoCount, fetchedAt, analyze, clear };
}

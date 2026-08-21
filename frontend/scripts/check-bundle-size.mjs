// Fase 15.2 — guarda de performance no CI. Sem isso, uma dependência pesada
// entrando por acidente no bundle PRINCIPAL (o que carrega em toda visita,
// diferente dos chunks lazy tipo pdf.worker/extractResumeText, que só
// carregam quando o usuário usa aquela feature) só seria percebida quando
// alguém notasse o app mais lento na prática — sem alarme automático.
//
// Cobre só o entry point (assets/index-*.js/.css, o dist/index.html
// referencia exatamente esses dois na primeira carga) contra um orçamento
// de tamanho gzip. Os chunks lazy (import() dinâmico — extração de currículo
// em PDF, etc) ficam de fora de propósito: aqueles só pesam pra quem usa a
// feature, não pra todo mundo que abre o app.
//
// Orçamento com margem generosa sobre o tamanho medido em 2026-08-21
// (JS: 351.94kB bruto / 102.35kB gzip · CSS: 103.07kB bruto / 28.12kB gzip)
// — o objetivo é pegar um salto grande e inesperado (ex: import de uma lib
// inteira em vez de uma função dela), não brigar por cada KB.
import { readdirSync, readFileSync, statSync } from 'node:fs';
import { gzipSync } from 'node:zlib';
import { join } from 'node:path';

const DIST_DIR = join(import.meta.dirname, '..', 'dist', 'assets');

const BUDGETS_GZIP_BYTES = {
  js: 170 * 1024,  // ~170KB — atual ~102KB
  css: 45 * 1024,  // ~45KB — atual ~28KB
};

function findEntryAsset(ext) {
  // O entry principal do Vite segue o padrão index-<hash>.<ext> — os chunks
  // lazy têm outros nomes (extractResumeText-<hash>.js, pdf.worker.min-<hash>.mjs).
  return readdirSync(DIST_DIR).find(f => new RegExp(`^index-.*\\.${ext}$`).test(f));
}

function gzipSizeOf(filename) {
  const raw = readFileSync(join(DIST_DIR, filename));
  return gzipSync(raw, { level: 9 }).length;
}

function fmtKb(bytes) {
  return `${(bytes / 1024).toFixed(1)}KB`;
}

let failed = false;

for (const [ext, budget] of Object.entries(BUDGETS_GZIP_BYTES)) {
  const file = findEntryAsset(ext);
  if (!file) {
    console.error(`❌ Guarda de bundle: não encontrei o entry .${ext} em ${DIST_DIR} (rodou "npm run build" antes?)`);
    failed = true;
    continue;
  }
  const size = gzipSizeOf(file);
  const status = size > budget ? '❌' : '✅';
  console.log(`${status} ${file}: ${fmtKb(size)} gzip (orçamento: ${fmtKb(budget)})`);
  if (size > budget) failed = true;
}

if (failed) {
  console.error('\nBundle principal passou do orçamento de tamanho — confira se alguma dependência nova entrou fora de um import() lazy.');
  process.exit(1);
}

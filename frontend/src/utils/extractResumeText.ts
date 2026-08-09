import * as pdfjsLib from 'pdfjs-dist';
import mammoth from 'mammoth';

// Worker do pdf.js: aponta pro arquivo publicado dentro do próprio pacote —
// o `new URL(..., import.meta.url)` é reconhecido estaticamente pelo Vite e
// vira um asset bundlado, sem depender de CDN nem de configuração extra.
pdfjsLib.GlobalWorkerOptions.workerSrc = new URL(
  'pdfjs-dist/build/pdf.worker.min.mjs',
  import.meta.url
).toString();

export const MAX_RESUME_FILE_SIZE = 8 * 1024 * 1024; // 8MB — currículo real nunca chega perto disso

export class ResumeExtractionError extends Error {}

async function extractPdfText(buffer: ArrayBuffer): Promise<string> {
  const doc = await pdfjsLib.getDocument({ data: buffer }).promise;
  const pages: string[] = [];
  for (let i = 1; i <= doc.numPages; i++) {
    const page = await doc.getPage(i);
    const content = await page.getTextContent();
    const pageText = content.items
      .map(item => ('str' in item ? item.str : ''))
      .join(' ');
    pages.push(pageText);
  }
  return pages.join('\n\n');
}

async function extractDocxText(buffer: ArrayBuffer): Promise<string> {
  const result = await mammoth.extractRawText({ arrayBuffer: buffer });
  return result.value;
}

function cleanup(text: string): string {
  return text
    .replace(/[ \t]+/g, ' ')
    .replace(/\n{3,}/g, '\n\n')
    .trim();
}

// Extrai o texto de um currículo em PDF ou DOCX inteiramente no navegador —
// o arquivo nunca sai da máquina do usuário nem é enviado ao backend, só o
// texto extraído (que o usuário ainda pode revisar/editar) é salvo depois
// via useCandidateProfile.
export async function extractResumeText(file: File): Promise<string> {
  if (file.size > MAX_RESUME_FILE_SIZE) {
    throw new ResumeExtractionError('Arquivo muito grande (máximo 8MB).');
  }

  const name = file.name.toLowerCase();
  const buffer = await file.arrayBuffer();

  let raw: string;
  try {
    if (file.type === 'application/pdf' || name.endsWith('.pdf')) {
      raw = await extractPdfText(buffer);
    } else if (
      file.type === 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' ||
      name.endsWith('.docx')
    ) {
      raw = await extractDocxText(buffer);
    } else if (name.endsWith('.doc')) {
      throw new ResumeExtractionError('Formato .doc (Word antigo) não é suportado — salve como .docx ou PDF e tente de novo.');
    } else {
      throw new ResumeExtractionError('Formato não suportado — envie um arquivo PDF ou DOCX.');
    }
  } catch (e) {
    if (e instanceof ResumeExtractionError) throw e;
    console.error('extractResumeText falhou:', e); // eslint-disable-line no-console
    throw new ResumeExtractionError('Não consegui ler esse arquivo. Ele pode estar corrompido ou protegido por senha.');
  }

  const text = cleanup(raw);
  if (text.length < 20) {
    throw new ResumeExtractionError(
      'Não encontrei texto legível nesse arquivo — pode ser um PDF escaneado (imagem, sem texto selecionável). Tente colar o texto manualmente.'
    );
  }
  return text;
}

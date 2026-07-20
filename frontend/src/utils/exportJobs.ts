import { Job } from '../types/Job';

const CSV_COLUMNS: (keyof Job)[] = [
  'id', 'title', 'company', 'source', 'seniority', 'workplaceType',
  'state', 'city', 'salary', 'postedAt', 'expiresAt',
  'seen', 'applied', 'appliedAt', 'inProgress', 'inProgressAt',
  'rejected', 'rejectedAt', 'pinned', 'notes', 'url',
];

function csvCell(value: unknown): string {
  if (value === null || value === undefined) return '';
  const str = Array.isArray(value) ? value.join('; ') : String(value);
  if (/[",\n]/.test(str)) return `"${str.replace(/"/g, '""')}"`;
  return str;
}

export function jobsToCsv(jobs: Job[]): string {
  const header = CSV_COLUMNS.join(',');
  const rows = jobs.map(job => CSV_COLUMNS.map(col => csvCell(job[col])).join(','));
  return [header, ...rows].join('\n');
}

export function downloadFile(filename: string, content: string, mime: string): void {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

function todayStamp(): string {
  const d = new Date();
  const pad = (n: number) => n.toString().padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
}

export async function exportAllJobs(format: 'csv' | 'json'): Promise<void> {
  const res = await fetch('/api/jobs');
  const jobs = await res.json() as Job[];
  const stamp = todayStamp();
  if (format === 'json') {
    downloadFile(`job-radar-historico-${stamp}.json`, JSON.stringify(jobs, null, 2), 'application/json');
  } else {
    downloadFile(`job-radar-historico-${stamp}.csv`, jobsToCsv(jobs), 'text/csv');
  }
}

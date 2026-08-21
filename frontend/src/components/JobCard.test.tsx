import { ComponentProps } from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { JobCard } from './JobCard';
import { Job } from '../types/Job';

// Fase 10.1 — smoke test do componente mais usado do app (renderiza 20-50+
// vezes por tela, ver React.memo da Fase 13.2). Cobre o básico que mais
// fácil quebra sem ninguém notar: título/empresa aparecem, e clicar em
// "Ver vaga" marca a vaga como vista (efeito colateral real do link, não
// só navegação — ver onClick={() => onSeen(job.id)} em JobCard.tsx).
function baseJob(overrides: Partial<Job> = {}): Job {
  return {
    id: 42,
    title: 'Engenheiro de Software Pleno',
    company: 'Acme Tecnologia',
    url: 'https://example.com/vaga/42',
    source: 'GUPY',
    seniority: 'PLENO',
    salary: null,
    workplaceType: 'REMOTO',
    state: null,
    city: null,
    tags: ['java', 'spring'],
    postedAt: null,
    expiresAt: null,
    fetchedAt: null,
    seen: false,
    interested: false,
    applied: false,
    appliedAt: null,
    inProgress: false,
    inProgressAt: null,
    rejected: false,
    rejectedAt: null,
    companyLogoUrl: null,
    pcd: false,
    pinned: false,
    notes: null,
    classifiedByAi: false,
    archived: false,
    archivedReason: null,
    linkMorto: null,
    rejectedReason: null,
    ...overrides,
  };
}

function noop() {}

function renderCard(job: Job, overrides: Partial<ComponentProps<typeof JobCard>> = {}) {
  return render(
    <JobCard
      job={job}
      onSeen={overrides.onSeen ?? noop}
      onApplied={overrides.onApplied ?? noop}
      onInProgress={overrides.onInProgress ?? noop}
      onSetStatus={overrides.onSetStatus ?? noop}
      onTogglePin={overrides.onTogglePin ?? noop}
      onUpdateNotes={overrides.onUpdateNotes ?? noop}
      onToast={overrides.onToast ?? noop}
      aiEnabled={overrides.aiEnabled ?? false}
      sortMode={overrides.sortMode ?? 'posted_desc'}
    />
  );
}

describe('JobCard', () => {
  it('renderiza título e empresa da vaga', () => {
    renderCard(baseJob());
    expect(screen.getByText('Engenheiro de Software Pleno')).toBeInTheDocument();
    expect(screen.getByText(/Acme Tecnologia/)).toBeInTheDocument();
  });

  it('clicar em "Ver vaga" marca a vaga como vista', async () => {
    const onSeen = vi.fn();
    renderCard(baseJob(), { onSeen });

    await userEvent.click(screen.getByText('Ver vaga →'));

    expect(onSeen).toHaveBeenCalledWith(42);
  });

  it('vaga já vista NÃO mostra o botão "Salvar na Agenda" pra vaga recusada', () => {
    renderCard(baseJob({ rejected: true }));
    expect(screen.queryByText('📅 Salvar na Agenda')).not.toBeInTheDocument();
  });
});

// Fase 4.4 — skeleton no lugar de "Carregando..." no grid principal. O
// fetch inicial roda em background por minutos (ver comentário em
// JobAggregatorService.fetchNaInicializacao no backend) — o carregamento
// visível aqui normalmente é rápido (é só a query no banco já populado),
// mas ainda assim dá uma sensação de app "vivo" desde o primeiro frame em
// vez de um spinner genérico parado no centro da tela.
export function SkeletonCard() {
  return (
    <div className="job-card skeleton-card" aria-hidden="true">
      <div className="card-header">
        <div className="card-header-left">
          <div className="skeleton-block skeleton-avatar" />
          <div className="skeleton-lines">
            <div className="skeleton-block skeleton-line skeleton-line--title" />
            <div className="skeleton-block skeleton-line skeleton-line--subtitle" />
          </div>
        </div>
      </div>
      <div className="skeleton-badges">
        <div className="skeleton-block skeleton-pill" />
        <div className="skeleton-block skeleton-pill" />
        <div className="skeleton-block skeleton-pill" />
      </div>
      <div className="skeleton-block skeleton-line skeleton-line--full" />
      <div className="skeleton-block skeleton-line skeleton-line--full" />
    </div>
  );
}

export function SkeletonGrid({ count = 9 }: { count?: number }) {
  return (
    <div className="jobs-grid" role="status" aria-label="Carregando vagas">
      {Array.from({ length: count }, (_, i) => <SkeletonCard key={i} />)}
    </div>
  );
}

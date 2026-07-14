import { useState, FormEvent } from 'react';
import { JobStatus, ManualJobPayload, WorkplaceType, statusMeta, workplaceMeta } from '../types/Job';

interface Props {
  onClose: () => void;
  onSubmit: (payload: ManualJobPayload) => Promise<boolean>;
}

const emptyForm = {
  title: '',
  company: '',
  url: '',
  source: '',
  salary: '',
  workplaceType: '' as WorkplaceType | '',
  state: '',
  city: '',
  status: 'APLICADA' as JobStatus,
};

export function AddJobModal({ onClose, onSubmit }: Props) {
  const [form, setForm] = useState(emptyForm);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const set = (partial: Partial<typeof form>) => setForm(f => ({ ...f, ...partial }));

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (!form.title.trim() || !form.company.trim() || !form.url.trim()) {
      setError('Preencha pelo menos título, empresa e link da vaga.');
      return;
    }
    setSaving(true);
    setError(null);
    const ok = await onSubmit({
      title: form.title.trim(),
      company: form.company.trim(),
      url: form.url.trim(),
      source: form.source.trim() || undefined,
      salary: form.salary.trim() || undefined,
      workplaceType: form.workplaceType || undefined,
      state: form.state.trim() || undefined,
      city: form.city.trim() || undefined,
      status: form.status,
    });
    setSaving(false);
    if (!ok) {
      setError('Não deu pra salvar. Confira o link (tem que ser uma URL válida) e tente de novo.');
    }
  };

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>➕ Adicionar vaga manualmente</h2>
          <button className="modal-close" onClick={onClose} aria-label="Fechar">✕</button>
        </div>
        <p className="modal-subtitle">
          Pra vagas achadas fora das buscas automáticas — Glassdoor, LinkedIn, indicação etc.
        </p>

        <form className="modal-form" onSubmit={handleSubmit}>
          <label className="form-field">
            <span>Título da vaga *</span>
            <input
              type="text"
              value={form.title}
              onChange={e => set({ title: e.target.value })}
              placeholder="Ex: Estágio em TI"
              autoFocus
            />
          </label>

          <label className="form-field">
            <span>Empresa *</span>
            <input
              type="text"
              value={form.company}
              onChange={e => set({ company: e.target.value })}
              placeholder="Ex: Hilton"
            />
          </label>

          <label className="form-field">
            <span>Link da vaga *</span>
            <input
              type="url"
              value={form.url}
              onChange={e => set({ url: e.target.value })}
              placeholder="https://..."
            />
          </label>

          <div className="form-row">
            <label className="form-field">
              <span>Fonte (opcional)</span>
              <input
                type="text"
                value={form.source}
                onChange={e => set({ source: e.target.value })}
                placeholder="Ex: Glassdoor"
              />
            </label>

            <label className="form-field">
              <span>Salário (opcional)</span>
              <input
                type="text"
                value={form.salary}
                onChange={e => set({ salary: e.target.value })}
                placeholder="Ex: R$ 1.800,00"
              />
            </label>
          </div>

          <div className="form-row">
            <label className="form-field">
              <span>Modalidade</span>
              <select value={form.workplaceType} onChange={e => set({ workplaceType: e.target.value as WorkplaceType | '' })}>
                <option value="">Não informado</option>
                <option value="REMOTO">{workplaceMeta.REMOTO.icon} Remoto</option>
                <option value="HIBRIDO">{workplaceMeta.HIBRIDO.icon} Híbrido</option>
                <option value="PRESENCIAL">{workplaceMeta.PRESENCIAL.icon} Presencial</option>
              </select>
            </label>

            <label className="form-field">
              <span>Cidade</span>
              <input
                type="text"
                value={form.city}
                onChange={e => set({ city: e.target.value })}
                placeholder="Ex: São Paulo"
              />
            </label>

            <label className="form-field">
              <span>Estado</span>
              <input
                type="text"
                value={form.state}
                onChange={e => set({ state: e.target.value })}
                placeholder="Ex: São Paulo"
              />
            </label>
          </div>

          <label className="form-field">
            <span>Status</span>
            <select value={form.status} onChange={e => set({ status: e.target.value as JobStatus })}>
              {(Object.keys(statusMeta) as JobStatus[]).map(s => (
                <option key={s} value={s}>{statusMeta[s]}</option>
              ))}
            </select>
          </label>

          {error && <div className="form-error">⚠️ {error}</div>}

          <div className="modal-actions">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Cancelar</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Salvando...' : 'Adicionar vaga'}
            </button>
          </div>
        </form>
      </div>
    </div>
  );
}

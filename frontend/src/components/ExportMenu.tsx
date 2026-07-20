import { useEffect, useRef, useState } from 'react';
import { exportAllJobs } from '../utils/exportJobs';

interface Props {
  onExported: (format: 'csv' | 'json') => void;
}

export function ExportMenu({ onExported }: Props) {
  const [open, setOpen] = useState(false);
  const [exporting, setExporting] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const handleClickOutside = (e: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(e.target as Node)) {
        setOpen(false);
      }
    };
    document.addEventListener('mousedown', handleClickOutside);
    return () => document.removeEventListener('mousedown', handleClickOutside);
  }, [open]);

  const handleExport = async (format: 'csv' | 'json') => {
    setOpen(false);
    setExporting(true);
    try {
      await exportAllJobs(format);
      onExported(format);
    } finally {
      setExporting(false);
    }
  };

  return (
    <div className="export-menu" ref={menuRef}>
      <button className="btn btn-ghost" onClick={() => setOpen(o => !o)} disabled={exporting}>
        {exporting ? '⏳' : '⬇️'} Exportar
      </button>
      {open && (
        <div className="export-menu-dropdown">
          <button className="export-menu-item" onClick={() => handleExport('csv')}>📄 CSV</button>
          <button className="export-menu-item" onClick={() => handleExport('json')}>🗂 JSON</button>
        </div>
      )}
    </div>
  );
}

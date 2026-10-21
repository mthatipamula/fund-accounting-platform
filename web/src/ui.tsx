import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from 'react';
import type { Basis } from './api';
import type { Role } from './auth';
import { fmtTime } from './format';

/** The global "which fund, as of when, as known when" selection shared by every page. */
export interface View { fundId: string; asOf: string; knownAt: string; roles: Set<Role>; refresh: number; bump: () => void }
export const ViewContext = createContext<View>(null!);
export const useView = () => useContext(ViewContext);
export const can = (v: View, role: 'write' | 'admin') =>
  role === 'admin' ? v.roles.has('fa-admin') : v.roles.has('fa-accountant') || v.roles.has('fa-admin');

/** Loads data and reloads when its inputs change. */
export function useLoad<T>(load: () => Promise<T>, deps: unknown[]): { data: T | null; error: string | null; loading: boolean; reload: () => void } {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [n, setN] = useState(0);
  // eslint-disable-next-line react-hooks/exhaustive-deps
  const run = useCallback(load, deps);
  useEffect(() => {
    let live = true;
    setLoading(true);
    run().then((d) => { if (live) { setData(d); setError(null); } })
      .catch((e) => { if (live) setError((e as Error).message); })
      .finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
  }, [run, n]);
  return { data, error, loading, reload: () => setN((x) => x + 1) };
}

export function Loading({ error, loading }: { error: string | null; loading: boolean }) {
  if (error) return <div className="notice error" role="alert">{error}</div>;
  return loading ? <p className="muted">Loading…</p> : null;
}

/** States exactly which version of the books a report shows. */
export function BasisNote({ basis }: { basis: Basis | undefined }) {
  if (!basis) return null;
  return (
    <p className="basis small muted">
      As of <strong>{basis.asOf}</strong>, as known at <strong>{fmtTime(basis.knownAt)}</strong>
      {basis.lockedByClose && <span className="tag">Closed period: figures as reported at close</span>}
    </p>
  );
}

export function Stat({ label, value, sub }: { label: string; value: ReactNode; sub?: ReactNode }) {
  return <div className="stat"><div className="stat-label">{label}</div><div className="stat-value">{value}</div>{sub && <div className="small muted">{sub}</div>}</div>;
}

export function Section({ title, actions, children }: { title: string; actions?: ReactNode; children: ReactNode }) {
  return (
    <section className="card">
      <div className="card-head"><h2>{title}</h2>{actions && <div className="row">{actions}</div>}</div>
      {children}
    </section>
  );
}

export function Modal({ title, onClose, children }: { title: string; onClose: () => void; children: ReactNode }) {
  useEffect(() => {
    const esc = (e: KeyboardEvent) => e.key === 'Escape' && onClose();
    window.addEventListener('keydown', esc);
    return () => window.removeEventListener('keydown', esc);
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="modal" role="dialog" aria-modal="true" aria-label={title}>
        <div className="card-head"><h2>{title}</h2><button className="link" onClick={onClose} aria-label="Close">✕</button></div>
        {children}
      </div>
    </div>
  );
}

export function Field({ label, children, hint }: { label: string; children: ReactNode; hint?: string }) {
  return <label className="field"><span>{label}</span>{children}{hint && <small className="muted">{hint}</small>}</label>;
}

/** A form submit wrapper: shows the server's message on failure and keeps the form open. */
export function useSubmit() {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const run = async (fn: () => Promise<unknown>) => {
    setBusy(true); setError(null);
    try { await fn(); return true; } catch (e) { setError((e as Error).message); return false; } finally { setBusy(false); }
  };
  return { busy, error, run, setError };
}

export function Upload({ label, onFile, busy }: { label: string; onFile: (f: File) => void; busy?: boolean }) {
  return (
    <label className={`button secondary${busy ? ' disabled' : ''}`}>
      {label}
      <input type="file" accept=".csv,text/csv" hidden disabled={busy}
        onChange={(e) => { const f = e.target.files?.[0]; if (f) onFile(f); e.target.value = ''; }} />
    </label>
  );
}

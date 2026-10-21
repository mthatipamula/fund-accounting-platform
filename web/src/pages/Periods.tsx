import { useState } from 'react';
import { api, post, type PeriodClose } from '../api';
import { fmtMoney, fmtTime } from '../format';
import { Field, Loading, Section, can, useLoad, useSubmit, useView } from '../ui';

export default function Periods() {
  const v = useView();
  const h = useLoad(() => api<PeriodClose[]>(`/api/accounting/funds/${v.fundId}/periods`), [v.fundId, v.refresh]);
  const [through, setThrough] = useState(v.asOf);
  const s = useSubmit();
  const admin = can(v, 'admin');
  const current = h.data?.[0]?.action === 'CLOSE' ? h.data[0] : null;

  const close = () => {
    if (!window.confirm(`Close the books through ${through}? Back-dated trades and capital activity on or before that date will be refused, and reports will show these figures until the period is reopened.`)) return;
    void s.run(async () => { await post(`/api/accounting/funds/${v.fundId}/periods/close`, { closeThrough: through }); v.bump(); });
  };
  const reopen = () => {
    if (!window.confirm('Reopen the books? Reported figures may change once corrections are entered. This is recorded in the audit history.')) return;
    void s.run(async () => { await post(`/api/accounting/funds/${v.fundId}/periods/reopen`); v.bump(); });
  };

  return (
    <>
      <Section title="Status">
        {current
          ? <p>Closed through <strong>{current.closedThrough}</strong> by {current.performedBy} on {fmtTime(current.performedAt)}, NAV {fmtMoney(current.nav)}.</p>
          : <p>No period is closed. All dates accept new and corrected entries.</p>}
        {admin ? (
          <div className="row">
            <Field label="Close through"><input type="date" value={through} onChange={(e) => setThrough(e.target.value)} /></Field>
            <button className="primary" disabled={s.busy} onClick={close}>Close period</button>
            {current && <button className="danger" disabled={s.busy} onClick={reopen}>Reopen</button>}
          </div>
        ) : <p className="small muted">Only an administrator can close or reopen periods.</p>}
        <p className="small muted">Closing is refused while there are unresolved exceptions (missing prices, oversells…).</p>
        {s.error && <div className="notice error">{s.error}</div>}
      </Section>
      <Section title="Audit history">
        {!h.data ? <Loading error={h.error} loading={h.loading} /> : (
          <table><thead><tr><th>When</th><th>Action</th><th>Through</th><th>By</th><th className="num">NAV at close</th></tr></thead>
            <tbody>{h.data.map((p) => <tr key={p.id}><td>{fmtTime(p.performedAt)}</td><td>{p.action === 'CLOSE' ? 'Closed' : 'Reopened'}</td>
              <td>{p.closedThrough ?? ''}</td><td>{p.performedBy}</td><td className="num">{p.nav != null ? fmtMoney(p.nav) : ''}</td></tr>)}
              {h.data.length === 0 && <tr><td colSpan={5} className="muted">No closes yet.</td></tr>}</tbody></table>)}
      </Section>
    </>
  );
}

import { useState } from 'react';
import { post, qs, type ReconResult } from '../api';
import { fmtMoney, fmtQty } from '../format';
import { Field, Section, Upload, useSubmit, useView } from '../ui';

const STATUS: Record<string, string> = { MATCHED: 'Matched', BREAK: 'Break', MISSING_IN_BOOKS: 'Not in our books', MISSING_IN_STATEMENT: 'Not on statement' };

export default function Reconciliation() {
  const v = useView();
  const [tolerance, setTolerance] = useState('0.01');
  const [result, setResult] = useState<ReconResult | null>(null);
  const [onlyBreaks, setOnlyBreaks] = useState(true);
  const s = useSubmit();
  const run = (f: File) => s.run(async () => {
    const fd = new FormData(); fd.append('file', f);
    setResult(await post<ReconResult>(`/api/accounting/funds/${v.fundId}/reconciliation${qs({ asOf: v.asOf, tolerance })}`, fd));
  });
  const lines = (result?.lines ?? []).filter((l) => !onlyBreaks || l.status !== 'MATCHED');
  return (
    <>
      <Section title="Reconcile against a custodian statement">
        <p className="small muted">Upload the custodian's or prime broker's holdings for {v.asOf} as CSV with columns <code>type,id,quantity</code>, one row per item: <code>POSITION,&lt;security id&gt;,&lt;units&gt;</code> or <code>CASH,&lt;currency&gt;,&lt;balance&gt;</code>.</p>
        <div className="row">
          <Field label="Tolerance"><input inputMode="decimal" value={tolerance} onChange={(e) => setTolerance(e.target.value)} /></Field>
          <Upload label="Upload statement" onFile={run} busy={s.busy} />
        </div>
        {s.error && <div className="notice error">{s.error}</div>}
      </Section>
      {result && (
        <Section title={`Result for ${result.asOf}: ${result.matched} matched, ${result.breaks} break${result.breaks === 1 ? '' : 's'}`}
          actions={<label className="inline small"><input type="checkbox" checked={onlyBreaks} onChange={(e) => setOnlyBreaks(e.target.checked)} /> Breaks only</label>}>
          <div className="table-wrap"><table>
            <thead><tr><th>Type</th><th>Item</th><th className="num">Our books</th><th className="num">Statement</th><th className="num">Difference</th><th>Status</th></tr></thead>
            <tbody>{lines.map((l) => { const fmt = l.type === 'CASH' ? fmtMoney : fmtQty; return (
              <tr key={l.type + l.id}><td>{l.type === 'CASH' ? 'Cash' : 'Position'}</td><td>{l.id}</td><td className="num">{fmt(l.books)}</td>
                <td className="num">{fmt(l.statement)}</td><td className="num">{fmt(l.difference)}</td>
                <td><span className={`tag ${l.status === 'MATCHED' ? 'ok' : 'warn'}`}>{l.status === 'MATCHED' ? '✓ ' : '⚠ '}{STATUS[l.status]}</span></td></tr>); })}
              {lines.length === 0 && <tr><td colSpan={6} className="muted">Everything matches.</td></tr>}</tbody>
          </table></div>
        </Section>)}
    </>
  );
}

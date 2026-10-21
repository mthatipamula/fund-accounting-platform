import { useState } from 'react';
import { api, qs, type AccountInfo, type Pnl, type Realized, type Report } from '../api';
import { fmtMoney, fmtQty, sign } from '../format';
import { BasisNote, Loading, Section, useLoad, useView } from '../ui';

export default function PnlPage() {
  const v = useView();
  const [from, setFrom] = useState(() => v.asOf.slice(0, 8) + '01');   // month to date
  const q = qs({ from, asOf: v.asOf, knownAt: v.knownAt });
  const r = useLoad(() => api<Report<Pnl>>(`/api/accounting/funds/${v.fundId}/pnl${q}`), [v.fundId, q, v.refresh]);
  const rz = useLoad(() => api<Report<Realized[]>>(`/api/accounting/funds/${v.fundId}/realized${qs({ asOf: v.asOf, knownAt: v.knownAt })}`), [v.fundId, v.asOf, v.knownAt, v.refresh]);
  const accounts = useLoad(() => api<AccountInfo[]>('/api/accounting/accounts'), []);
  const label = (a: string) => accounts.data?.find((x) => x.account === a)?.label ?? a;
  const realized = (rz.data?.data ?? []).filter((x) => x.date >= from);

  return (
    <>
      <div className="row"><label className="inline">From <input type="date" value={from} max={v.asOf} onChange={(e) => e.target.value && setFrom(e.target.value)} /></label>
        <span className="muted small">to {v.asOf}</span></div>
      {!r.data ? <Loading error={r.error} loading={r.loading} /> : (<>
        <BasisNote basis={r.data.basis} />
        <div className="grid2">
          <Section title="By account">
            <table><tbody>
              {Object.entries(r.data.data.byAccount).map(([a, val]) => <tr key={a}><td>{label(a)}</td><td className={`num ${sign(val)}`}>{fmtMoney(val)}</td></tr>)}
            </tbody><tfoot><tr><td>Net income</td><td className={`num ${sign(r.data.data.total)}`}>{fmtMoney(r.data.data.total)}</td></tr></tfoot></table>
          </Section>
          <Section title="By security">
            <div className="table-wrap"><table>
              <thead><tr><th>Security</th><th className="num">Realized</th><th className="num">Unrealized change</th><th className="num">Income</th><th className="num">Total</th></tr></thead>
              <tbody>{Object.values(r.data.data.bySecurity).map((s) => (
                <tr key={s.securityId}><td>{s.securityId}</td><td className={`num ${sign(s.realized)}`}>{fmtMoney(s.realized)}</td>
                  <td className={`num ${sign(s.unrealizedChange)}`}>{fmtMoney(s.unrealizedChange)}</td><td className="num">{fmtMoney(s.income)}</td>
                  <td className={`num ${sign(s.total)}`}><strong>{fmtMoney(s.total)}</strong></td></tr>))}</tbody>
            </table></div>
          </Section>
        </div>
      </>)}
      <Section title="Realized gains and losses (lot relief)">
        {!rz.data ? <Loading error={rz.error} loading={rz.loading} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Date</th><th>Security</th><th>Trade</th><th className="num">Quantity</th><th className="num">Proceeds</th><th className="num">Cost</th>
              <th className="num">Gain</th><th className="num">of which price</th><th className="num">of which FX</th></tr></thead>
            <tbody>{realized.map((x, i) => (
              <tr key={i}><td>{x.date}</td><td>{x.securityId}</td><td className="mono small">{x.tradeId}</td><td className="num">{fmtQty(x.quantity)}</td>
                <td className="num">{fmtMoney(x.proceedsBase)}</td><td className="num">{fmtMoney(x.costBase)}</td>
                <td className={`num ${sign(x.gainBase)}`}>{fmtMoney(x.gainBase)}</td><td className="num">{fmtMoney(x.priceComponentBase)}</td>
                <td className="num">{fmtMoney(x.fxComponentBase)}</td></tr>))}
              {realized.length === 0 && <tr><td colSpan={9} className="muted">No disposals in this period.</td></tr>}</tbody>
          </table></div>)}
      </Section>
    </>
  );
}

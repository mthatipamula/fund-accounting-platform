import { api, qs, type NavPoint, type Report, type Summary } from '../api';
import { fmtMoney, fmtPrice, fmtPct, fmtQty } from '../format';
import { BasisNote, Loading, Section, Stat, useLoad, useView } from '../ui';
import NavChart from './NavChart';

export default function Dashboard() {
  const v = useView();
  const q = qs({ asOf: v.asOf, knownAt: v.knownAt });
  const s = useLoad(() => api<Summary>(`/api/accounting/funds/${v.fundId}/summary${q}`), [v.fundId, q, v.refresh]);
  const h = useLoad(() => api<Report<NavPoint[]>>(`/api/accounting/funds/${v.fundId}/nav-history${q}`), [v.fundId, q, v.refresh]);
  if (!s.data) return <Loading error={s.error} loading={s.loading} />;
  const d = s.data;
  return (
    <>
      <BasisNote basis={d.basis} />
      {d.exceptions.length > 0 && (
        <div className="notice warn" role="status">
          <strong>{d.exceptions.length} exception{d.exceptions.length > 1 ? 's' : ''} need attention</strong>
          <ul>{d.exceptions.slice(0, 5).map((e, i) => <li key={i}>{e.date}: {e.message}</li>)}</ul>
        </div>)}
      <div className="stats">
        <Stat label={`Net asset value (${d.baseCurrency})`} value={fmtMoney(d.nav)} />
        <Stat label="Total assets" value={fmtMoney(d.totalAssets)} />
        <Stat label="Total liabilities" value={fmtMoney(d.totalLiabilities)} />
        <Stat label="Positions" value={d.positionCount} />
      </div>
      <Section title="Net asset value">
        {h.data ? <NavChart points={h.data.data} currency={d.baseCurrency} /> : <Loading error={h.error} loading={h.loading} />}
      </Section>
      <div className="grid2">
        <Section title="Share classes">
          <div className="table-wrap"><table>
            <thead><tr><th>Class</th><th className="num">NAV</th><th className="num">Shares</th><th className="num">NAV / share</th><th className="num">Mgmt fee</th></tr></thead>
            <tbody>{d.shareClasses.map((c) => (
              <tr key={c.shareClassId}><td>{c.name}</td><td className="num">{fmtMoney(c.nav)}</td><td className="num">{fmtQty(c.shares)}</td>
                <td className="num">{fmtPrice(c.navPerShare)}</td><td className="num">{fmtPct(c.managementFeeRate)}</td></tr>))}</tbody>
          </table></div>
        </Section>
        <Section title="Cash">
          <div className="table-wrap"><table>
            <thead><tr><th>Currency</th><th className="num">Local</th><th className="num">{d.baseCurrency}</th></tr></thead>
            <tbody>{d.cash.map((c) => <tr key={c.currency}><td>{c.currency}</td><td className="num">{fmtMoney(c.local)}</td><td className="num">{fmtMoney(c.base)}</td></tr>)}</tbody>
          </table></div>
        </Section>
      </div>
    </>
  );
}

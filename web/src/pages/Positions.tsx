import { api, qs, type Position, type Report } from '../api';
import { fmtMoney, fmtPct, fmtPrice, fmtQty, sign } from '../format';
import { BasisNote, Loading, useLoad, useView } from '../ui';

export default function Positions() {
  const v = useView();
  const q = qs({ asOf: v.asOf, knownAt: v.knownAt });
  const r = useLoad(() => api<Report<Position[]>>(`/api/accounting/funds/${v.fundId}/positions${q}`), [v.fundId, q, v.refresh]);
  if (!r.data) return <Loading error={r.error} loading={r.loading} />;
  const rows = r.data.data;
  const sum = (f: (p: Position) => unknown) => rows.reduce((a, p) => a + Number(f(p) ?? 0), 0);
  return (
    <>
      <BasisNote basis={r.data.basis} />
      <p className="small muted">Unrealized gain/loss is split into the part from the price move and the part from the currency move.</p>
      <div className="table-wrap card"><table>
        <thead><tr>
          <th>Security</th><th>Ccy</th><th className="num">Quantity</th><th className="num">Price</th><th className="num">FX</th>
          <th className="num">Cost (base)</th><th className="num">Market value (base)</th><th className="num">Accrued int.</th>
          <th className="num">Unrealized</th><th className="num">of which price</th><th className="num">of which FX</th><th className="num">Weight</th>
        </tr></thead>
        <tbody>{rows.map((p) => (
          <tr key={p.securityId}>
            <td><strong>{p.symbol}</strong> <span className="muted small">{p.name}</span></td><td>{p.currency}</td>
            <td className="num">{fmtQty(p.quantity)}</td><td className="num">{p.price === null ? <span className="tag warn">no price</span> : fmtPrice(p.price)}</td>
            <td className="num">{fmtPrice(p.fxRate)}</td><td className="num">{fmtMoney(p.costBase)}</td><td className="num">{fmtMoney(p.marketValueBase)}</td>
            <td className="num">{fmtMoney(p.accruedInterestBase)}</td>
            <td className={`num ${sign(p.unrealizedBase)}`}>{fmtMoney(p.unrealizedBase)}</td>
            <td className={`num ${sign(p.unrealizedPriceBase)}`}>{fmtMoney(p.unrealizedPriceBase)}</td>
            <td className={`num ${sign(p.unrealizedFxBase)}`}>{fmtMoney(p.unrealizedFxBase)}</td>
            <td className="num">{fmtPct(p.weight)}</td>
          </tr>))}
          {rows.length === 0 && <tr><td colSpan={12} className="muted">No open positions on this date.</td></tr>}
        </tbody>
        {rows.length > 0 && <tfoot><tr>
          <td colSpan={5}>Total</td><td className="num">{fmtMoney(sum((p) => p.costBase))}</td><td className="num">{fmtMoney(sum((p) => p.marketValueBase))}</td>
          <td className="num">{fmtMoney(sum((p) => p.accruedInterestBase))}</td><td className="num">{fmtMoney(sum((p) => p.unrealizedBase))}</td>
          <td className="num">{fmtMoney(sum((p) => p.unrealizedPriceBase))}</td><td className="num">{fmtMoney(sum((p) => p.unrealizedFxBase))}</td><td />
        </tr></tfoot>}
      </table></div>
    </>
  );
}

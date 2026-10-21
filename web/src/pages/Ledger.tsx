import { useState } from 'react';
import { api, qs, type AccountInfo, type JournalLine, type Report, type TrialBalanceRow } from '../api';
import { fmtMoney } from '../format';
import { BasisNote, Loading, Section, useLoad, useView } from '../ui';

export default function Ledger() {
  const v = useView();
  const [account, setAccount] = useState('');
  const [from, setFrom] = useState(() => v.asOf.slice(0, 8) + '01');
  const base = qs({ asOf: v.asOf, knownAt: v.knownAt });
  const accounts = useLoad(() => api<AccountInfo[]>('/api/accounting/accounts'), []);
  const tb = useLoad(() => api<Report<TrialBalanceRow[]>>(`/api/accounting/funds/${v.fundId}/trial-balance${base}`), [v.fundId, base, v.refresh]);
  const jq = qs({ asOf: v.asOf, knownAt: v.knownAt, from, account });
  const journal = useLoad(() => api<Report<JournalLine[]>>(`/api/accounting/funds/${v.fundId}/journal${jq}`), [v.fundId, jq, v.refresh]);
  const info = (a: string) => accounts.data?.find((x) => x.account === a);
  const totals = (tb.data?.data ?? []).reduce((t, r) => [t[0] + Number(r.debit), t[1] + Number(r.credit)], [0, 0]);
  const lines = journal.data?.data ?? [];
  const shown = lines.slice(-500);

  return (
    <>
      <Section title="Trial balance">
        {!tb.data ? <Loading error={tb.error} loading={tb.loading} /> : (<>
          <BasisNote basis={tb.data.basis} />
          <div className="table-wrap"><table>
            <thead><tr><th>Code</th><th>Account</th><th>Type</th><th className="num">Debit</th><th className="num">Credit</th></tr></thead>
            <tbody>{tb.data.data.map((r) => (
              <tr key={r.account}><td className="mono">{info(r.account)?.code}</td>
                <td><button className="link" onClick={() => setAccount(info(r.account)?.code ?? '')}>{info(r.account)?.label ?? r.account}</button></td>
                <td className="small muted">{info(r.account)?.type}</td><td className="num">{Number(r.debit) ? fmtMoney(r.debit) : ''}</td>
                <td className="num">{Number(r.credit) ? fmtMoney(r.credit) : ''}</td></tr>))}</tbody>
            <tfoot><tr><td colSpan={3}>Total {Math.abs(totals[0] - totals[1]) < 0.005 ? <span className="tag ok">balanced</span> : <span className="tag warn">out of balance</span>}</td>
              <td className="num">{fmtMoney(totals[0])}</td><td className="num">{fmtMoney(totals[1])}</td></tr></tfoot>
          </table></div></>)}
      </Section>
      <Section title="Journal" actions={<>
        <label className="inline small">From <input type="date" value={from} max={v.asOf} onChange={(e) => setFrom(e.target.value)} /></label>
        <select value={account} onChange={(e) => setAccount(e.target.value)} aria-label="Account">
          <option value="">All accounts</option>
          {accounts.data?.map((a) => <option key={a.code} value={a.code}>{a.code} {a.label}</option>)}
        </select></>}>
        {!journal.data ? <Loading error={journal.error} loading={journal.loading} /> : (<>
          {lines.length > shown.length && <p className="small muted">Showing the latest {shown.length} of {lines.length} lines; narrow the dates or pick an account.</p>}
          <div className="table-wrap"><table>
            <thead><tr><th>Entry</th><th>Date</th><th>Account</th><th>Security</th><th>Ccy</th><th className="num">Debit</th><th className="num">Credit</th><th>Description</th><th>Source</th></tr></thead>
            <tbody>{shown.map((l, i) => (
              <tr key={i}><td className="mono small">{l.entryId}</td><td>{l.date}</td><td>{info(l.account)?.code} {info(l.account)?.label ?? l.account}</td>
                <td>{l.securityId}</td><td>{l.currency}</td><td className="num">{Number(l.debit) ? fmtMoney(l.debit) : ''}</td>
                <td className="num">{Number(l.credit) ? fmtMoney(l.credit) : ''}</td><td className="small">{l.description}</td>
                <td className="small muted">{l.sourceType} {l.sourceId}</td></tr>))}
              {shown.length === 0 && <tr><td colSpan={9} className="muted">No entries.</td></tr>}</tbody>
          </table></div></>)}
      </Section>
    </>
  );
}

import { useState } from 'react';
import { api, post, put, qs, type CapitalRow, type Fund, type InvestorHolding, type Investor, type Report } from '../api';
import { fmtMoney, fmtQty, fmtTime, today } from '../format';
import { BasisNote, Field, Loading, Modal, Section, can, useLoad, useSubmit, useView } from '../ui';

export default function Capital() {
  const v = useView();
  const fund = useLoad(() => api<Fund>(`/api/accounting/funds/${v.fundId}`), [v.fundId]);
  const investors = useLoad(() => api<Investor[]>('/api/accounting/investors'), [v.refresh]);
  const activity = useLoad(() => api<CapitalRow[]>(`/api/accounting/capital${qs({ fundId: v.fundId, knownAt: v.knownAt })}`), [v.fundId, v.knownAt, v.refresh]);
  const holdings = useLoad(() => api<Report<InvestorHolding[]>>(`/api/accounting/funds/${v.fundId}/investors${qs({ asOf: v.asOf, knownAt: v.knownAt })}`),
    [v.fundId, v.asOf, v.knownAt, v.refresh]);
  const [editing, setEditing] = useState<CapitalRow | 'new' | null>(null);
  const [newInvestor, setNewInvestor] = useState(false);
  const writable = can(v, 'write');
  const invName = (id: string) => investors.data?.find((i) => i.id === id)?.name ?? id;
  const className = (id: string) => fund.data?.shareClasses.find((c) => c.id === id)?.name ?? id;

  return (
    <>
      <Section title="Investor holdings">
        {!holdings.data ? <Loading error={holdings.error} loading={holdings.loading} /> : (<>
          <BasisNote basis={holdings.data.basis} />
          <div className="table-wrap"><table>
            <thead><tr><th>Investor</th><th>Share class</th><th className="num">Shares</th><th className="num">Value</th></tr></thead>
            <tbody>{holdings.data.data.map((h) => (
              <tr key={h.investorId + h.shareClassId}><td>{invName(h.investorId)}</td><td>{className(h.shareClassId)}</td>
                <td className="num">{fmtQty(h.shares)}</td><td className="num">{fmtMoney(h.value)}</td></tr>))}
              {holdings.data.data.length === 0 && <tr><td colSpan={4} className="muted">No investors hold shares on this date.</td></tr>}</tbody>
          </table></div></>)}
      </Section>
      <Section title="Subscriptions and redemptions" actions={writable && <>
        <button className="secondary" onClick={() => setNewInvestor(true)}>New investor</button>
        <button className="primary" onClick={() => setEditing('new')} disabled={!fund.data}>New activity</button></>}>
        <p className="small muted">Subscriptions buy shares at the dealing date's NAV per share; redemptions pay out shares × NAV per share.</p>
        {!activity.data ? <Loading error={activity.error} loading={activity.loading} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>ID</th><th>Dealing date</th><th>Type</th><th>Investor</th><th>Class</th><th className="num">Amount</th><th className="num">Shares</th><th>Ver.</th><th>Recorded</th><th /></tr></thead>
            <tbody>{activity.data.map((c) => (
              <tr key={c.id} className={c.cancelled ? 'cancelled' : ''}><td className="mono small">{c.id}</td><td>{c.dealingDate}</td>
                <td><span className={`tag ${c.type === 'SUBSCRIPTION' ? 'buy' : 'sell'}`}>{c.type === 'SUBSCRIPTION' ? 'Subscription' : 'Redemption'}</span></td>
                <td>{invName(c.investorId)}</td><td>{className(c.shareClassId)}</td><td className="num">{fmtMoney(c.amount)}</td><td className="num">{fmtQty(c.shares)}</td>
                <td>v{c.version}</td><td className="small muted">{fmtTime(c.recordedAt)} · {c.enteredBy}</td>
                <td className="actions">{writable && !c.cancelled && <button className="link small" onClick={() => setEditing(c)}>Amend / cancel</button>}
                  {c.cancelled && <span className="tag">cancelled</span>}</td></tr>))}
              {activity.data.length === 0 && <tr><td colSpan={10} className="muted">No capital activity.</td></tr>}</tbody>
          </table></div>)}
      </Section>
      {editing && fund.data && <CapitalForm row={editing === 'new' ? null : editing} fund={fund.data} investors={investors.data ?? []}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); v.bump(); }} />}
      {newInvestor && <InvestorForm onClose={() => setNewInvestor(false)} onSaved={() => { setNewInvestor(false); v.bump(); }} />}
    </>
  );
}

function CapitalForm({ row, fund, investors, onClose, onSaved }: { row: CapitalRow | null; fund: Fund; investors: Investor[]; onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({
    type: row?.type ?? 'SUBSCRIPTION', investorId: row?.investorId ?? investors[0]?.id ?? '', shareClassId: row?.shareClassId ?? fund.shareClasses[0]?.id ?? '',
    dealingDate: row?.dealingDate ?? today(), amount: row?.amount != null ? String(row.amount) : '', shares: row?.shares != null ? String(row.shares) : '',
  });
  const s = useSubmit();
  const set = (k: keyof typeof d) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const body = { fundId: fund.id, shareClassId: d.shareClassId, investorId: d.investorId, type: d.type, dealingDate: d.dealingDate,
    amount: d.type === 'SUBSCRIPTION' ? d.amount : null, shares: d.type === 'REDEMPTION' ? d.shares : null };
  const save = () => s.run(async () => {
    if (row) await put(`/api/accounting/capital/${row.id}`, { expectedVersion: row.version, activity: body });
    else await post('/api/accounting/capital', body);
    onSaved();
  });
  const cancel = () => {
    if (!row || !window.confirm(`Cancel ${row.id}?`)) return;
    void s.run(async () => { await post(`/api/accounting/capital/${row.id}/cancel${qs({ expectedVersion: row.version })}`); onSaved(); });
  };
  return (
    <Modal title={row ? `Amend ${row.id} (v${row.version})` : 'New subscription or redemption'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <div className="form-row">
          <Field label="Type"><select value={d.type} onChange={set('type')}><option value="SUBSCRIPTION">Subscription</option><option value="REDEMPTION">Redemption</option></select></Field>
          <Field label="Dealing date"><input type="date" required value={d.dealingDate} onChange={set('dealingDate')} /></Field>
        </div>
        <div className="form-row">
          <Field label="Investor"><select value={d.investorId} onChange={set('investorId')} required>{investors.map((i) => <option key={i.id} value={i.id}>{i.name}</option>)}</select></Field>
          <Field label="Share class"><select value={d.shareClassId} onChange={set('shareClassId')}>{fund.shareClasses.map((c) => <option key={c.id} value={c.id}>{c.name}</option>)}</select></Field>
        </div>
        {d.type === 'SUBSCRIPTION'
          ? <Field label={`Amount (${fund.baseCurrency})`}><input inputMode="decimal" required value={d.amount} onChange={set('amount')} /></Field>
          : <Field label="Shares to redeem"><input inputMode="decimal" required value={d.shares} onChange={set('shares')} /></Field>}
        {s.error && <div className="notice error">{s.error}</div>}
        <div className="row end">
          {row && <button type="button" className="danger" disabled={s.busy} onClick={cancel}>Cancel activity</button>}
          <button type="button" className="secondary" onClick={onClose}>Close</button>
          <button className="primary" disabled={s.busy}>Save</button>
        </div>
      </form>
    </Modal>
  );
}

function InvestorForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({ id: '', name: '', type: 'INSTITUTION' });
  const s = useSubmit();
  return (
    <Modal title="New investor" onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void s.run(async () => { await post('/api/accounting/investors', d); onSaved(); }); }}>
        <Field label="ID" hint="Letters, digits, - and _"><input required pattern="[A-Za-z0-9_\-]{1,40}" value={d.id} onChange={(e) => setD({ ...d, id: e.target.value })} /></Field>
        <Field label="Name"><input required value={d.name} onChange={(e) => setD({ ...d, name: e.target.value })} /></Field>
        <Field label="Type"><select value={d.type} onChange={(e) => setD({ ...d, type: e.target.value })}>
          {['INSTITUTION', 'INDIVIDUAL', 'FUND_OF_FUNDS', 'EMPLOYEE'].map((t) => <option key={t}>{t}</option>)}</select></Field>
        {s.error && <div className="notice error">{s.error}</div>}
        <div className="row end"><button type="button" className="secondary" onClick={onClose}>Close</button><button className="primary" disabled={s.busy}>Create</button></div>
      </form>
    </Modal>
  );
}

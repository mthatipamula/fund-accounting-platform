import { useState } from 'react';
import { api, post, put, qs, type CorporateActionRow, type FxRow, type PriceRow, type Security } from '../api';
import { fmtPrice, fmtTime, today } from '../format';
import { Field, Loading, Modal, Section, Upload, can, useLoad, useSubmit, useView } from '../ui';

const daysAgo = (n: number) => new Date(Date.now() - n * 864e5).toISOString().slice(0, 10);

export default function ReferenceData() {
  const v = useView();
  const writable = can(v, 'write');
  const secs = useLoad(() => api<Security[]>('/api/reference/securities'), [v.refresh]);
  const [from, setFrom] = useState(daysAgo(14));
  const pq = qs({ from, to: v.asOf, knownAt: v.knownAt });
  const prices = useLoad(() => api<PriceRow[]>(`/api/reference/prices${pq}`), [pq, v.refresh]);
  const fx = useLoad(() => api<FxRow[]>(`/api/reference/fx${pq}`), [pq, v.refresh]);
  const cas = useLoad(() => api<CorporateActionRow[]>('/api/reference/corporate-actions'), [v.refresh]);
  const [modal, setModal] = useState<'security' | 'price' | 'fx' | 'ca' | null>(null);
  const [history, setHistory] = useState<PriceRow | null>(null);
  const upload = useSubmit();
  const [msg, setMsg] = useState<string | null>(null);
  const doUpload = (path: string) => (f: File) => upload.run(async () => {
    const fd = new FormData(); fd.append('file', f);
    const r = await post<{ loaded: number; corrected: number }>(path, fd);
    setMsg(`Loaded ${r.loaded} rows (${r.corrected} corrected an earlier value).`); v.bump();
  });
  const close = () => { setModal(null); v.bump(); };

  return (
    <>
      {upload.error && <div className="notice error">{upload.error}</div>}
      {msg && <div className="notice ok">{msg}</div>}
      <Section title="Securities" actions={writable && <button className="primary" onClick={() => setModal('security')}>New security</button>}>
        {!secs.data ? <Loading error={secs.error} loading={secs.loading} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>ID</th><th>Symbol</th><th>Name</th><th>Class</th><th>Ccy</th><th className="num">Coupon</th><th>Maturity</th><th>Day count</th></tr></thead>
            <tbody>{secs.data.map((s) => (
              <tr key={s.id}><td className="mono small">{s.id}</td><td><strong>{s.symbol}</strong></td><td>{s.name}</td><td>{s.assetClass}</td><td>{s.currency}</td>
                <td className="num">{s.couponRate != null ? `${(Number(s.couponRate) * 100).toFixed(3)}%` : ''}</td><td>{s.maturityDate}</td><td className="small">{s.dayCount}</td></tr>))}</tbody>
          </table></div>)}
      </Section>
      <div className="grid2">
        <Section title="Prices" actions={<>
          <label className="inline small">From <input type="date" value={from} onChange={(e) => setFrom(e.target.value)} /></label>
          {writable && <Upload label="Upload CSV" onFile={doUpload('/api/reference/prices/upload')} busy={upload.busy} />}
          {writable && <button className="secondary" onClick={() => setModal('price')}>Enter price</button>}</>}>
          {writable && <p className="small muted">CSV: security_id, date, price[, source]. Re-sending a date corrects it; the old value is kept in its history.</p>}
          {!prices.data ? <Loading error={prices.error} loading={prices.loading} /> : (
            <div className="table-wrap tall"><table>
              <thead><tr><th>Date</th><th>Security</th><th className="num">Price</th><th>Source</th><th /></tr></thead>
              <tbody>{[...prices.data].reverse().map((p) => (
                <tr key={p.securityId + p.date}><td>{p.date}</td><td>{p.securityId}</td><td className="num">{fmtPrice(p.price)}</td><td className="small muted">{p.source}</td>
                  <td><button className="link small" onClick={() => setHistory(p)}>history</button></td></tr>))}</tbody>
            </table></div>)}
        </Section>
        <Section title="FX rates (to base)" actions={<>
          {writable && <Upload label="Upload CSV" onFile={doUpload('/api/reference/fx/upload')} busy={upload.busy} />}
          {writable && <button className="secondary" onClick={() => setModal('fx')}>Enter rate</button>}</>}>
          {writable && <p className="small muted">CSV: currency, base_currency, date, rate (units of base per 1 unit of currency).</p>}
          {!fx.data ? <Loading error={fx.error} loading={fx.loading} /> : (
            <div className="table-wrap tall"><table>
              <thead><tr><th>Date</th><th>Pair</th><th className="num">Rate</th><th>Recorded</th></tr></thead>
              <tbody>{[...fx.data].reverse().map((r) => (
                <tr key={r.currency + r.date}><td>{r.date}</td><td>{r.currency}/{r.baseCurrency}</td><td className="num">{fmtPrice(r.rate)}</td><td className="small muted">{fmtTime(r.recordedAt)}</td></tr>))}</tbody>
            </table></div>)}
        </Section>
      </div>
      <Section title="Corporate actions" actions={writable && <button className="primary" onClick={() => setModal('ca')}>New corporate action</button>}>
        {!cas.data ? <Loading error={cas.error} loading={cas.loading} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>ID</th><th>Security</th><th>Type</th><th>Ex date</th><th>Pay date</th><th className="num">Per share</th><th className="num">Ratio</th><th>Ver.</th><th /></tr></thead>
            <tbody>{cas.data.map((c) => (
              <tr key={c.id} className={c.cancelled ? 'cancelled' : ''}><td className="mono small">{c.id}</td><td>{c.securityId}</td><td>{c.type === 'SPLIT' ? 'Split' : 'Cash dividend'}</td>
                <td>{c.exDate}</td><td>{c.payDate}</td><td className="num">{fmtPrice(c.amountPerShare)}</td><td className="num">{c.ratio ?? ''}</td><td>v{c.version}</td>
                <td>{writable && !c.cancelled && <button className="link small" onClick={() => {
                  if (window.confirm(`Cancel corporate action ${c.id}?`)) void post(`/api/reference/corporate-actions/${c.id}/cancel`).then(v.bump, (e) => setMsg((e as Error).message));
                }}>Cancel</button>}{c.cancelled && <span className="tag">cancelled</span>}</td></tr>))}
              {cas.data.length === 0 && <tr><td colSpan={9} className="muted">None.</td></tr>}</tbody>
          </table></div>)}
      </Section>
      {modal === 'security' && <SecurityForm onClose={() => setModal(null)} onSaved={close} />}
      {modal === 'price' && <PriceForm securities={secs.data ?? []} onClose={() => setModal(null)} onSaved={close} />}
      {modal === 'fx' && <FxForm onClose={() => setModal(null)} onSaved={close} />}
      {modal === 'ca' && <CaForm securities={secs.data ?? []} onClose={() => setModal(null)} onSaved={close} />}
      {history && <PriceHistory row={history} onClose={() => setHistory(null)} />}
    </>
  );
}

function FormShell({ title, onClose, onSubmit, s, children }: { title: string; onClose: () => void; onSubmit: () => void; s: ReturnType<typeof useSubmit>; children: React.ReactNode }) {
  return (
    <Modal title={title} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); onSubmit(); }}>
        {children}
        {s.error && <div className="notice error">{s.error}</div>}
        <div className="row end"><button type="button" className="secondary" onClick={onClose}>Close</button><button className="primary" disabled={s.busy}>Save</button></div>
      </form>
    </Modal>
  );
}

function SecurityForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({ id: '', symbol: '', name: '', assetClass: 'EQUITY', currency: 'USD', couponRate: '', couponsPerYear: '2', issueDate: '', maturityDate: '', dayCount: 'THIRTY_360' });
  const s = useSubmit();
  const set = (k: keyof typeof d) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const bond = d.assetClass === 'BOND';
  const submit = () => s.run(async () => {
    await post('/api/reference/securities', bond ? { ...d, couponRate: d.couponRate, couponsPerYear: Number(d.couponsPerYear) }
      : { id: d.id, symbol: d.symbol, name: d.name, assetClass: d.assetClass, currency: d.currency });
    onSaved();
  });
  return (
    <FormShell title="New security" onClose={onClose} onSubmit={submit} s={s}>
      <div className="form-row">
        <Field label="ID"><input required pattern="[A-Za-z0-9._\-]+" value={d.id} onChange={set('id')} /></Field>
        <Field label="Symbol"><input required value={d.symbol} onChange={set('symbol')} /></Field>
      </div>
      <Field label="Name"><input required value={d.name} onChange={set('name')} /></Field>
      <div className="form-row">
        <Field label="Asset class"><select value={d.assetClass} onChange={set('assetClass')}><option>EQUITY</option><option>BOND</option></select></Field>
        <Field label="Currency"><input required pattern="[A-Z]{3}" value={d.currency} onChange={set('currency')} /></Field>
      </div>
      {bond && (<>
        <div className="form-row">
          <Field label="Coupon rate" hint="0.05 = 5%"><input required inputMode="decimal" value={d.couponRate} onChange={set('couponRate')} /></Field>
          <Field label="Coupons / year"><select value={d.couponsPerYear} onChange={set('couponsPerYear')}>{['1', '2', '4', '12'].map((x) => <option key={x}>{x}</option>)}</select></Field>
          <Field label="Day count"><select value={d.dayCount} onChange={set('dayCount')}><option value="THIRTY_360">30/360</option><option value="ACT_365">ACT/365</option></select></Field>
        </div>
        <div className="form-row">
          <Field label="Issue date"><input type="date" required value={d.issueDate} onChange={set('issueDate')} /></Field>
          <Field label="Maturity date"><input type="date" required value={d.maturityDate} onChange={set('maturityDate')} /></Field>
        </div></>)}
    </FormShell>
  );
}

function PriceForm({ securities, onClose, onSaved }: { securities: Security[]; onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({ securityId: securities[0]?.id ?? '', date: today(), price: '' });
  const s = useSubmit();
  return (
    <FormShell title="Enter or correct a price" onClose={onClose} s={s} onSubmit={() => s.run(async () => { await put('/api/reference/prices', [{ ...d, source: 'MANUAL' }]); onSaved(); })}>
      <Field label="Security"><select value={d.securityId} onChange={(e) => setD({ ...d, securityId: e.target.value })}>{securities.map((x) => <option key={x.id} value={x.id}>{x.symbol}</option>)}</select></Field>
      <div className="form-row">
        <Field label="Date"><input type="date" required value={d.date} onChange={(e) => setD({ ...d, date: e.target.value })} /></Field>
        <Field label="Price"><input required inputMode="decimal" value={d.price} onChange={(e) => setD({ ...d, price: e.target.value })} /></Field>
      </div>
    </FormShell>
  );
}

function FxForm({ onClose, onSaved }: { onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({ currency: 'EUR', baseCurrency: 'USD', date: today(), rate: '' });
  const s = useSubmit();
  const set = (k: keyof typeof d) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  return (
    <FormShell title="Enter or correct an FX rate" onClose={onClose} s={s} onSubmit={() => s.run(async () => { await put('/api/reference/fx', [d]); onSaved(); })}>
      <div className="form-row">
        <Field label="Currency"><input required pattern="[A-Z]{3}" value={d.currency} onChange={set('currency')} /></Field>
        <Field label="Base"><input required pattern="[A-Z]{3}" value={d.baseCurrency} onChange={set('baseCurrency')} /></Field>
      </div>
      <div className="form-row">
        <Field label="Date"><input type="date" required value={d.date} onChange={set('date')} /></Field>
        <Field label="Rate" hint="Base units per 1 unit of currency"><input required inputMode="decimal" value={d.rate} onChange={set('rate')} /></Field>
      </div>
    </FormShell>
  );
}

function CaForm({ securities, onClose, onSaved }: { securities: Security[]; onClose: () => void; onSaved: () => void }) {
  const [d, setD] = useState({ securityId: securities.find((x) => x.assetClass === 'EQUITY')?.id ?? '', type: 'CASH_DIVIDEND', exDate: today(), payDate: today(), amountPerShare: '', ratio: '' });
  const s = useSubmit();
  const set = (k: keyof typeof d) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const div = d.type === 'CASH_DIVIDEND';
  return (
    <FormShell title="New corporate action" onClose={onClose} s={s} onSubmit={() => s.run(async () => {
      await post('/api/reference/corporate-actions', { ...d, amountPerShare: div ? d.amountPerShare : null, ratio: div ? null : d.ratio }); onSaved();
    })}>
      <div className="form-row">
        <Field label="Security"><select value={d.securityId} onChange={set('securityId')}>{securities.filter((x) => x.assetClass === 'EQUITY').map((x) => <option key={x.id} value={x.id}>{x.symbol}</option>)}</select></Field>
        <Field label="Type"><select value={d.type} onChange={set('type')}><option value="CASH_DIVIDEND">Cash dividend</option><option value="SPLIT">Split</option></select></Field>
      </div>
      <div className="form-row">
        <Field label="Ex date"><input type="date" required value={d.exDate} onChange={set('exDate')} /></Field>
        <Field label="Pay date"><input type="date" required value={d.payDate} onChange={set('payDate')} /></Field>
      </div>
      {div ? <Field label="Amount per share"><input required inputMode="decimal" value={d.amountPerShare} onChange={set('amountPerShare')} /></Field>
        : <Field label="Ratio" hint="2 = 2-for-1"><input required inputMode="decimal" value={d.ratio} onChange={set('ratio')} /></Field>}
    </FormShell>
  );
}

function PriceHistory({ row, onClose }: { row: PriceRow; onClose: () => void }) {
  const h = useLoad(() => api<PriceRow[]>(`/api/reference/prices/${encodeURIComponent(row.securityId)}/${row.date}/history`), [row]);
  return (
    <Modal title={`${row.securityId} price on ${row.date}: every value recorded`} onClose={onClose}>
      {!h.data ? <Loading error={h.error} loading={h.loading} /> : (
        <table><thead><tr><th className="num">Price</th><th>Source</th><th>Recorded</th><th>Replaced</th></tr></thead>
          <tbody>{h.data.map((p, i) => <tr key={i}><td className="num">{fmtPrice(p.price)}</td><td>{p.source}</td><td className="small">{fmtTime(p.recordedAt)}</td><td className="small">{fmtTime(p.supersededAt)}</td></tr>)}</tbody></table>)}
    </Modal>
  );
}

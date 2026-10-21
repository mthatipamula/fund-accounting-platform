import { useState } from 'react';
import { api, post, put, qs, type Security, type TradeVersion } from '../api';
import { fmtMoney, fmtPrice, fmtQty, fmtTime, today } from '../format';
import { Field, Loading, Modal, Section, Upload, can, useLoad, useSubmit, useView } from '../ui';

type Draft = { securityId: string; side: 'BUY' | 'SELL'; quantity: string; price: string; commission: string; tradeDate: string; settleDate: string; reason: string };

export default function Trades() {
  const v = useView();
  const q = qs({ fundId: v.fundId, knownAt: v.knownAt });
  const list = useLoad(() => api<TradeVersion[]>(`/api/trades${q}`), [q, v.refresh]);
  const secs = useLoad(() => api<Security[]>('/api/reference/securities'), [v.refresh]);
  const [editing, setEditing] = useState<TradeVersion | 'new' | null>(null);
  const [history, setHistory] = useState<string | null>(null);
  const [filter, setFilter] = useState('');
  const [showCancelled, setShowCancelled] = useState(false);
  const upload = useSubmit();
  const [uploaded, setUploaded] = useState<string | null>(null);
  const writable = can(v, 'write');
  const bonds = new Set((secs.data ?? []).filter((x) => x.assetClass === 'BOND').map((x) => x.id));  // priced per 100 of face

  const rows = (list.data ?? [])
    .filter((t) => showCancelled || !t.cancelled)
    .filter((t) => !filter || `${t.id} ${t.securityId} ${t.side}`.toLowerCase().includes(filter.toLowerCase()))
    .sort((a, b) => b.tradeDate.localeCompare(a.tradeDate) || b.id.localeCompare(a.id));

  const onUpload = (f: File) => upload.run(async () => {
    const fd = new FormData(); fd.append('file', f);
    const r = await post<TradeVersion[]>('/api/trades/upload', fd);
    setUploaded(`Loaded ${r.length} trades.`); v.bump();
  });

  return (
    <>
      <p className="small muted">Every change is a new version; nothing is overwritten. Amend and cancel need a reason and are refused if
        someone else changed the trade first, or if the trade date is in a closed period.</p>
      <Section title="Trade blotter" actions={<>
        <input placeholder="Filter" value={filter} onChange={(e) => setFilter(e.target.value)} aria-label="Filter trades" />
        <label className="inline small"><input type="checkbox" checked={showCancelled} onChange={(e) => setShowCancelled(e.target.checked)} /> Show cancelled</label>
        {writable && <Upload label="Upload CSV" onFile={onUpload} busy={upload.busy} />}
        {writable && <button className="primary" onClick={() => setEditing('new')}>New trade</button>}
      </>}>
        {upload.error && <div className="notice error">{upload.error}</div>}
        {uploaded && <div className="notice ok">{uploaded}</div>}
        {writable && <p className="small muted">CSV columns: fund_id, security_id, side, quantity, price, commission, trade_date[, settle_date]. All rows load or none do.</p>}
        {!list.data ? <Loading error={list.error} loading={list.loading} /> : (
          <div className="table-wrap"><table>
            <thead><tr><th>Trade</th><th>Trade date</th><th>Settles</th><th>Side</th><th>Security</th><th className="num">Quantity</th>
              <th className="num">Price</th><th className="num">Commission</th><th className="num">Gross</th><th>Ver.</th><th>By</th><th /></tr></thead>
            <tbody>{rows.map((t) => (
              <tr key={t.id} className={t.cancelled ? 'cancelled' : ''}>
                <td className="mono small">{t.id}</td><td>{t.tradeDate}</td><td>{t.settleDate}</td>
                <td><span className={`tag ${t.side === 'BUY' ? 'buy' : 'sell'}`}>{t.side}</span></td><td>{t.securityId}</td>
                <td className="num">{fmtQty(t.quantity)}</td><td className="num">{fmtPrice(t.price)}</td><td className="num">{fmtMoney(t.commission)}</td>
                <td className="num">{fmtMoney(Number(t.quantity) * Number(t.price) / (bonds.has(t.securityId) ? 100 : 1))}</td>
                <td><button className="link small" onClick={() => setHistory(t.id)}>v{t.version}</button></td>
                <td className="small muted">{t.enteredBy}</td>
                <td className="actions">{writable && !t.cancelled && <button className="link small" onClick={() => setEditing(t)}>Amend / cancel</button>}
                  {t.cancelled && <span className="tag">cancelled</span>}</td>
              </tr>))}
              {rows.length === 0 && <tr><td colSpan={12} className="muted">No trades.</td></tr>}</tbody>
          </table></div>)}
      </Section>
      {editing && <TradeForm trade={editing === 'new' ? null : editing} securities={secs.data ?? []}
        onClose={() => setEditing(null)} onSaved={() => { setEditing(null); v.bump(); }} />}
      {history && <History id={history} onClose={() => setHistory(null)} />}
    </>
  );
}

function TradeForm({ trade, securities, onClose, onSaved }: { trade: TradeVersion | null; securities: Security[]; onClose: () => void; onSaved: () => void }) {
  const v = useView();
  const [d, setD] = useState<Draft>(() => trade ? {
    securityId: trade.securityId, side: trade.side, quantity: String(trade.quantity), price: String(trade.price),
    commission: String(trade.commission), tradeDate: trade.tradeDate, settleDate: trade.settleDate, reason: '',
  } : { securityId: securities[0]?.id ?? '', side: 'BUY', quantity: '', price: '', commission: '0', tradeDate: today(), settleDate: '', reason: '' });
  const [key] = useState(() => crypto.randomUUID());   // one key per form: a double-click can't book two trades
  const s = useSubmit();
  const set = (k: keyof Draft) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const body = () => ({ fundId: v.fundId, securityId: d.securityId, side: d.side, quantity: d.quantity, price: d.price,
    commission: d.commission || '0', tradeDate: d.tradeDate, settleDate: d.settleDate || null, reason: d.reason || null });

  const save = () => s.run(async () => {
    if (trade) await put(`/api/trades/${trade.id}`, { expectedVersion: trade.version, reason: d.reason, trade: body() });
    else await post('/api/trades', body(), { 'Idempotency-Key': key });
    onSaved();
  });
  const cancel = () => {
    if (!trade || !window.confirm(`Cancel trade ${trade.id}? It stays in the history but no longer affects the books.`)) return;
    void s.run(async () => { await post(`/api/trades/${trade.id}/cancel`, { expectedVersion: trade.version, reason: d.reason }); onSaved(); });
  };
  const needsReason = !!trade && !d.reason.trim();

  return (
    <Modal title={trade ? `Amend trade ${trade.id} (v${trade.version})` : 'New trade'} onClose={onClose}>
      <form className="form" onSubmit={(e) => { e.preventDefault(); void save(); }}>
        <Field label="Security">
          <select value={d.securityId} onChange={set('securityId')} required>
            {securities.map((x) => <option key={x.id} value={x.id}>{x.symbol}: {x.name} ({x.currency})</option>)}
          </select></Field>
        <div className="form-row">
          <Field label="Side"><select value={d.side} onChange={set('side')}><option>BUY</option><option>SELL</option></select></Field>
          <Field label="Quantity"><input inputMode="decimal" required value={d.quantity} onChange={set('quantity')} /></Field>
          <Field label="Price" hint="Bonds: per 100 of face"><input inputMode="decimal" required value={d.price} onChange={set('price')} /></Field>
          <Field label="Commission"><input inputMode="decimal" value={d.commission} onChange={set('commission')} /></Field>
        </div>
        <div className="form-row">
          <Field label="Trade date"><input type="date" required value={d.tradeDate} onChange={set('tradeDate')} /></Field>
          <Field label="Settle date" hint="Blank: T+2 business days"><input type="date" value={d.settleDate} onChange={set('settleDate')} /></Field>
        </div>
        <Field label={trade ? 'Reason for change (required)' : 'Note'}><input value={d.reason} onChange={set('reason')} maxLength={500} /></Field>
        {s.error && <div className="notice error">{s.error}</div>}
        <div className="row end">
          {trade && <button type="button" className="danger" disabled={s.busy || needsReason} onClick={cancel}>Cancel trade</button>}
          <button type="button" className="secondary" onClick={onClose}>Close</button>
          <button className="primary" disabled={s.busy || needsReason}>{trade ? 'Save amendment' : 'Book trade'}</button>
        </div>
      </form>
    </Modal>
  );
}

function History({ id, onClose }: { id: string; onClose: () => void }) {
  const h = useLoad(() => api<TradeVersion[]>(`/api/trades/${id}/versions`), [id]);
  return (
    <Modal title={`History of ${id}`} onClose={onClose}>
      {!h.data ? <Loading error={h.error} loading={h.loading} /> : (
        <div className="table-wrap"><table>
          <thead><tr><th>Ver.</th><th>Recorded</th><th>Replaced</th><th>Side</th><th className="num">Qty</th><th className="num">Price</th><th>Trade date</th><th>By</th><th>Reason</th></tr></thead>
          <tbody>{h.data.map((t) => (
            <tr key={t.version} className={t.cancelled ? 'cancelled' : ''}><td>v{t.version}{t.cancelled && ' (cancel)'}</td><td className="small">{fmtTime(t.recordedAt)}</td>
              <td className="small">{fmtTime(t.supersededAt)}</td><td>{t.side}</td><td className="num">{fmtQty(t.quantity)}</td><td className="num">{fmtPrice(t.price)}</td>
              <td>{t.tradeDate}</td><td className="small">{t.enteredBy}</td><td className="small">{t.reason}</td></tr>))}</tbody>
        </table></div>)}
    </Modal>
  );
}

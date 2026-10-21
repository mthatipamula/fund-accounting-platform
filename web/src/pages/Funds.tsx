import { useState } from 'react';
import { api, post, type Fund } from '../api';
import { fmtPct, fmtPrice } from '../format';
import { Field, Loading, Section, can, useLoad, useSubmit, useView } from '../ui';

export default function Funds() {
  const v = useView();
  const fund = useLoad(() => (v.fundId ? api<Fund>(`/api/accounting/funds/${v.fundId}`) : Promise.resolve(null)), [v.fundId, v.refresh]);
  return (
    <>
      {v.fundId && <Section title="Selected fund">
        {!fund.data ? <Loading error={fund.error} loading={fund.loading} /> : (<>
          <p><strong>{fund.data.name}</strong> · base {fund.data.baseCurrency} · {fund.data.costMethod === 'FIFO' ? 'FIFO lots' : 'average cost'} · incepted {fund.data.inceptionDate}</p>
          <table><thead><tr><th>Share class</th><th className="num">Management fee</th><th className="num">Launch NAV / share</th></tr></thead>
            <tbody>{fund.data.shareClasses.map((c) => <tr key={c.id}><td>{c.name}</td><td className="num">{fmtPct(c.managementFeeRate)}</td><td className="num">{fmtPrice(c.initialNavPerShare)}</td></tr>)}</tbody></table>
        </>)}
      </Section>}
      {can(v, 'write') && <NewFund />}
    </>
  );
}

function NewFund() {
  const v = useView();
  const [d, setD] = useState({ id: '', name: '', baseCurrency: 'USD', costMethod: 'FIFO', inceptionDate: new Date().toISOString().slice(0, 10) });
  const [classes, setClasses] = useState([{ id: '', name: 'Class A', managementFeeRate: '0.015', initialNavPerShare: '100' }]);
  const [done, setDone] = useState<string | null>(null);
  const s = useSubmit();
  const set = (k: keyof typeof d) => (e: { target: { value: string } }) => setD({ ...d, [k]: e.target.value });
  const submit = () => s.run(async () => {
    await post('/api/accounting/funds', { ...d, shareClasses: classes.map((c, i) => ({ ...c, id: c.id || `${d.id}-${String.fromCharCode(65 + i)}` })) });
    setDone(`Created ${d.name}. Pick it in the Fund menu above.`); v.bump();
  });
  return (
    <Section title="New fund">
      <form className="form" onSubmit={(e) => { e.preventDefault(); void submit(); }}>
        <div className="form-row">
          <Field label="Fund ID"><input required pattern="[A-Za-z0-9_\-]{1,40}" value={d.id} onChange={set('id')} /></Field>
          <Field label="Name"><input required value={d.name} onChange={set('name')} /></Field>
        </div>
        <div className="form-row">
          <Field label="Base currency"><input required pattern="[A-Z]{3}" value={d.baseCurrency} onChange={set('baseCurrency')} /></Field>
          <Field label="Lot relief"><select value={d.costMethod} onChange={set('costMethod')}><option value="FIFO">FIFO</option><option value="AVERAGE">Average cost</option></select></Field>
          <Field label="Inception date"><input type="date" required value={d.inceptionDate} onChange={set('inceptionDate')} /></Field>
        </div>
        <h3>Share classes</h3>
        {classes.map((c, i) => (
          <div className="form-row" key={i}>
            <Field label="Name"><input required value={c.name} onChange={(e) => setClasses(classes.map((x, j) => j === i ? { ...x, name: e.target.value } : x))} /></Field>
            <Field label="Mgmt fee (annual)" hint="0.015 = 1.5%"><input required inputMode="decimal" value={c.managementFeeRate} onChange={(e) => setClasses(classes.map((x, j) => j === i ? { ...x, managementFeeRate: e.target.value } : x))} /></Field>
            <Field label="Launch NAV / share"><input required inputMode="decimal" value={c.initialNavPerShare} onChange={(e) => setClasses(classes.map((x, j) => j === i ? { ...x, initialNavPerShare: e.target.value } : x))} /></Field>
            {classes.length > 1 && <button type="button" className="link small" onClick={() => setClasses(classes.filter((_, j) => j !== i))}>Remove</button>}
          </div>))}
        <button type="button" className="link small" onClick={() => setClasses([...classes, { id: '', name: `Class ${String.fromCharCode(65 + classes.length)}`, managementFeeRate: '0.01', initialNavPerShare: '100' }])}>+ Add share class</button>
        {s.error && <div className="notice error">{s.error}</div>}
        {done && <div className="notice ok">{done}</div>}
        <div className="row end"><button className="primary" disabled={s.busy}>Create fund</button></div>
      </form>
    </Section>
  );
}

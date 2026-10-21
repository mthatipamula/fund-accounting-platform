#!/usr/bin/env node
// Loads a demo fund through the public API (so every rule is exercised): securities, three months of prices and
// FX, corporate actions, a two-class fund with investors, trades, and a closed August.
// Usage: node scripts/seed.mjs   (reads SEEDER_CLIENT_SECRET from .env; needs the stack running)
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('..', import.meta.url));
const env = Object.fromEntries(readFileSync(`${root}.env`, 'utf8').split('\n').filter((l) => l.includes('=') && !l.startsWith('#'))
  .map((l) => [l.slice(0, l.indexOf('=')), l.slice(l.indexOf('=') + 1).trim()]));
const API = process.env.API_URL ?? 'http://localhost:8080';
const ISSUER = process.env.OIDC_ISSUER ?? 'http://localhost:8180/realms/fund-accounting';
const FUND = 'GLOBAL-EQ';

let token;
async function signIn() {
  const res = await fetch(`${ISSUER}/protocol/openid-connect/token`, {
    method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials', client_id: 'fa-seeder', client_secret: env.SEEDER_CLIENT_SECRET }),
  });
  if (!res.ok) throw new Error(`Sign-in failed: ${res.status} ${await res.text()}`);
  token = (await res.json()).access_token;
}
async function call(method, path, body, headers = {}) {
  const res = await fetch(API + path, {
    method, headers: { Authorization: `Bearer ${token}`, ...(body ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} → ${res.status}: ${text}`);
  return text ? JSON.parse(text) : null;
}

// Deterministic random walk so every run produces the same demo.
let state = 20260701;
const rand = () => { state = (state * 1664525 + 1013904223) % 4294967296; return state / 4294967296; };
const gauss = () => Math.sqrt(-2 * Math.log(rand() + 1e-12)) * Math.cos(2 * Math.PI * rand());
const iso = (d) => d.toISOString().slice(0, 10);
const businessDays = (from, to) => {
  const out = [];
  for (let d = new Date(`${from}T00:00:00Z`); iso(d) <= to; d.setUTCDate(d.getUTCDate() + 1)) if (d.getUTCDay() % 6) out.push(iso(d));
  return out;
};
const yesterday = iso(new Date(Date.now() - 864e5));
const days = businessDays('2026-06-26', yesterday);

const SECURITIES = [
  { id: 'NRTH', symbol: 'NRTH', name: 'Northwind Industries (demo)', assetClass: 'EQUITY', currency: 'USD', start: 150, vol: 0.015, drift: 0.0006 },
  { id: 'CNTS', symbol: 'CNTS', name: 'Contoso Systems (demo)', assetClass: 'EQUITY', currency: 'USD', start: 320, vol: 0.02, drift: 0.0009 },
  { id: 'FABK', symbol: 'FABK', name: 'Fabrikam Energie SE (demo)', assetClass: 'EQUITY', currency: 'EUR', start: 45, vol: 0.012, drift: 0.0002 },
  { id: 'GOVT29', symbol: 'GOVT 4.25 11/29', name: 'Government Note 4.25% 2029 (demo)', assetClass: 'BOND', currency: 'USD', start: 99.2, vol: 0.002, drift: 0.00005,
    couponRate: '0.0425', couponsPerYear: 2, issueDate: '2025-11-15', maturityDate: '2029-11-15', dayCount: 'THIRTY_360' },
];
const SPLIT = { securityId: 'CNTS', exDate: '2026-09-15', ratio: 2 };

const prices = {};   // id -> date -> price
for (const s of SECURITIES) {
  let p = s.start;
  prices[s.id] = {};
  for (const d of days) {
    p *= 1 + s.drift + s.vol * gauss();
    const shown = s.id === SPLIT.securityId && d >= SPLIT.exDate ? p / SPLIT.ratio : p;
    prices[s.id][d] = shown.toFixed(s.assetClass === 'BOND' ? 4 : 2);
  }
}
let fx = 1.09;
const eur = Object.fromEntries(days.map((d) => { fx *= 1 + 0.004 * gauss(); return [d, fx.toFixed(5)]; }));
const px = (id, d) => prices[id][d] ?? (() => { throw new Error(`No price for ${id} on ${d}`); })();

await signIn();
const funds = await call('GET', '/api/accounting/funds');
if (funds.some((f) => f.id === FUND)) { console.log(`${FUND} already exists; nothing to do.`); process.exit(0); }

console.log('Reference data…');
const existing = new Set((await call('GET', '/api/reference/securities')).map((s) => s.id));
for (const { start, vol, drift, ...s } of SECURITIES) if (!existing.has(s.id)) await call('POST', '/api/reference/securities', s);
await call('PUT', '/api/reference/prices', SECURITIES.flatMap((s) => days.map((d) => ({ securityId: s.id, date: d, price: prices[s.id][d], source: 'DEMO' }))));
await call('PUT', '/api/reference/fx', days.map((d) => ({ currency: 'EUR', baseCurrency: 'USD', date: d, rate: eur[d] })));
await call('POST', '/api/reference/corporate-actions', { securityId: 'NRTH', type: 'CASH_DIVIDEND', exDate: '2026-08-14', payDate: '2026-08-28', amountPerShare: '1.20' });
await call('POST', '/api/reference/corporate-actions', { securityId: SPLIT.securityId, type: 'SPLIT', exDate: SPLIT.exDate, payDate: SPLIT.exDate, ratio: String(SPLIT.ratio) });

console.log('Fund, investors, capital…');
await call('POST', '/api/accounting/funds', {
  id: FUND, name: 'Global Equity & Income Fund (demo)', baseCurrency: 'USD', costMethod: 'FIFO', inceptionDate: '2026-07-01',
  shareClasses: [
    { id: `${FUND}-A`, name: 'Class A', managementFeeRate: '0.015', initialNavPerShare: '100' },
    { id: `${FUND}-I`, name: 'Class I', managementFeeRate: '0.0075', initialNavPerShare: '1000' },
  ],
});
const investors = [
  { id: 'INV-PENSION', name: 'Lakeside Teachers Pension (demo)', type: 'INSTITUTION' },
  { id: 'INV-ENDOW', name: 'Hillcrest University Endowment (demo)', type: 'INSTITUTION' },
  { id: 'INV-FAMILY', name: 'Rivera Family Office (demo)', type: 'FUND_OF_FUNDS' },
];
const knownInvestors = new Set((await call('GET', '/api/accounting/investors')).map((i) => i.id));
for (const i of investors) if (!knownInvestors.has(i.id)) await call('POST', '/api/accounting/investors', i);
const capital = [
  { investorId: 'INV-PENSION', shareClassId: `${FUND}-I`, type: 'SUBSCRIPTION', dealingDate: '2026-07-01', amount: '10000000' },
  { investorId: 'INV-ENDOW', shareClassId: `${FUND}-A`, type: 'SUBSCRIPTION', dealingDate: '2026-07-01', amount: '5000000' },
  { investorId: 'INV-FAMILY', shareClassId: `${FUND}-A`, type: 'SUBSCRIPTION', dealingDate: '2026-07-01', amount: '2000000' },
  { investorId: 'INV-PENSION', shareClassId: `${FUND}-I`, type: 'SUBSCRIPTION', dealingDate: '2026-08-03', amount: '3000000' },
  { investorId: 'INV-FAMILY', shareClassId: `${FUND}-A`, type: 'REDEMPTION', dealingDate: '2026-09-30', shares: '5000' },
];
for (const c of capital) await call('POST', '/api/accounting/capital', { fundId: FUND, ...c });

console.log('Trades…');
const trades = [
  ['2026-07-02', 'NRTH', 'BUY', 20000], ['2026-07-02', 'CNTS', 'BUY', 12000], ['2026-07-02', 'FABK', 'BUY', 40000],
  ['2026-07-02', 'GOVT29', 'BUY', 3000000], ['2026-08-05', 'NRTH', 'SELL', 5000], ['2026-09-02', 'FABK', 'BUY', 10000],
  ['2026-09-22', 'CNTS', 'SELL', 6000], ['2026-10-06', 'NRTH', 'BUY', 3000],
].filter(([d]) => d <= yesterday);
for (const [tradeDate, securityId, side, quantity] of trades) {
  const price = px(securityId, tradeDate);
  const gross = securityId === 'GOVT29' ? quantity * price / 100 : quantity * price;
  await call('POST', '/api/trades', { fundId: FUND, securityId, side, quantity: String(quantity), price,
    commission: (gross * 0.0005).toFixed(2), tradeDate }, { 'Idempotency-Key': `seed-${tradeDate}-${securityId}-${side}` });
}

// Trades reach accounting through Kafka; wait until they've all arrived before closing a period.
process.stdout.write('Waiting for accounting to receive the trades');
const expectedNrth = trades.filter(([, s]) => s === 'NRTH').reduce((q, [, , side, n]) => q + (side === 'BUY' ? n : -n), 0);
for (let i = 0; ; i++) {
  const pos = await call('GET', `/api/accounting/funds/${FUND}/positions`).catch(() => null);
  if (pos?.data.find((p) => p.securityId === 'NRTH' && Number(p.quantity) === expectedNrth)) break;
  if (i > 60) throw new Error('\nTrades did not arrive in accounting within 60 s; is Kafka running?');
  process.stdout.write('.');
  await new Promise((r) => setTimeout(r, 1000));
}
console.log(' done.');

if (yesterday > '2026-08-31') {
  await call('POST', `/api/accounting/funds/${FUND}/periods/close`, { closeThrough: '2026-08-31' });
  console.log('Closed August.');
}
const s = await call('GET', `/api/accounting/funds/${FUND}/summary`);
console.log(`Done. ${FUND} NAV ${Number(s.nav).toLocaleString('en-US', { maximumFractionDigits: 2 })} USD as of ${s.basis.asOf}; ${s.exceptions.length} exceptions.`);

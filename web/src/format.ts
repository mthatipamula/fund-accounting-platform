const money = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const qty = new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 });
const px = new Intl.NumberFormat('en-US', { minimumFractionDigits: 2, maximumFractionDigits: 6 });
const pct = new Intl.NumberFormat('en-US', { style: 'percent', minimumFractionDigits: 2, maximumFractionDigits: 2 });

const n = (v: unknown) => (v === null || v === undefined || v === '' ? NaN : Number(v));

export const fmtMoney = (v: unknown) => (Number.isNaN(n(v)) ? '—' : money.format(n(v)));
export const fmtQty = (v: unknown) => (Number.isNaN(n(v)) ? '—' : qty.format(n(v)));
export const fmtPrice = (v: unknown) => (Number.isNaN(n(v)) ? '—' : px.format(n(v)));
export const fmtPct = (v: unknown) => (Number.isNaN(n(v)) ? '—' : pct.format(n(v)));
export const fmtTime = (v: string | null | undefined) => (v ? new Date(v).toLocaleString() : '—');
export const sign = (v: unknown) => (n(v) > 0 ? 'pos' : n(v) < 0 ? 'neg' : '');
export const today = () => new Date().toISOString().slice(0, 10);

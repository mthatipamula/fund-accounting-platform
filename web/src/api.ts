import { users } from './auth';

export class ApiError extends Error {
  constructor(public status: number, message: string, public fields?: Record<string, string>) { super(message); }
}

/** Calls the API with the user's token; turns RFC 7807 problem responses into readable errors. */
export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const user = await users.getUser();
  const headers = new Headers(init.headers);
  if (user) headers.set('Authorization', `Bearer ${user.access_token}`);
  if (init.body && !(init.body instanceof FormData)) headers.set('Content-Type', 'application/json');
  const res = await fetch(path, { ...init, headers });
  if (res.status === 401) { await users.signinRedirect(); throw new ApiError(401, 'Signing you in again…'); }
  if (!res.ok) {
    let message = `${res.status} ${res.statusText}`;
    let fields: Record<string, string> | undefined;
    try {
      const p = await res.json();
      message = p.detail ?? p.title ?? message;
      fields = p.fields;
      if (res.status === 403) message = "You don't have permission to do that.";
    } catch { /* not JSON */ }
    throw new ApiError(res.status, message, fields);
  }
  return res.status === 204 || res.headers.get('content-length') === '0' ? (undefined as T) : res.json();
}

export const post = <T,>(path: string, body?: unknown, headers?: HeadersInit) =>
  api<T>(path, { method: 'POST', body: body instanceof FormData ? body : body === undefined ? undefined : JSON.stringify(body), headers });
export const put = <T,>(path: string, body: unknown) => api<T>(path, { method: 'PUT', body: JSON.stringify(body) });

export function qs(params: Record<string, string | number | undefined | null>): string {
  const p = new URLSearchParams();
  for (const [k, v] of Object.entries(params)) if (v !== undefined && v !== null && v !== '') p.set(k, String(v));
  const s = p.toString();
  return s ? `?${s}` : '';
}

// ---------------------------------------------------------------- shapes

export type Num = number | string;  // decimals arrive as JSON numbers

export interface FundRow { id: string; name: string; baseCurrency: string; costMethod: string; inceptionDate: string }
export interface ShareClass { id: string; name: string; managementFeeRate: Num; initialNavPerShare: Num }
export interface Fund { id: string; name: string; baseCurrency: string; costMethod: string; inceptionDate: string; shareClasses: ShareClass[] }
export interface Basis { fundId: string; asOf: string; knownAt: string; lockedByClose: boolean; closedThrough: string | null }
export interface Report<T> { basis: Basis; data: T }
export interface ClassNav { shareClassId: string; name: string; nav: Num; shares: Num; navPerShare: Num; managementFeeRate: Num }
export interface CashBalance { currency: string; local: Num; base: Num }
export interface Exception { date: string; severity: string; message: string }
export interface Summary {
  basis: Basis; baseCurrency: string; nav: Num; totalAssets: Num; totalLiabilities: Num; shareClasses: ClassNav[];
  cash: CashBalance[]; positionCount: number; exceptions: Exception[];
}
export interface Position {
  securityId: string; symbol: string; name: string; assetClass: string; currency: string; quantity: Num; price: Num | null;
  fxRate: Num; costLocal: Num; costBase: Num; marketValueLocal: Num; marketValueBase: Num; unrealizedBase: Num;
  unrealizedPriceBase: Num; unrealizedFxBase: Num; accruedInterestLocal: Num; accruedInterestBase: Num; weight: Num;
}
export interface Realized {
  date: string; securityId: string; tradeId: string; quantity: Num; proceedsBase: Num; costBase: Num; gainBase: Num;
  priceComponentBase: Num; fxComponentBase: Num;
}
export interface SecurityPnl { securityId: string; realized: Num; unrealizedChange: Num; income: Num; total: Num }
export interface Pnl { from: string; to: string; byAccount: Record<string, Num>; bySecurity: Record<string, SecurityPnl>; total: Num }
export interface NavPoint { date: string; nav: Num; navPerShare: Record<string, Num> }
export interface JournalLine {
  entryId: number; date: string; account: string; currency: string; securityId: string | null; debit: Num; credit: Num;
  localAmount: Num; description: string; sourceType: string; sourceId: string;
}
export interface TrialBalanceRow { account: string; debit: Num; credit: Num }
export interface AccountInfo { account: string; code: string; label: string; type: string; monetary: boolean }
export interface InvestorHolding { investorId: string; shareClassId: string; shares: Num; value: Num }
export interface Investor { id: string; name: string; type: string }
export interface CapitalRow {
  id: string; version: number; fundId: string; shareClassId: string; investorId: string; type: string; dealingDate: string;
  amount: Num | null; shares: Num | null; recordedAt: string; supersededAt: string | null; cancelled: boolean; enteredBy: string;
}
export interface TradeVersion {
  id: string; version: number; fundId: string; securityId: string; side: 'BUY' | 'SELL'; quantity: Num; price: Num;
  commission: Num; tradeDate: string; settleDate: string; recordedAt: string; supersededAt: string | null;
  cancelled: boolean; enteredBy: string; reason: string | null;
}
export interface Security {
  id: string; symbol: string; name: string; assetClass: 'EQUITY' | 'BOND'; currency: string; couponRate?: Num | null;
  couponsPerYear?: number | null; issueDate?: string | null; maturityDate?: string | null; dayCount?: string | null;
}
export interface PriceRow { securityId: string; date: string; price: Num; source: string; recordedAt: string; supersededAt: string | null }
export interface FxRow { currency: string; baseCurrency: string; date: string; rate: Num; recordedAt: string; supersededAt: string | null }
export interface CorporateActionRow {
  id: string; version: number; securityId: string; type: string; exDate: string; payDate: string; amountPerShare: Num | null;
  ratio: Num | null; recordedAt: string; supersededAt: string | null; cancelled: boolean;
}
export interface PeriodClose { id: number; fundId: string; closedThrough: string | null; action: string; performedAt: string; performedBy: string; nav: Num | null }
export interface ReconLine { type: string; id: string; books: Num; statement: Num; difference: Num; status: string }
export interface ReconResult { fundId: string; asOf: string; matched: number; breaks: number; lines: ReconLine[] }

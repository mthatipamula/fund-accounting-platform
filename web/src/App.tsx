import { useEffect, useMemo, useState } from 'react';
import type { User } from 'oidc-client-ts';
import { api, type FundRow } from './api';
import { initialUser, rolesOf, users } from './auth';
import { ViewContext, type View } from './ui';
import { today } from './format';
import Dashboard from './pages/Dashboard';
import Positions from './pages/Positions';
import PnlPage from './pages/Pnl';
import Trades from './pages/Trades';
import Capital from './pages/Capital';
import Ledger from './pages/Ledger';
import ReferenceData from './pages/ReferenceData';
import Periods from './pages/Periods';
import Reconciliation from './pages/Reconciliation';
import Funds from './pages/Funds';

const PAGES = [
  { id: 'dashboard', label: 'Dashboard', el: Dashboard },
  { id: 'positions', label: 'Positions', el: Positions },
  { id: 'pnl', label: 'P&L', el: PnlPage },
  { id: 'trades', label: 'Trades', el: Trades },
  { id: 'capital', label: 'Capital & investors', el: Capital },
  { id: 'ledger', label: 'General ledger', el: Ledger },
  { id: 'reference', label: 'Reference data', el: ReferenceData },
  { id: 'periods', label: 'Period close', el: Periods },
  { id: 'recon', label: 'Reconciliation', el: Reconciliation },
  { id: 'funds', label: 'Fund setup', el: Funds },
] as const;

function pageFromHash() {
  const id = window.location.hash.replace('#/', '');
  return PAGES.find((p) => p.id === id) ?? PAGES[0];
}

export default function App() {
  const [user, setUser] = useState<User | null | undefined>(undefined);
  const [authError, setAuthError] = useState<string | null>(null);
  useEffect(() => {
    initialUser().then(setUser).catch((e) => { setAuthError((e as Error).message); setUser(null); });
    const loaded = (u: User) => setUser(u);
    const gone = () => setUser(null);
    users.events.addUserLoaded(loaded);
    users.events.addUserSignedOut(gone);
    return () => { users.events.removeUserLoaded(loaded); users.events.removeUserSignedOut(gone); };
  }, []);

  if (user === undefined) return <div className="center muted">Loading…</div>;
  if (!user) {
    return (
      <div className="center">
        <div className="card signin">
          <h1>Fund Accounting</h1>
          <p className="muted">Portfolio and fund accounting: trades, valuation, NAV, the general ledger and period close.</p>
          {authError && <div className="notice error">{authError}</div>}
          <button className="primary" onClick={() => users.signinRedirect()}>Sign in</button>
        </div>
      </div>
    );
  }
  return <Shell user={user} />;
}

function Shell({ user }: { user: User }) {
  const [page, setPage] = useState(pageFromHash);
  const [funds, setFunds] = useState<FundRow[] | null>(null);
  const [fundId, setFundId] = useState(() => localStorage.getItem('fundId') ?? '');
  const [asOf, setAsOf] = useState(today());
  const [knownAt, setKnownAt] = useState('');
  const [refresh, setRefresh] = useState(0);
  const roles = useMemo(() => rolesOf(user), [user]);

  useEffect(() => {
    const onHash = () => setPage(pageFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);
  useEffect(() => {
    api<FundRow[]>('/api/accounting/funds').then((f) => {
      setFunds(f);
      if (f.length && !f.some((x) => x.id === fundId)) setFundId(f[0].id);
    }).catch(() => setFunds([]));
  }, [refresh]); // eslint-disable-line react-hooks/exhaustive-deps
  useEffect(() => { try { if (fundId) localStorage.setItem('fundId', fundId); } catch { /* storage unavailable */ } }, [fundId]);

  const view: View = {
    fundId, asOf, knownAt: knownAt ? new Date(knownAt).toISOString() : '', roles, refresh,
    bump: () => setRefresh((x) => x + 1),
  };
  const Page = page.el;
  const name = user.profile.preferred_username ?? user.profile.name;

  return (
    <ViewContext.Provider value={view}>
      <div className="layout">
        <nav className="sidebar" aria-label="Main">
          <div className="brand">Fund Accounting</div>
          {PAGES.map((p) => (
            <a key={p.id} href={`#/${p.id}`} className={p.id === page.id ? 'active' : ''}>{p.label}</a>
          ))}
          <div className="sidebar-foot small">
            <div>{name}</div>
            <div className="muted">{[...roles].map((r) => r.replace('fa-', '')).join(', ') || 'no access'}</div>
            <button className="link small" onClick={() => users.signoutRedirect()}>Sign out</button>
          </div>
        </nav>
        <main>
          <header className="toolbar">
            <label>Fund
              <select value={fundId} onChange={(e) => setFundId(e.target.value)} disabled={!funds?.length}>
                {!funds?.length && <option value="">No funds yet</option>}
                {funds?.map((f) => <option key={f.id} value={f.id}>{f.name} ({f.baseCurrency})</option>)}
              </select>
            </label>
            <label>As of <input type="date" value={asOf} max={today()} onChange={(e) => e.target.value && setAsOf(e.target.value)} /></label>
            <label title="Show the books as they were known at this moment, before any later corrections">As known at
              <input type="datetime-local" value={knownAt} onChange={(e) => setKnownAt(e.target.value)} />
              {knownAt && <button className="link small" onClick={() => setKnownAt('')}>latest</button>}
            </label>
            <button className="secondary small" onClick={view.bump}>Refresh</button>
          </header>
          {!fundId && page.id !== 'funds' && page.id !== 'reference'
            ? <div className="page"><div className="notice">No funds yet. Create one in <a href="#/funds">Fund setup</a>.</div></div>
            : <div className="page"><h1>{page.label}</h1><Page /></div>}
        </main>
      </div>
    </ViewContext.Provider>
  );
}

import { useCallback, useEffect, useMemo, useState } from 'react';
import { api, getToken, onUnauthorized, setToken } from './api.js';
import { Avatar, BRAND, Icon, Logo, ToastProvider, Tone } from './components.jsx';
import { navigate, useHashRoute, usePoll } from './hooks.js';
import { SessionContext, useSession } from './session.js';
import { Login, Signup } from './pages/Auth.jsx';
import Home from './pages/Home.jsx';
import Send from './pages/Send.jsx';
import Move from './pages/Move.jsx';
import Activity from './pages/Activity.jsx';
import Notifications from './pages/Notifications.jsx';
import Admin from './pages/Admin.jsx';

const NAV = [
  ['home', 'Home', 'home'],
  ['send', 'Send', 'send'],
  ['deposit', 'Add money', 'add'],
  ['withdraw', 'Withdraw', 'withdraw'],
  ['activity', 'Activity', 'activity'],
  ['notifications', 'Notifications', 'bell'],
];

const TITLES = {
  home: 'Overview', send: 'Send money', deposit: 'Add money', withdraw: 'Withdraw',
  activity: 'Activity', notifications: 'Notifications', admin: 'Administration',
};

const PUBLIC = ['login', 'signup'];

export default function App() {
  const [token, setTokenState] = useState(getToken());
  const [me, setMe] = useState(null);
  const route = useHashRoute();

  const logout = useCallback(() => {
    setToken(null);
    setTokenState(null);
    setMe(null);
    navigate('login');
  }, []);

  useEffect(() => onUnauthorized(logout), [logout]);

  useEffect(() => {
    if (token && !me) api('GET', '/api/v1/auth/me').then(setMe).catch(() => {});
  }, [token, me]);

  const login = useCallback(async (username, password) => {
    const result = await api('POST', '/api/v1/auth/login', { username, password });
    setToken(result.accessToken);
    setTokenState(result.accessToken);
    setMe(await api('GET', '/api/v1/auth/me'));
  }, []);

  const isAdmin = Boolean(me && me.roles && me.roles.includes('ADMIN'));
  const session = useMemo(() => ({ me, isAdmin, login, logout }), [me, isAdmin, login, logout]);

  // not logged in: login or signup; logged in: the app; UI-3: the admin console only for ADMIN
  let page = route;
  if (!token) page = PUBLIC.includes(route) ? route : 'login';
  else if (PUBLIC.includes(route) || !route) page = 'home';
  else if (route === 'admin' && me && !isAdmin) page = 'home';
  useEffect(() => { if (page !== route) navigate(page); }, [page, route]);

  return (
    <ToastProvider>
      <SessionContext.Provider value={session}>
        {!token
          ? <AuthLayout>{page === 'signup' ? <Signup /> : <Login />}</AuthLayout>
          : <Shell page={page} />}
      </SessionContext.Provider>
    </ToastProvider>
  );
}

// ------------------------------------------------------------------ login and sign-up screen
function AuthLayout({ children }) {
  return (
    <div className="auth">
      <section className="auth-hero" aria-hidden="true">
        <div className="brand brand-light"><Logo size={36} /> {BRAND}</div>
        <div className="hero-art">
          <div className="hero-phone">
            <div className="hero-phone-top"><span>Balance</span><span>USD</span></div>
            <div className="hero-phone-balance">$2,480.50</div>
            <div className="hero-bars">
              {[38, 62, 48, 80, 56, 92, 70].map((h, i) => <span key={i} style={{ height: `${h}%` }} />)}
            </div>
            <div className="hero-phone-row"><Tone tone="add" icon="add" size={28} /><span>Added money</span><b>+$500.00</b></div>
            <div className="hero-phone-row"><Tone tone="send" icon="out" size={28} /><span>Sent to Bob</span><b>-$12.50</b></div>
          </div>
          <div className="hero-float hero-float-a"><Tone tone="add" icon="in" size={32} /><div><b>Bob Lee</b><span>sent you $12.50</span></div></div>
          <div className="hero-float hero-float-b"><Tone tone="withdraw" icon="bank" size={32} /><div><b>Withdrawal</b><span>Sent to your bank</span></div></div>
          <div className="hero-float hero-float-c"><Tone tone="bell" icon="check" size={32} /><div><b>Deposit complete</b><span>$500.00 is in your wallet</span></div></div>
        </div>
        <div className="hero-copy">
          <h2>Money that moves the moment you do.</h2>
          <ul className="hero-points">
            <li><Icon name="bolt" size={16} /> Instant transfers to anyone with a username</li>
            <li><Icon name="ledger" size={16} /> Every cent recorded in a double-entry ledger</li>
            <li><Icon name="bell" size={16} /> A notification for every movement</li>
          </ul>
        </div>
      </section>
      <section className="auth-form">
        <div className="auth-card">
          <div className="brand brand-mobile"><Logo /> {BRAND}</div>
          {children}
          <p className="auth-secure"><Icon name="lock" size={14} /> Passwords are stored as salted hashes, never in plain text.</p>
        </div>
      </section>
    </div>
  );
}

// ------------------------------------------------------------------ the app around the pages
function Shell({ page }) {
  const { me, isAdmin, logout } = useSession();
  const [unread, setUnread] = useState(0);

  // UI-5: the unread count appears without a reload
  usePoll(async () => {
    try {
      const { count } = await api('GET', '/api/v1/notifications/unread-count');
      setUnread(count);
    } catch (e) { /* next tick */ }
  }, 3000);

  const items = isAdmin ? [...NAV, ['admin', 'Admin', 'shield']] : NAV;
  const PAGES = {
    home: <Home />,
    send: <Send />,
    deposit: <Move kind="deposit" />,
    withdraw: <Move kind="withdraw" />,
    activity: <Activity />,
    notifications: <Notifications onUnread={setUnread} />,
    admin: <Admin />,
  };

  return (
    <div className="shell">
      <aside className="side">
        <div className="brand"><Logo /> {BRAND}</div>
        <nav className="nav" aria-label="Main">
          {items.map(([route, label, icon]) => (
            <a key={route} href={`#/${route}`} className={`nav-${route} ${route === page ? 'active' : ''}`}
               aria-current={route === page ? 'page' : undefined}>
              <span className="nav-icon"><Icon name={icon} /></span>
              <span className="nav-label">{label}</span>
              {route === 'notifications' && unread > 0 && <span className="badge" aria-label={`${unread} unread`}>{unread}</span>}
            </a>
          ))}
        </nav>
        <div className="side-card">
          <Icon name="ledger" size={18} />
          <p><b>Double-entry ledger</b>Every movement is two entries that add up to zero.</p>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <span className="topbar-title">{TITLES[page] || ''}</span>
          <div className="topbar-end">
            <a className="bell" href="#/notifications" aria-label={`Notifications, ${unread} unread`}>
              <Icon name="bell" size={20} />
              {unread > 0 && <span className="bell-dot">{unread > 9 ? '9+' : unread}</span>}
            </a>
            {me && (
              <div className="who">
                <Avatar name={me.displayName} size={36} />
                <div className="who-text">
                  <strong>{me.displayName}</strong>
                  <span>{isAdmin ? 'Administrator' : `@${me.username}`}</span>
                </div>
                <button className="icon-button" onClick={logout} aria-label="Log out" title="Log out">
                  <Icon name="logout" />
                </button>
              </div>
            )}
          </div>
        </header>
        <main className={`page ${page === 'admin' ? 'page-wide' : ''}`} key={page}>
          {PAGES[page] || <Home />}
        </main>
      </div>
    </div>
  );
}

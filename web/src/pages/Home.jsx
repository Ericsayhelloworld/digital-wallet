import { useState } from 'react';
import { api, messageFor } from '../api.js';
import { Avatar, Empty, Guilloche, Icon, Notice, Tone, TrendChart, useToast } from '../components.jsx';
import { fmt, greeting } from '../format.js';
import { usePoll } from '../hooks.js';
import { useSession } from '../session.js';
import { EntryRow, recentContacts } from './shared.jsx';

export default function Home() {
  const { me } = useSession();
  const toast = useToast();
  const [wallet, setWallet] = useState(null);
  const [missing, setMissing] = useState(false);
  const [entries, setEntries] = useState([]);
  const [error, setError] = useState('');
  const [opening, setOpening] = useState(false);

  async function load() {
    try {
      const w = await api('GET', '/api/v1/wallets/me');
      setWallet(w);
      setMissing(false);
      setError('');
      const page = await api('GET', '/api/v1/wallets/me/entries?limit=50');
      setEntries(page.items);
    } catch (err) {
      if (err.code === 'WALLET_NOT_FOUND') setMissing(true);
      else setError(messageFor(err));
    }
  }

  // UI-5: refreshed every 3 seconds; not while the user has no wallet
  usePoll(load, missing ? null : 3000, [missing]);

  async function open() {
    setOpening(true);
    try {
      await api('POST', '/api/v1/wallets');
      toast('ok', 'Your wallet is open.');
      setMissing(false);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setOpening(false);
    }
  }

  const firstName = me ? me.displayName.split(' ')[0] : '';

  if (missing) {
    return (
      <>
        <h1>{greeting()}, {firstName}</h1>
        <div className="open-wallet">
          <Guilloche width={760} height={240} lines={16} />
          <div className="open-wallet-text">
            <h2>Open your wallet</h2>
            <p>Add money from your bank, send it to anyone with a username, and withdraw it back whenever you like.</p>
            <button className="btn btn-bright" onClick={open} disabled={opening}>{opening ? 'Opening' : 'Open my wallet'}</button>
          </div>
        </div>
        <Notice kind="error">{error}</Notice>
      </>
    );
  }

  if (!wallet) return <><h1>{greeting()}, {firstName}</h1><div className="banknote skeleton" /></>;

  const percent = wallet.dailyLimit > 0 ? Math.min(100, (wallet.sentToday / wallet.dailyLimit) * 100) : 0;

  // this month's money in and out, from the statement
  const monthStart = new Date();
  monthStart.setDate(1);
  monthStart.setHours(0, 0, 0, 0);
  const thisMonth = entries.filter(e => new Date(e.createdAt) >= monthStart);
  const moneyIn = thisMonth.filter(e => e.amount > 0 && e.type !== 'WITHDRAWAL_REVERSAL').reduce((s, e) => s + e.amount, 0);
  const moneyOut = thisMonth.filter(e => e.amount < 0).reduce((s, e) => s - e.amount, 0);
  const trend = [...entries].reverse().map(e => ({ value: e.balanceAfter }));
  const contacts = recentContacts(entries);

  return (
    <>
      <h1>{greeting()}, {firstName}</h1>

      <div className="home-grid">
        <section className="banknote" aria-label="Your wallet">
          <Guilloche />
          <div className="banknote-top">
            <span className="banknote-label">Available balance</span>
            <span className="banknote-currency">USD</span>
          </div>
          <div className="banknote-balance num">{fmt(wallet.balance)}</div>
          {wallet.reserved > 0 && (
            <div className="banknote-reserved"><Icon name="reserve" size={15} /> {fmt(wallet.reserved)} reserved for withdrawals in progress</div>
          )}
          <div className="banknote-limit">
            <div className="banknote-limit-text num">
              <span>Sent today</span>
              <span>{fmt(wallet.sentToday)} of {fmt(wallet.dailyLimit)}</span>
            </div>
            <div className="meter" role="progressbar" aria-valuemin={0} aria-valuemax={100} aria-valuenow={Math.round(percent)}>
              <span style={{ width: `${percent}%` }} />
            </div>
          </div>
          <div className="banknote-bottom">
            <span>@{wallet.username}</span>
            <button className="serial" title="Copy wallet ID"
                    onClick={() => navigator.clipboard && navigator.clipboard.writeText(wallet.id).then(() => toast('ok', 'Wallet ID copied.'))}>
              {wallet.id} <Icon name="copy" size={13} />
            </button>
          </div>
        </section>

        <div className="quick">
          <a className="quick-action quick-send" href="#/send"><Tone tone="send" icon="send" size={42} /><span>Send</span></a>
          <a className="quick-action quick-add" href="#/deposit"><Tone tone="add" icon="add" size={42} /><span>Add money</span></a>
          <a className="quick-action quick-withdraw" href="#/withdraw"><Tone tone="withdraw" icon="withdraw" size={42} /><span>Withdraw</span></a>
        </div>
      </div>

      <div className="stats">
        <div className="stat">
          <Tone tone="add" icon="trendUp" size={36} />
          <div><span>Money in this month</span><b className="num">{fmt(moneyIn)}</b></div>
        </div>
        <div className="stat">
          <Tone tone="send" icon="trendDown" size={36} />
          <div><span>Money out this month</span><b className="num">{fmt(moneyOut)}</b></div>
        </div>
        <div className="stat">
          <Tone tone="return" icon="clock" size={36} />
          <div><span>Reserved right now</span><b className="num">{fmt(wallet.reserved)}</b></div>
        </div>
      </div>

      <div className="home-columns">
        <section className="panel">
          <div className="panel-head">
            <h2>Balance</h2>
            <span className="muted small">Last {Math.min(trend.length, 50)} movements</span>
          </div>
          <TrendChart points={trend} />
        </section>
        <section className="panel">
          <div className="panel-head">
            <h2>Send again</h2>
            <a className="small" href="#/send">New transfer</a>
          </div>
          {contacts.length ? (
            <div className="contacts">
              {contacts.map(c => (
                <a key={c.walletId} className="contact" href={`#/send?to=${encodeURIComponent(c.walletId)}&name=${encodeURIComponent(c.displayName)}`}>
                  <Avatar name={c.displayName} size={46} />
                  <span>{c.displayName.split(' ')[0]}</span>
                </a>
              ))}
            </div>
          ) : <p className="muted small">People you send money to will show up here.</p>}
        </section>
      </div>

      <div className="section-head">
        <h2>Recent activity</h2>
        <a href="#/activity">See all</a>
      </div>
      {entries.length
        ? <ul className="list">{entries.slice(0, 5).map(e => <EntryRow key={e.id} entry={e} />)}</ul>
        : <Empty title="No activity yet">Add money to get started.</Empty>}
      <Notice kind="error">{error}</Notice>
    </>
  );
}

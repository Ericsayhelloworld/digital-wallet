import { useState } from 'react';
import { api, messageFor, moneyRequest } from '../api.js';
import { Field, Journey, Notice, StatusPill, useToast } from '../components.jsx';
import { fmt, parseDollars, timeAgo } from '../format.js';
import { usePoll } from '../hooks.js';

const CONFIG = {
  deposit: {
    title: 'Add money',
    path: '/api/v1/deposits',
    intro: 'Money comes from your bank account. It reaches your wallet once the bank confirms it.',
    button: 'Add money',
    done: 'Money added',
  },
  withdraw: {
    title: 'Withdraw',
    path: '/api/v1/withdrawals',
    intro: 'Money goes to your bank account. It stays reserved until the bank confirms the payout.',
    button: 'Withdraw',
    done: 'Withdrawal complete',
  },
};

const PRESETS = [10, 50, 100];

/** UI-2 and UI-5: enter the amount, then follow the operation until its status is final. */
export default function Move({ kind }) {
  const cfg = CONFIG[kind];
  const toast = useToast();
  const [amount, setAmount] = useState('');
  const [current, setCurrent] = useState(null);
  const [recent, setRecent] = useState([]);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  usePoll(async () => {
    try {
      const page = await api('GET', `${cfg.path}?limit=5`);
      setRecent(page.items);
    } catch (e) { /* not essential */ }
  }, 3000, [kind]);

  // while the latest operation is PENDING, ask for its status every second
  const pending = current && current.status === 'PENDING';
  usePoll(async () => {
    if (!current || current.status !== 'PENDING') return;
    try {
      const fresh = await api('GET', `${cfg.path}/${current.id}`);
      setCurrent(fresh);
      if (fresh.status === 'SUCCEEDED') toast('ok', `${cfg.done}: ${fmt(fresh.amount)}.`);
      if (fresh.status === 'FAILED') toast('error', `The bank declined ${fmt(fresh.amount)}.`);
    } catch (e) { /* keep polling */ }
  }, pending ? 1000 : null, [pending, current && current.id]);

  async function submit(e) {
    e.preventDefault();
    setError('');
    const cents = parseDollars(amount);
    if (cents === null) {
      setError('Enter an amount like 100.00');
      return;
    }
    setBusy(true);
    try {
      setCurrent(await moneyRequest(cfg.path, { amount: cents }));
      setAmount('');
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className={`move-${kind}`}>
      <h1>{cfg.title}</h1>
      <p className="lead">{cfg.intro}</p>
      <div className="move-grid">
        <form className="panel stack" onSubmit={submit}>
          <Field label="Amount in USD">
            <div className="amount-input">
              <span>$</span>
              <input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} placeholder="0.00" required />
            </div>
          </Field>
          <div className="chips">
            {PRESETS.map(p => (
              <button type="button" key={p} className="chip" onClick={() => setAmount(String(p))}>${p}</button>
            ))}
          </div>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Sending request' : cfg.button}</button>
          <Notice kind="error">{error}</Notice>
        </form>

        <div className="panel">
          {current ? (
            <div className="stack">
              <div className="between">
                <div className="big num">{fmt(current.amount)}</div>
                <StatusPill status={current.status} />
              </div>
              <Journey kind={kind} status={current.status} />
              {current.status === 'PENDING' && <p className="muted small">The bank answers in its own time. This updates by itself.</p>}
              {current.failureReason && <p className="muted small">Reason given by the bank: {current.failureReason}</p>}
            </div>
          ) : (
            <div className="stack">
              <p className="muted">Your request will show here, step by step.</p>
              <Journey kind={kind} status="PENDING" />
            </div>
          )}
        </div>
      </div>

      <h2>Recent</h2>
      <OperationList items={recent} kind={kind} />
    </div>
  );
}

export function OperationList({ items, kind }) {
  if (!items.length) {
    return <p className="muted">{kind === 'deposit' ? 'No money added yet.' : 'No withdrawals yet.'}</p>;
  }
  return (
    <ul className="list">
      {items.map(op => (
        <li key={op.id} className="row">
          <div className="row-main">
            <div className="row-title">{kind === 'deposit' ? 'Added money' : 'Withdrawal'}</div>
            <div className="row-sub">{timeAgo(op.createdAt)}{op.failureReason ? `, ${op.failureReason}` : ''}</div>
          </div>
          <div className="row-end">
            <div className="amount">{fmt(op.amount)}</div>
            <StatusPill status={op.status} />
          </div>
        </li>
      ))}
    </ul>
  );
}

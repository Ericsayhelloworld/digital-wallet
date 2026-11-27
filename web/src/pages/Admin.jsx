import { useState } from 'react';
import { api, getLastMoney, messageFor, rawMoneyPost } from '../api.js';
import { Field, Icon, Notice, useToast } from '../components.jsx';
import { fmt, timeOf } from '../format.js';
import { usePoll } from '../hooks.js';

const DEFAULTS = { down: false, responseDelayMs: 50, hangRate: 0, settleDelayMs: 500, declineRate: 0, webhookCopies: 1 };

const FIELDS = [
  ['down', 'Down', 'checkbox'],
  ['responseDelayMs', 'Response delay (ms)', 'number'],
  ['hangRate', 'Hang rate (0 to 1)', 'number'],
  ['settleDelayMs', 'Settle delay (ms)', 'number'],
  ['declineRate', 'Decline rate (0 to 1)', 'number'],
  ['webhookCopies', 'Webhook copies', 'number'],
];

// UI-3: one button per preset
const PRESETS = [
  ['Normal', {}],
  ['Slow', { responseDelayMs: 2000, settleDelayMs: 5000 }],
  ['Down', { down: true }],
  ['Hang', { hangRate: 1, webhookCopies: 0 }],
  ['Decline', { declineRate: 1 }],
  ['Duplicate webhooks', { webhookCopies: 3 }],
  ['No webhooks', { webhookCopies: 0 }],
];

const CIRCUIT = {
  CLOSED: ['ok', 'Calls go through'],
  OPEN: ['bad', 'Calls are stopped'],
  HALF_OPEN: ['warn', 'Testing the bank'],
};

function matches(settings, preset) {
  const expected = { ...DEFAULTS, ...preset };
  return settings && Object.keys(DEFAULTS).every(key => Number(settings[key]) === Number(expected[key]));
}

export default function Admin() {
  const toast = useToast();
  const [status, setStatus] = useState(null);
  const [wallets, setWallets] = useState([]);
  const [report, setReport] = useState(null);
  const [bank, setBank] = useState(null);
  const [form, setForm] = useState(DEFAULTS);
  const [results, setResults] = useState(null);
  const [error, setError] = useState('');
  const lastMoney = getLastMoney();

  // UI-3: status and wallets refreshed every 2 seconds
  usePoll(async () => {
    try {
      setStatus(await api('GET', '/api/v1/admin/status'));
      setWallets((await api('GET', '/api/v1/admin/wallets?limit=100')).items);
    } catch (err) {
      setError(messageFor(err));
    }
  }, 2000);

  usePoll(async () => {
    try {
      const settings = await api('GET', '/bank/admin/config');
      setBank(settings);
      setForm(settings);
    } catch (err) {
      setError(messageFor(err));
    }
  }, null);

  async function applyBank(settings, label) {
    try {
      const result = await api('PUT', '/bank/admin/config', settings);
      setBank(result);
      setForm(result);
      toast('ok', `${label || 'Bank settings'} applied.`);
    } catch (err) {
      toast('error', messageFor(err));
    }
  }

  async function reconcile() {
    try {
      await api('POST', '/api/v1/admin/reconciliation');
      toast('ok', 'Reconciliation started for every pending operation.');
    } catch (err) {
      toast('error', messageFor(err));
    }
  }

  async function check() {
    try {
      setReport(await api('GET', '/api/v1/admin/integrity'));
    } catch (err) {
      toast('error', messageFor(err));
    }
  }

  async function diagnose(times) {
    if (!lastMoney) {
      setResults('none');
      return;
    }
    setResults(await Promise.all(Array.from({ length: times }, () => rawMoneyPost(lastMoney))));
  }

  const circuit = status ? (CIRCUIT[status.bankCircuit] || ['warn', status.bankCircuit]) : null;

  return (
    <>
      <h1>Administration</h1>
      <Notice kind="error">{error}</Notice>

      {/* ---------------- Status (ADM-2) ---------------- */}
      <div className="section-head">
        <h2>Status</h2>
        <button className="btn btn-small" onClick={reconcile}><Icon name="refresh" size={15} /> Run reconciliation now</button>
      </div>
      {status && (
        <div className="tiles">
          <div className={`tile tile-circuit tile-${circuit[0]}`}>
            <span className="tile-k">Bank circuit</span>
            <span className="tile-v"><span className="light" aria-hidden="true" />{status.bankCircuit}</span>
            <span className="tile-note">{circuit[1]}</span>
          </div>
          <Tile label="Pending deposits" value={status.pendingDeposits} />
          <Tile label="Pending withdrawals" value={status.pendingWithdrawals} />
          <Tile label="Oldest pending" value={`${status.oldestPendingSeconds} s`} />
          <Tile label="Unsent events" value={status.outboxUnsent} />
          <Tile label="Oldest unsent event" value={`${status.outboxOldestSeconds} s`} />
        </div>
      )}

      {/* ---------------- Bank failure settings ---------------- */}
      <h2>Bank</h2>
      <div className="presets" role="group" aria-label="Bank presets">
        {PRESETS.map(([label, preset]) => {
          const active = matches(bank, preset);
          return (
            <button key={label} className={`preset ${active ? 'preset-on' : ''} ${label === 'Normal' ? '' : 'preset-fault'}`}
                    aria-pressed={active} onClick={() => applyBank(preset, label)}>{label}</button>
          );
        })}
      </div>
      <details className="panel settings-panel">
        <summary>Custom settings</summary>
        <form className="settings" onSubmit={e => {
          e.preventDefault();
          const settings = {};
          for (const [key, , type] of FIELDS) settings[key] = type === 'checkbox' ? Boolean(form[key]) : Number(form[key]);
          applyBank(settings);
        }}>
          {FIELDS.map(([key, label, type]) => (
            <Field key={key} label={label}>
              {type === 'checkbox'
                ? <input type="checkbox" checked={Boolean(form[key])} onChange={e => setForm({ ...form, [key]: e.target.checked })} />
                : <input type="number" step="any" min="0" value={form[key]} onChange={e => setForm({ ...form, [key]: e.target.value })} />}
            </Field>
          ))}
          <button className="btn btn-primary">Apply settings</button>
        </form>
      </details>

      {/* ---------------- Integrity (ADM-1) ---------------- */}
      <div className="section-head">
        <h2>Integrity</h2>
        <button className="btn btn-small btn-primary" onClick={check}>Run integrity checks</button>
      </div>
      {report && (
        <div className="panel stack">
          <Notice kind={report.ok ? 'ok' : 'error'}>
            {report.ok ? 'All six invariants hold' : 'Some invariants do not hold'}, checked at {timeOf(report.checkedAt)}.
          </Notice>
          <ul className="checks">
            {report.checks.map(c => (
              <li key={c.id} className={c.ok ? 'ok' : 'bad'}>
                <span className="check-mark"><Icon name={c.ok ? 'check' : 'x'} size={14} /></span>
                <strong>{c.id}</strong>
                <span className="muted">{c.detail}</span>
              </li>
            ))}
          </ul>
          <div className="equation num" aria-label="The ledger adds up to zero">
            <span><small>Wallets</small>{fmt(report.totals.wallets)}</span>
            <b>+</b>
            <span><small>Payout hold</small>{fmt(report.totals.payoutHold)}</span>
            <b>+</b>
            <span><small>External bank</small>{fmt(report.totals.externalBank)}</span>
            <b>=</b>
            <span className="equation-total">
              <small>Total</small>{fmt(report.totals.wallets + report.totals.payoutHold + report.totals.externalBank)}
            </span>
          </div>
        </div>
      )}

      {/* ---------------- Diagnostics ---------------- */}
      <h2>Diagnostics</h2>
      <div className="panel stack">
        <p className="muted small">
          {lastMoney
            ? <>Last money request: <code>POST {lastMoney.path}</code> with Idempotency-Key <code>{lastMoney.key}</code></>
            : 'No money request in this tab yet.'}
        </p>
        <div className="actions">
          <button className="btn" onClick={() => diagnose(1)}>Repeat the last money request with the same key</button>
          <button className="btn" onClick={() => diagnose(10)}>Send the last money request 10 times in parallel with the same key</button>
        </div>
        {results === 'none' && <Notice kind="error">Make a money request in this tab first: Add money, Send or Withdraw, then come back here.</Notice>}
        {Array.isArray(results) && (
          <div className="responses">
            {results.map((r, i) => (
              <div key={i} className={`response ${r.replayed ? 'replayed' : ''}`}>
                <span className="response-n">#{i + 1}</span>
                <strong className="num">{r.status || 'error'}</strong>
                <span>{r.replayed ? 'replayed' : 'executed'}</span>
              </div>
            ))}
          </div>
        )}
      </div>

      {/* ---------------- Wallets (ADM-3) ---------------- */}
      <h2>Wallets</h2>
      <div className="table-wrap">
        <table className="table">
          <thead>
            <tr><th>Name</th><th>Username</th><th>Wallet ID</th><th className="right">Reserved</th><th className="right">Balance</th></tr>
          </thead>
          <tbody>
            {wallets.map(w => (
              <tr key={w.walletId}>
                <td>{w.displayName}</td>
                <td className="muted">@{w.username}</td>
                <td className="muted mono-ish">{w.walletId}</td>
                <td className="right num">{w.reserved ? fmt(w.reserved) : ''}</td>
                <td className="right num strong">{fmt(w.balance)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* ---------------- Links (UI-3) ---------------- */}
      <h2>Links</h2>
      <div className="links">
        {[
          ['auth-service API', '/docs/auth/swagger-ui.html'],
          ['wallet-service API', '/docs/wallet/swagger-ui.html'],
          ['notification-service API', '/docs/notifications/swagger-ui.html'],
          ['bank-simulator API', '/docs/bank/swagger-ui.html'],
          ['Grafana dashboard', `${window.location.protocol}//${window.location.hostname}:3000`],
          ['Zipkin traces', `${window.location.protocol}//${window.location.hostname}:9411`],
        ].map(([label, href]) => (
          <a key={label} className="link-card" href={href} target="_blank" rel="noopener noreferrer">
            <Icon name="link" size={16} /> {label}
          </a>
        ))}
      </div>
    </>
  );
}

function Tile({ label, value }) {
  return (
    <div className="tile">
      <span className="tile-k">{label}</span>
      <span className="tile-v num">{value}</span>
    </div>
  );
}

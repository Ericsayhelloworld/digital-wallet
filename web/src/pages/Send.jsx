import { useEffect, useState } from 'react';
import { api, messageFor, moneyRequest } from '../api.js';
import { Avatar, Burst, Field, Icon, Notice, useToast } from '../components.jsx';
import { fmt, parseDollars } from '../format.js';
import { routeParams } from '../hooks.js';
import { recentContacts } from './shared.jsx';

const STEPS = ['Recipient', 'Amount', 'Confirm'];

/** UI-2: enter a username, look up the recipient, show the name, amount and note, confirm, show the result. */
export default function Send() {
  const toast = useToast();
  const [step, setStep] = useState(0);
  const [username, setUsername] = useState('');
  const [recipient, setRecipient] = useState(null);
  const [amount, setAmount] = useState('');
  const [note, setNote] = useState('');
  const [result, setResult] = useState(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const [contacts, setContacts] = useState([]);

  // "Send again" from the home page opens this page with the recipient already chosen
  useEffect(() => {
    const params = routeParams();
    if (params.get('to')) {
      setRecipient({ walletId: params.get('to'), displayName: params.get('name') || 'Recipient', username: null });
      setStep(1);
    }
    api('GET', '/api/v1/wallets/me/entries?limit=50').then(page => setContacts(recentContacts(page.items))).catch(() => {});
  }, []);

  function pick(contact) {
    setRecipient({ walletId: contact.walletId, displayName: contact.displayName, username: null });
    setError('');
    setStep(1);
  }

  async function find(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      setRecipient(await api('GET', `/api/v1/wallets/lookup?username=${encodeURIComponent(username.trim())}`));
      setStep(1);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  function review(e) {
    e.preventDefault();
    setError(parseDollars(amount) === null ? 'Enter an amount like 12.50' : '');
    if (parseDollars(amount) !== null) setStep(2);
  }

  async function send() {
    setError('');
    setBusy(true);
    try {
      const transfer = await moneyRequest('/api/v1/transfers', {
        toWalletId: recipient.walletId, amount: parseDollars(amount), note: note.trim() || null,
      });
      setResult(transfer);
      toast('ok', `Sent ${fmt(transfer.amount)} to ${recipient.displayName}.`);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  function again() {
    if (window.location.hash.includes('?')) window.location.hash = '#/send';
    setStep(0); setUsername(''); setRecipient(null); setAmount(''); setNote(''); setResult(null); setError('');
  }

  if (result) {
    return (
      <div className="send-page">
        <h1>Send money</h1>
        <div className="panel success">
          <span className="success-mark"><Burst /><Icon name="check" size={28} /></span>
          <div className="big num">{fmt(result.amount)}</div>
          <p>Sent to <strong>{recipient.displayName}</strong></p>
          {result.note && <p className="muted">“{result.note}”</p>}
          <div className="actions">
            <a className="btn" href="#/activity">See activity</a>
            <button className="btn btn-primary" onClick={again}>Send again</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="send-page">
      <h1>Send money</h1>
      <ol className="steps" aria-label="Steps">
        {STEPS.map((label, i) => (
          <li key={label} className={i === step ? 'current' : i < step ? 'done' : undefined}>
            <span className="steps-number">{i < step ? <Icon name="check" size={13} /> : i + 1}</span>{label}
          </li>
        ))}
      </ol>

      {step === 0 && (
        <form className="panel stack" onSubmit={find}>
          <Field label="Recipient username">
            <div className="input-icon">
              <Icon name="search" />
              <input value={username} onChange={e => setUsername(e.target.value)} autoCapitalize="none" autoComplete="off"
                     placeholder="bob" required autoFocus />
            </div>
          </Field>
          <button className="btn btn-primary" disabled={busy}>{busy ? 'Looking up' : 'Find'}</button>
          {contacts.length > 0 && (
            <div className="stack-tight">
              <span className="field-label">Or choose someone you know</span>
              <div className="contacts">
                {contacts.map(c => (
                  <button type="button" key={c.walletId} className="contact" onClick={() => pick(c)}>
                    <Avatar name={c.displayName} size={46} />
                    <span>{c.displayName.split(' ')[0]}</span>
                  </button>
                ))}
              </div>
            </div>
          )}
        </form>
      )}

      {step === 1 && recipient && (
        <form className="panel stack" onSubmit={review}>
          <RecipientCard recipient={recipient} onChange={() => setStep(0)} />
          <Field label="Amount in USD">
            <div className="amount-input">
              <span>$</span>
              <input inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} placeholder="0.00" required autoFocus />
            </div>
          </Field>
          <Field label="Note (optional)" hint="You both see it">
            <input value={note} onChange={e => setNote(e.target.value)} maxLength={140} placeholder="Lunch" />
          </Field>
          <button className="btn btn-primary">Review</button>
        </form>
      )}

      {step === 2 && recipient && (
        <div className="panel stack confirm">
          <p className="muted">You are sending</p>
          <div className="big num">{fmt(parseDollars(amount))}</div>
          <RecipientCard recipient={recipient} />
          {note.trim() && <p>“{note.trim()}”</p>}
          <div className="actions">
            <button className="btn" onClick={() => setStep(1)}>Change</button>
            <button className="btn btn-primary" onClick={send} disabled={busy}>{busy ? 'Sending' : 'Send money'}</button>
          </div>
        </div>
      )}
      <Notice kind="error">{error}</Notice>
    </div>
  );
}

function RecipientCard({ recipient, onChange }) {
  return (
    <div className="recipient">
      <Avatar name={recipient.displayName} />
      <div>
        <strong>{recipient.displayName}</strong>
        <div className="muted">{recipient.username ? `@${recipient.username}` : 'From your recent activity'}</div>
      </div>
      {onChange && <button type="button" className="link-button" onClick={onChange}>Change</button>}
    </div>
  );
}

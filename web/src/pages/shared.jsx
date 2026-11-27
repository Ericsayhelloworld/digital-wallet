import { CATEGORY, Tone } from '../components.jsx';
import { fmt, signed, timeOf } from '../format.js';

export const ENTRY_LABEL = {
  DEPOSIT: 'Added money',
  TRANSFER_OUT: 'Sent',
  TRANSFER_IN: 'Received',
  WITHDRAWAL: 'Withdrawal',
  WITHDRAWAL_REVERSAL: 'Withdrawal returned',
};

/** One line of the statement (WAL-5): what happened, with whom, the signed amount and the balance after it. */
export function EntryRow({ entry, showTime = true }) {
  const who = entry.counterparty
    ? (entry.type === 'TRANSFER_OUT' ? `to ${entry.counterparty.displayName}` : `from ${entry.counterparty.displayName}`)
    : '';
  const incoming = entry.amount >= 0;
  const category = CATEGORY[entry.type] || { icon: 'activity', tone: 'neutral' };
  return (
    <li className="row">
      <Tone tone={category.tone} icon={category.icon} />
      <div className="row-main">
        <div className="row-title">{ENTRY_LABEL[entry.type] || entry.type} {who}</div>
        <div className="row-sub">
          {showTime && timeOf(entry.createdAt)}
          {entry.note && <span className="row-note">“{entry.note}”</span>}
        </div>
      </div>
      <div className="row-end">
        <div className={`amount ${incoming ? 'in' : 'out'}`}>{signed(entry.amount)}</div>
        <div className="row-sub num">Balance {fmt(entry.balanceAfter)}</div>
      </div>
    </li>
  );
}

/** People the user recently sent money to or received money from, newest first, without duplicates. */
export function recentContacts(entries, max = 6) {
  const seen = new Map();
  for (const entry of entries) {
    if (entry.counterparty && !seen.has(entry.counterparty.walletId)) seen.set(entry.counterparty.walletId, entry.counterparty);
  }
  return [...seen.values()].slice(0, max);
}

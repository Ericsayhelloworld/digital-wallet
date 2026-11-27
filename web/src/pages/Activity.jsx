import { useCallback, useEffect, useState } from 'react';
import { api, messageFor } from '../api.js';
import { Empty, Notice } from '../components.jsx';
import { dayLabel } from '../format.js';
import { ENTRY_LABEL, EntryRow } from './shared.jsx';
import { OperationList } from './Move.jsx';

const FILTERS = [['', 'All'], ...Object.entries(ENTRY_LABEL)];

/** A list that pages with the cursor (API-5) and a "Load more" button (UI-2). */
function usePaged(path, deps) {
  const [items, setItems] = useState([]);
  const [cursor, setCursor] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState('');

  const load = useCallback(async (next) => {
    setLoading(true);
    try {
      const separator = path.includes('?') ? '&' : '?';
      const page = await api('GET', `${path}${next ? `${separator}cursor=${encodeURIComponent(next)}` : ''}`);
      setItems(list => (next ? [...list, ...page.items] : page.items));
      setCursor(page.nextCursor);
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setLoading(false);
    }
  }, [path]);

  useEffect(() => { load(null); }, [load, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
  return { items, cursor, loading, error, more: () => load(cursor) };
}

export default function Activity() {
  const [type, setType] = useState('');
  const statement = usePaged(`/api/v1/wallets/me/entries?limit=15${type ? `&type=${type}` : ''}`, [type]);
  const deposits = usePaged('/api/v1/deposits?limit=5', []);
  const withdrawals = usePaged('/api/v1/withdrawals?limit=5', []);

  // group the statement by day, newest first
  const groups = [];
  for (const entry of statement.items) {
    const label = dayLabel(entry.createdAt);
    if (!groups.length || groups[groups.length - 1].label !== label) groups.push({ label, entries: [] });
    groups[groups.length - 1].entries.push(entry);
  }

  return (
    <>
      <h1>Activity</h1>
      <div className="chips" role="group" aria-label="Filter by type">
        {FILTERS.map(([value, label]) => (
          <button key={value || 'all'} className={`chip ${type === value ? 'chip-on' : ''}`} aria-pressed={type === value}
                  onClick={() => setType(value)}>{label}</button>
        ))}
      </div>

      {!statement.loading && !statement.items.length && (
        <Empty title={type ? 'Nothing of this type yet' : 'No activity yet'}>Add money to get started.</Empty>
      )}
      {groups.map(group => (
        <section key={group.label} className="day">
          <h3 className="day-label">{group.label}</h3>
          <ul className="list">{group.entries.map(e => <EntryRow key={e.id} entry={e} />)}</ul>
        </section>
      ))}
      {statement.cursor && (
        <button className="btn more" onClick={statement.more} disabled={statement.loading}>
          {statement.loading ? 'Loading' : 'Load more'}
        </button>
      )}
      <Notice kind="error">{statement.error}</Notice>

      <div className="two-col">
        <section>
          <h2>Money added</h2>
          <OperationList items={deposits.items} kind="deposit" />
          {deposits.cursor && <button className="btn more" onClick={deposits.more}>Load more</button>}
        </section>
        <section>
          <h2>Withdrawals</h2>
          <OperationList items={withdrawals.items} kind="withdraw" />
          {withdrawals.cursor && <button className="btn more" onClick={withdrawals.more}>Load more</button>}
        </section>
      </div>
    </>
  );
}

import { useState } from 'react';
import { api, messageFor } from '../api.js';
import { Empty, Notice, Tone } from '../components.jsx';
import { timeAgo } from '../format.js';
import { usePoll } from '../hooks.js';

const KIND = {
  'transfer.completed': ['send', 'out'],
  'deposit.succeeded': ['add', 'add'],
  'deposit.failed': ['out', 'x'],
  'withdrawal.requested': ['withdraw', 'clock'],
  'withdrawal.succeeded': ['withdraw', 'bank'],
  'withdrawal.failed': ['return', 'undo'],
};

function toneFor(n) {
  if (n.type === 'transfer.completed' && n.text.startsWith('You received')) return ['add', 'in'];
  return KIND[n.type] || ['bell', 'bell'];
}

/** UI-2 / UI-5: the unread count, the list, mark one or all as read; new ones appear within 5 seconds. */
export default function Notifications({ onUnread }) {
  const [items, setItems] = useState([]);
  const [count, setCount] = useState(0);
  const [cursor, setCursor] = useState(null);
  const [pages, setPages] = useState(1);
  const [unreadOnly, setUnreadOnly] = useState(false);
  const [error, setError] = useState('');

  async function refresh() {
    try {
      const { count: n } = await api('GET', '/api/v1/notifications/unread-count');
      setCount(n);
      onUnread(n);
      if (pages <= 1) { // do not reset a list the user has paged through
        const page = await api('GET', `/api/v1/notifications?limit=20${unreadOnly ? '&unreadOnly=true' : ''}`);
        setItems(page.items);
        setCursor(page.nextCursor);
      }
    } catch (err) {
      setError(messageFor(err));
    }
  }

  usePoll(refresh, 3000, [unreadOnly, pages]);

  async function more() {
    const page = await api('GET', `/api/v1/notifications?limit=20${unreadOnly ? '&unreadOnly=true' : ''}&cursor=${encodeURIComponent(cursor)}`);
    setItems(list => [...list, ...page.items]);
    setCursor(page.nextCursor);
    setPages(p => p + 1);
  }

  async function act(path) {
    try {
      await api('POST', path);
      setPages(1);
      await refresh();
    } catch (err) {
      setError(messageFor(err));
    }
  }

  return (
    <>
      <div className="between">
        <h1>Notifications</h1>
        <button className="btn btn-small" onClick={() => act('/api/v1/notifications/read-all')} disabled={!count}>Mark all as read</button>
      </div>
      <div className="toolbar">
        <span className="muted">{count ? `${count} unread` : 'All caught up'}</span>
        <label className="switch">
          <input type="checkbox" checked={unreadOnly} onChange={e => { setUnreadOnly(e.target.checked); setPages(1); }} />
          <span className="switch-track" aria-hidden="true" />
          Unread only
        </label>
      </div>
      {!items.length && <Empty icon="bell" title={unreadOnly ? 'No unread notifications' : 'No notifications yet'}>Every movement of money shows up here.</Empty>}
      <ul className="list">
        {items.map(n => (
          <li key={n.id} className={`row ${n.read ? '' : 'unread'}`}>
            <Tone tone={toneFor(n)[0]} icon={toneFor(n)[1]} />
            <div className="row-main">
              <div className="row-title">{n.text}</div>
              <div className="row-sub">{timeAgo(n.createdAt)}</div>
            </div>
            <div className="row-end">
              {n.read
                ? <span className="row-sub">Read</span>
                : <button className="btn btn-small" onClick={() => act(`/api/v1/notifications/${n.id}/read`)}>Mark as read</button>}
            </div>
          </li>
        ))}
      </ul>
      {cursor && <button className="btn more" onClick={more}>Load more</button>}
      <Notice kind="error">{error}</Notice>
    </>
  );
}

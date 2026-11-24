'use strict';

// =====================================================================
// State
// UI-7: the access token is kept in sessionStorage (gone when the tab closes)
// =====================================================================
const state = { token: sessionStorage.getItem('accessToken'), me: null };
const app = document.getElementById('app');
const badge = document.createElement('span');
badge.className = 'badge';
badge.hidden = true;
let pageTimer = null;
let badgeTimer = null;

// =====================================================================
// DOM helper. Text is always added as text nodes, never as HTML (SEC-8).
// =====================================================================
function h(tag, props, ...children) {
  const el = document.createElement(tag);
  for (const [key, value] of Object.entries(props || {})) {
    if (value === null || value === undefined || value === false) continue;
    if (key === 'class') el.className = value;
    else if (key.startsWith('on')) el.addEventListener(key.slice(2), value);
    else if (key === 'value') el.value = value;
    else el.setAttribute(key, value === true ? '' : value);
  }
  for (const child of children.flat()) {
    if (child === null || child === undefined || child === false) continue;
    el.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return el;
}

// =====================================================================
// Money and dates
// =====================================================================
function fmt(cents) {
  const sign = cents < 0 ? '-' : '';
  const abs = Math.abs(cents);
  const dollars = Math.floor(abs / 100).toLocaleString('en-US');
  return `${sign}$${dollars}.${String(abs % 100).padStart(2, '0')}`;
}

function signed(cents) {
  return (cents > 0 ? '+' : '') + fmt(cents);
}

/** UI-6: the frontend checks only the format; limits and balances are checked by the backend. */
function parseDollars(text) {
  const s = text.trim().replace(/^\$/, '').replace(/,/g, '');
  if (!/^\d+(\.\d{1,2})?$/.test(s)) return null;
  const [dollars, cents = ''] = s.split('.');
  return Number(dollars) * 100 + Number((cents + '00').slice(0, 2));
}

function when(iso) {
  return iso ? new Date(iso).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '';
}

// =====================================================================
// API
// =====================================================================
class ApiError extends Error {
  constructor(code, detail, status, errors) {
    super(detail || code);
    this.code = code;
    this.status = status;
    this.errors = errors || [];
  }
}

async function api(method, path, body, idempotencyKey) {
  const headers = { Accept: 'application/json' };
  if (state.token) headers.Authorization = `Bearer ${state.token}`;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  if (idempotencyKey) headers['Idempotency-Key'] = idempotencyKey;

  let response;
  try {
    response = await fetch(path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (e) {
    throw new ApiError('NETWORK', 'Network error', 0);
  }
  const text = await response.text();
  let data = null;
  try { data = text ? JSON.parse(text) : null; } catch (e) { data = null; }

  // UI-7: a 401 leads back to the login page
  if (response.status === 401 && state.token && path !== '/api/v1/auth/login') {
    logout();
    throw new ApiError('UNAUTHENTICATED', 'Session ended', 401);
  }
  if (!response.ok) {
    throw new ApiError((data && data.code) || 'INTERNAL_ERROR', data && data.detail, response.status, data && data.errors);
  }
  return data;
}

/**
 * UI-4: every user action that moves money gets a NEW Idempotency-Key.
 * A retry of the same action (after a network error or 409 REQUEST_IN_PROGRESS) sends the SAME key again,
 * so the server never executes it twice.
 */
async function moneyRequest(path, body) {
  const key = crypto.randomUUID();
  for (let attempt = 1; ; attempt++) {
    try {
      return await api('POST', path, body, key);
    } catch (e) {
      const retry = (e.code === 'NETWORK' || e.code === 'REQUEST_IN_PROGRESS') && attempt < 4;
      if (!retry) throw e;
      await new Promise(resolve => setTimeout(resolve, 1000 * attempt));
    }
  }
}

// UI-6: a message for every error code of Section 6
const MESSAGES = {
  VALIDATION_FAILED: 'Some fields are not valid.',
  USERNAME_TAKEN: 'That username is already taken. Choose another one.',
  INVALID_CREDENTIALS: 'The username or password is wrong.',
  RATE_LIMITED: 'Too many login attempts. Wait a minute and try again.',
  UNAUTHENTICATED: 'Your session has ended. Log in again.',
  FORBIDDEN: 'You do not have access to this.',
  NOT_FOUND: 'This item does not exist.',
  WALLET_NOT_FOUND: 'No wallet was found for that username.',
  RECIPIENT_NOT_FOUND: 'The recipient wallet does not exist.',
  SAME_WALLET: 'You cannot send money to your own wallet.',
  INSUFFICIENT_FUNDS: 'Your balance is too low for this amount.',
  DAILY_LIMIT_EXCEEDED: 'This would go over your daily sending limit.',
  IDEMPOTENCY_KEY_REQUIRED: 'The request was incomplete. Try again.',
  IDEMPOTENCY_KEY_REUSED: 'This request conflicts with an earlier one. Start again.',
  REQUEST_IN_PROGRESS: 'This request is still being processed. Wait a moment and check your activity.',
  INTERNAL_ERROR: 'Something went wrong on the server. Try again.',
  NETWORK: 'The server cannot be reached. Check your connection and try again.',
};

function messageFor(error) {
  const base = MESSAGES[error.code] || error.message;
  if (error.errors && error.errors.length) {
    return base + ' ' + error.errors.map(e => `${e.field} ${e.message}`).join('; ') + '.';
  }
  return base;
}

function notice(kind, text) {
  return h('p', { class: `notice notice-${kind}`, role: kind === 'error' ? 'alert' : 'status' }, text);
}

function field(label, input, hint) {
  return h('label', { class: 'field' }, h('span', {}, label), input, hint ? h('small', { class: 'muted' }, hint) : null);
}

// =====================================================================
// Session
// =====================================================================
async function login(username, password) {
  const result = await api('POST', '/api/v1/auth/login', { username, password });
  state.token = result.accessToken;
  sessionStorage.setItem('accessToken', state.token);
  state.me = await api('GET', '/api/v1/auth/me');
}

function logout() {
  state.token = null;
  state.me = null;
  sessionStorage.removeItem('accessToken');
  clearInterval(badgeTimer);
  badgeTimer = null;
  go('login');
}

/** UI-5: the unread count updates every 3 seconds without a reload. */
function startBadge() {
  if (badgeTimer) return;
  const update = async () => {
    try {
      const { count } = await api('GET', '/api/v1/notifications/unread-count');
      badge.textContent = count;
      badge.hidden = count === 0;
      badge.setAttribute('aria-label', `${count} unread`);
    } catch (e) { /* shown on next success */ }
  };
  update();
  badgeTimer = setInterval(update, 3000);
}

function poll(fn, ms) {
  clearInterval(pageTimer);
  pageTimer = setInterval(fn, ms);
}

function stopPoll() {
  clearInterval(pageTimer);
  pageTimer = null;
}

// =====================================================================
// Router
// =====================================================================
const NAV = [
  ['home', 'Home'],
  ['send', 'Send'],
  ['deposit', 'Add money'],
  ['withdraw', 'Withdraw'],
  ['activity', 'Activity'],
  ['notifications', 'Notifications'],
];

const PAGES = {
  login: renderLogin,
  signup: renderSignup,
  home: renderHome,
  send: renderSend,
  deposit: main => renderMove(main, 'deposit'),
  withdraw: main => renderMove(main, 'withdraw'),
  activity: renderActivity,
  notifications: renderNotifications,
};

function go(route) {
  if (location.hash === `#/${route}`) render();
  else location.hash = `#/${route}`;
}

async function render() {
  stopPoll();
  let route = location.hash.replace(/^#\//, '');
  const publicRoute = route === 'login' || route === 'signup';
  if (!state.token && !publicRoute) return go('login');
  if (state.token && (publicRoute || !PAGES[route])) return go('home');

  const main = h('main', { class: 'page', id: 'main' });
  if (!state.token) {
    app.replaceChildren(h('div', { class: 'auth' }, main));
  } else {
    if (!state.me) {
      try { state.me = await api('GET', '/api/v1/auth/me'); } catch (e) { return; }
    }
    startBadge();
    app.replaceChildren(h('div', { class: 'shell' },
      h('aside', { class: 'side' },
        h('div', { class: 'brand' }, 'Wallet'),
        h('nav', { class: 'nav', 'aria-label': 'Main' },
          NAV.map(([r, label]) => h('a', {
            href: `#/${r}`,
            class: r === route ? 'active' : null,
            'aria-current': r === route ? 'page' : null,
          }, label, r === 'notifications' ? badge : null))),
        h('div', { class: 'who' },
          h('strong', {}, state.me.displayName),
          h('span', { class: 'muted' }, `@${state.me.username}`), ' ',
          h('button', { class: 'btn-link', onclick: logout }, 'Log out'))),
      main));
  }
  await PAGES[route](main);
}

window.addEventListener('hashchange', render);

// =====================================================================
// Pages: log in and sign up (UI-2)
// =====================================================================
function renderLogin(main) {
  const message = h('div');
  const username = h('input', { name: 'username', autocomplete: 'username', required: true, autocapitalize: 'none' });
  const password = h('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const button = h('button', { type: 'submit', class: 'btn btn-primary' }, 'Log in');
  main.append(
    h('h1', {}, 'Log in'),
    h('form', {
      class: 'stack',
      onsubmit: async e => {
        e.preventDefault();
        message.replaceChildren();
        button.disabled = true;
        try {
          await login(username.value.trim(), password.value);
          go('home');
        } catch (err) {
          message.replaceChildren(notice('error', messageFor(err)));
        } finally {
          button.disabled = false;
        }
      },
    }, field('Username', username), field('Password', password), button, message),
    h('p', { class: 'muted', style: 'margin-top:1.25rem' }, 'New here? ', h('a', { href: '#/signup' }, 'Create an account')));
}

function renderSignup(main) {
  const message = h('div');
  const username = h('input', { autocomplete: 'username', required: true, autocapitalize: 'none' });
  const displayName = h('input', { autocomplete: 'name', required: true });
  const password = h('input', { type: 'password', autocomplete: 'new-password', required: true });
  const button = h('button', { type: 'submit', class: 'btn btn-primary' }, 'Create account');
  main.append(
    h('h1', {}, 'Create an account'),
    h('form', {
      class: 'stack',
      onsubmit: async e => {
        e.preventDefault();
        message.replaceChildren();
        button.disabled = true;
        try {
          await api('POST', '/api/v1/auth/register', {
            username: username.value.trim(), password: password.value, displayName: displayName.value.trim(),
          });
          await login(username.value.trim(), password.value);
          go('home');
        } catch (err) {
          message.replaceChildren(notice('error', messageFor(err)));
        } finally {
          button.disabled = false;
        }
      },
    },
    field('Username', username, '3 to 20 characters: lowercase letters, digits and _'),
    field('Your name', displayName, 'Shown to people you send money to'),
    field('Password', password, 'At least 8 characters'),
    button, message),
    h('p', { class: 'muted', style: 'margin-top:1.25rem' }, 'Already have an account? ', h('a', { href: '#/login' }, 'Log in')));
}

// =====================================================================
// Home (UI-2): open the wallet, then the balance card
// =====================================================================
async function renderHome(main) {
  main.append(h('h1', {}, 'Your wallet'));
  const box = h('div');
  main.append(box);
  let showingOpen = false;

  const load = async () => {
    try {
      const wallet = await api('GET', '/api/v1/wallets/me');
      showingOpen = false;
      box.replaceChildren(walletCard(wallet));
    } catch (err) {
      if (err.code === 'WALLET_NOT_FOUND') {
        if (!showingOpen) box.replaceChildren(openWalletPanel(load));
        showingOpen = true;
      } else {
        box.replaceChildren(notice('error', messageFor(err)));
      }
    }
  };
  await load();
  poll(load, 3000);
}

function openWalletPanel(onOpened) {
  const message = h('div');
  const button = h('button', {
    class: 'btn btn-primary',
    onclick: async () => {
      button.disabled = true;
      try {
        await api('POST', '/api/v1/wallets');
        await onOpened();
      } catch (err) {
        message.replaceChildren(notice('error', messageFor(err)));
        button.disabled = false;
      }
    },
  }, 'Open my wallet');
  return h('div', { class: 'panel stack' },
    h('p', {}, 'You do not have a wallet yet. Open one to add, send and withdraw money.'),
    button, message);
}

function walletCard(w) {
  const percent = w.dailyLimit > 0 ? Math.min(100, (w.sentToday / w.dailyLimit) * 100) : 0;
  return h('div', {},
    h('section', { class: 'wallet-card', 'aria-label': 'Wallet' },
      h('p', { class: 'label' }, 'Balance'),
      h('p', { class: 'balance' }, fmt(w.balance)),
      w.reserved > 0 ? h('p', { class: 'reserved' }, `${fmt(w.reserved)} reserved for withdrawals in progress`) : null,
      h('div', { class: 'limit-text' },
        h('span', {}, 'Sent today'),
        h('span', { class: 'num' }, `${fmt(w.sentToday)} of ${fmt(w.dailyLimit)}`)),
      h('div', { class: 'bar', role: 'progressbar', 'aria-valuemin': 0, 'aria-valuemax': 100, 'aria-valuenow': Math.round(percent) },
        h('div', { class: 'bar-fill', style: `width:${percent}%` })),
      h('dl', { class: 'facts' },
        h('dt', {}, 'Reserved'), h('dd', { class: 'num' }, fmt(w.reserved)),
        h('dt', {}, 'Username'), h('dd', {}, `@${w.username}`),
        h('dt', {}, 'Wallet ID'), h('dd', {}, w.id))),
    h('div', { class: 'actions' },
      h('a', { class: 'btn btn-primary', href: '#/send' }, 'Send money'),
      h('a', { class: 'btn', href: '#/deposit' }, 'Add money'),
      h('a', { class: 'btn', href: '#/withdraw' }, 'Withdraw')));
}

// =====================================================================
// Send (UI-2): look up, show the name, amount and note, confirm, result
// =====================================================================
function renderSend(main) {
  main.append(h('h1', {}, 'Send money'));
  const message = h('div', { 'aria-live': 'polite' });
  const username = h('input', { autocomplete: 'off', autocapitalize: 'none', required: true });
  const findButton = h('button', { type: 'submit', class: 'btn' }, 'Find');
  const step2 = h('div', { class: 'stack' });
  let recipient = null;

  const findForm = h('form', {
    class: 'row',
    onsubmit: async e => {
      e.preventDefault();
      message.replaceChildren();
      step2.replaceChildren();
      findButton.disabled = true;
      try {
        recipient = await api('GET', `/api/v1/wallets/lookup?username=${encodeURIComponent(username.value.trim())}`);
        step2.replaceChildren(amountStep());
      } catch (err) {
        message.replaceChildren(notice('error', messageFor(err)));
      } finally {
        findButton.disabled = false;
      }
    },
  }, field('Recipient username', username), findButton);

  function recipientLine() {
    return h('div', { class: 'recipient' },
      h('div', { class: 'avatar', 'aria-hidden': 'true' }, recipient.displayName.trim().charAt(0).toUpperCase() || '?'),
      h('div', {}, h('div', { class: 'title' }, recipient.displayName), h('div', { class: 'muted' }, `@${recipient.username}`)));
  }

  function amountStep() {
    const amount = h('input', { inputmode: 'decimal', placeholder: '0.00', required: true });
    const note = h('input', { maxlength: '140' });
    return h('form', {
      class: 'stack',
      onsubmit: e => {
        e.preventDefault();
        message.replaceChildren();
        const cents = parseDollars(amount.value);
        if (cents === null) {
          message.replaceChildren(notice('error', 'Enter an amount like 12.50'));
          return;
        }
        step2.replaceChildren(confirmStep(cents, note.value.trim()));
      },
    }, h('div', { class: 'panel' }, recipientLine()),
       field('Amount in USD', amount), field('Note (optional)', note, 'Both of you will see it'),
       h('button', { type: 'submit', class: 'btn btn-primary' }, 'Review'));
  }

  function confirmStep(cents, note) {
    const sendButton = h('button', { class: 'btn btn-primary' }, 'Send money');
    sendButton.addEventListener('click', async () => {
      sendButton.disabled = true;
      message.replaceChildren();
      try {
        const transfer = await moneyRequest('/api/v1/transfers', {
          toWalletId: recipient.walletId, amount: cents, note: note || null,
        });
        step2.replaceChildren(h('div', { class: 'panel stack flash' },
          h('p', { class: 'big' }, fmt(transfer.amount)),
          notice('ok', `Sent to ${recipient.displayName}.`),
          transfer.note ? h('p', { class: 'muted' }, `Note: ${transfer.note}`) : null,
          h('div', { class: 'actions' },
            h('a', { class: 'btn', href: '#/home' }, 'See balance'),
            h('a', { class: 'btn', href: '#/activity' }, 'See activity'))));
        username.value = '';
      } catch (err) {
        message.replaceChildren(notice('error', messageFor(err)));
        sendButton.disabled = false;
      }
    });
    return h('div', { class: 'panel stack' },
      h('p', { class: 'muted' }, 'You are sending'),
      h('p', { class: 'big' }, fmt(cents)),
      recipientLine(),
      note ? h('p', {}, `Note: ${note}`) : null,
      h('div', { class: 'actions' },
        sendButton,
        h('button', { class: 'btn', onclick: () => step2.replaceChildren(amountStep()) }, 'Change')));
  }

  main.append(findForm, h('div', { style: 'margin-top:1rem' }, message), h('div', { style: 'margin-top:1rem' }, step2));
}

// =====================================================================
// Add money and Withdraw (UI-2, UI-5): the status is shown until it is final
// =====================================================================
const MOVES = {
  deposit: {
    title: 'Add money', path: '/api/v1/deposits', button: 'Add money',
    intro: 'Money comes from your bank account. It appears in your wallet when the bank confirms it.',
    final: { SUCCEEDED: 'The money is in your wallet.', FAILED: 'The bank did not send the money.' },
  },
  withdraw: {
    title: 'Withdraw', path: '/api/v1/withdrawals', button: 'Withdraw',
    intro: 'Money goes to your bank account. It is reserved until the bank confirms the payout.',
    final: { SUCCEEDED: 'The money is on its way to your bank account.', FAILED: 'The bank refused the payout. The money is back in your wallet.' },
  },
};

const STATUS_LABEL = { PENDING: 'In progress', SUCCEEDED: 'Complete', FAILED: 'Failed', COMPLETED: 'Complete' };

function statusTag(status) {
  return h('span', { class: `status status-${status}` }, STATUS_LABEL[status] || status);
}

async function renderMove(main, kind) {
  const cfg = MOVES[kind];
  main.append(h('h1', {}, cfg.title), h('p', { class: 'muted' }, cfg.intro));
  const message = h('div', { 'aria-live': 'polite' });
  const result = h('div', { 'aria-live': 'polite' });
  const amount = h('input', { inputmode: 'decimal', placeholder: '0.00', required: true });
  const button = h('button', { type: 'submit', class: 'btn btn-primary' }, cfg.button);
  const recent = h('div');

  const loadRecent = async () => {
    try {
      const page = await api('GET', `${cfg.path}?limit=5`);
      recent.replaceChildren(h('h2', {}, 'Recent'), operationList(page.items, kind));
    } catch (err) { /* not essential */ }
  };

  const form = h('form', {
    class: 'stack',
    onsubmit: async e => {
      e.preventDefault();
      message.replaceChildren();
      const cents = parseDollars(amount.value);
      if (cents === null) {
        message.replaceChildren(notice('error', 'Enter an amount like 100.00'));
        return;
      }
      button.disabled = true;
      try {
        const op = await moneyRequest(cfg.path, { amount: cents });
        amount.value = '';
        showOperation(op);
        // UI-5: poll until the status is final
        poll(async () => {
          try {
            const current = await api('GET', `${cfg.path}/${op.id}`);
            showOperation(current);
            if (current.status !== 'PENDING') {
              stopPoll();
              loadRecent();
            }
          } catch (err) { /* keep polling */ }
        }, 1000);
        loadRecent();
      } catch (err) {
        message.replaceChildren(notice('error', messageFor(err)));
      } finally {
        button.disabled = false;
      }
    },
  }, field('Amount in USD', amount), button, message);

  function showOperation(op) {
    result.replaceChildren(h('div', { class: 'panel stack flash', style: 'margin-top:1.5rem' },
      h('div', { class: 'row', style: 'justify-content:space-between;align-items:center' },
        h('p', { class: 'big' }, fmt(op.amount)), statusTag(op.status)),
      op.status === 'PENDING'
        ? h('p', { class: 'muted' }, 'Waiting for the bank. This page updates by itself.')
        : notice(op.status === 'SUCCEEDED' ? 'ok' : 'error',
            cfg.final[op.status] + (op.failureReason ? ` Reason: ${op.failureReason}.` : ''))));
  }

  main.append(form, result, recent);
  await loadRecent();
}

function operationList(items, kind) {
  if (!items.length) {
    return h('div', { class: 'list' }, h('p', { class: 'empty' }, kind === 'deposit' ? 'No money added yet.' : 'No withdrawals yet.'));
  }
  return h('ul', { class: 'list' }, items.map(op => h('li', {},
    h('div', {}, h('div', { class: 'title' }, kind === 'deposit' ? 'Added money' : 'Withdrawal'),
      h('div', { class: 'sub' }, when(op.createdAt), op.failureReason ? `, ${op.failureReason}` : '')),
    h('div', { class: 'right' }, h('div', { class: 'amount' }, fmt(op.amount)), statusTag(op.status)))));
}

// =====================================================================
// Activity (UI-2): statement with running balances and "Load more"
// =====================================================================
const ENTRY_LABEL = {
  DEPOSIT: 'Added money',
  TRANSFER_OUT: 'Sent',
  TRANSFER_IN: 'Received',
  WITHDRAWAL: 'Withdrawal',
  WITHDRAWAL_REVERSAL: 'Withdrawal returned',
};

/** A list that loads page after page with the cursor (API-5). */
function pagedList(fetchPage, renderItem, emptyText) {
  const list = h('ul', { class: 'list' });
  const more = h('button', { class: 'btn more', hidden: true }, 'Load more');
  const message = h('div');
  let cursor = null;

  const load = async () => {
    more.disabled = true;
    try {
      const page = await fetchPage(cursor);
      if (!cursor && !page.items.length) list.replaceChildren(h('li', { class: 'empty' }, emptyText));
      page.items.forEach(item => list.append(renderItem(item)));
      cursor = page.nextCursor;
      more.hidden = !cursor;
    } catch (err) {
      message.replaceChildren(notice('error', messageFor(err)));
    } finally {
      more.disabled = false;
    }
  };
  more.addEventListener('click', load);
  load();
  return h('div', {}, list, more, message);
}

function renderActivity(main) {
  main.append(h('h1', {}, 'Activity'));
  const filter = h('select', { 'aria-label': 'Filter by type' },
    h('option', { value: '' }, 'All entries'),
    Object.entries(ENTRY_LABEL).map(([value, label]) => h('option', { value }, label)));
  const statement = h('div');

  const loadStatement = () => {
    const type = filter.value;
    statement.replaceChildren(pagedList(
      cursor => api('GET', `/api/v1/wallets/me/entries?limit=10${type ? `&type=${type}` : ''}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`),
      entryItem,
      'No entries yet. Add money to get started.'));
  };
  filter.addEventListener('change', loadStatement);

  const opsPage = (path) => cursor => api('GET', `${path}?limit=5${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);

  main.append(
    h('div', { class: 'toolbar' }, h('h2', { style: 'margin:0' }, 'Statement'), h('span', { class: 'spacer' }), filter),
    statement,
    h('h2', {}, 'Money added'),
    pagedList(opsPage('/api/v1/deposits'), op => operationList([op], 'deposit').firstChild, 'No money added yet.'),
    h('h2', {}, 'Withdrawals'),
    pagedList(opsPage('/api/v1/withdrawals'), op => operationList([op], 'withdraw').firstChild, 'No withdrawals yet.'));
  loadStatement();
}

function entryItem(e) {
  const who = e.counterparty
    ? (e.type === 'TRANSFER_OUT' ? `to ${e.counterparty.displayName}` : `from ${e.counterparty.displayName}`)
    : '';
  return h('li', {},
    h('div', {},
      h('div', { class: 'title' }, ENTRY_LABEL[e.type] || e.type, who ? ` ${who}` : ''),
      h('div', { class: 'sub' }, when(e.createdAt))),
    h('div', { class: 'right' },
      h('div', { class: `amount ${e.amount >= 0 ? 'in' : 'out'}` }, signed(e.amount)),
      h('div', { class: 'sub num' }, `Balance ${fmt(e.balanceAfter)}`)),
    e.note ? h('div', { class: 'note' }, `“${e.note}”`) : null);
}

// =====================================================================
// Notifications (UI-2, UI-5)
// =====================================================================
function renderNotifications(main) {
  main.append(h('h1', {}, 'Notifications'));
  const unreadOnly = h('input', { type: 'checkbox' });
  const count = h('span', { class: 'muted' });
  const message = h('div');
  const list = h('ul', { class: 'list' });
  const more = h('button', { class: 'btn more', hidden: true }, 'Load more');
  let cursor = null;
  let pagesLoaded = 0;

  const markAll = h('button', {
    class: 'btn btn-small',
    onclick: async () => {
      try {
        await api('POST', '/api/v1/notifications/read-all');
        await refresh();
      } catch (err) { message.replaceChildren(notice('error', messageFor(err))); }
    },
  }, 'Mark all as read');

  function item(n) {
    const li = h('li', { class: n.read ? null : 'unread' },
      h('div', {}, h('div', { class: 'title' }, n.text), h('div', { class: 'sub' }, when(n.createdAt))),
      h('div', { class: 'right' }, n.read ? h('span', { class: 'sub' }, 'Read') : h('button', {
        class: 'btn btn-small',
        onclick: async () => {
          try {
            await api('POST', `/api/v1/notifications/${n.id}/read`);
            await refresh();
          } catch (err) { message.replaceChildren(notice('error', messageFor(err))); }
        },
      }, 'Mark as read')));
    return li;
  }

  async function loadPage(reset) {
    try {
      if (reset) { cursor = null; pagesLoaded = 0; }
      const page = await api('GET', `/api/v1/notifications?limit=20${unreadOnly.checked ? '&unreadOnly=true' : ''}${cursor ? `&cursor=${encodeURIComponent(cursor)}` : ''}`);
      if (reset) list.replaceChildren();
      if (reset && !page.items.length) {
        list.append(h('li', { class: 'empty' }, unreadOnly.checked ? 'No unread notifications.' : 'No notifications yet.'));
      }
      page.items.forEach(n => list.append(item(n)));
      cursor = page.nextCursor;
      pagesLoaded++;
      more.hidden = !cursor;
    } catch (err) {
      message.replaceChildren(notice('error', messageFor(err)));
    }
  }

  async function refresh() {
    try {
      const { count: n } = await api('GET', '/api/v1/notifications/unread-count');
      count.textContent = `${n} unread`;
      badge.textContent = n;
      badge.hidden = n === 0;
    } catch (err) { /* ignore */ }
    if (pagesLoaded <= 1) await loadPage(true); // do not reset a list the user has paged through
  }

  unreadOnly.addEventListener('change', () => loadPage(true).then(refresh));
  more.addEventListener('click', () => loadPage(false));

  main.append(
    h('div', { class: 'toolbar' }, count, h('span', { class: 'spacer' }),
      h('label', { class: 'row', style: 'align-items:center;gap:0.4rem;font-weight:600' }, unreadOnly, 'Unread only'),
      markAll),
    message, list, more);
  refresh();
  poll(refresh, 3000); // UI-5: new notifications appear within 5 seconds
}

// =====================================================================
render();

// UI-7: the access token is kept in sessionStorage, so every browser tab has its own login.
let token = sessionStorage.getItem('accessToken');
let unauthorizedHandler = () => {};

export const getToken = () => token;

export function setToken(value) {
  token = value;
  if (value) {
    sessionStorage.setItem('accessToken', value);
  } else {
    sessionStorage.removeItem('accessToken');
    sessionStorage.removeItem('lastMoney');
  }
}

export function onUnauthorized(handler) {
  unauthorizedHandler = handler;
}

export class ApiError extends Error {
  constructor(code, detail, status, errors) {
    super(detail || code);
    this.code = code;
    this.status = status;
    this.errors = errors || [];
  }
}

export async function api(method, path, body, idempotencyKey) {
  const headers = { Accept: 'application/json' };
  if (token) headers.Authorization = `Bearer ${token}`;
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
  if (response.status === 401 && token && path !== '/api/v1/auth/login') {
    unauthorizedHandler();
    throw new ApiError('UNAUTHENTICATED', 'Session ended', 401);
  }
  if (!response.ok) {
    throw new ApiError((data && data.code) || 'INTERNAL_ERROR', data && data.detail, response.status, data && data.errors);
  }
  return data;
}

export function getLastMoney() {
  try {
    return JSON.parse(sessionStorage.getItem('lastMoney') || 'null');
  } catch (e) {
    return null;
  }
}

/**
 * UI-4: every action that moves money gets a NEW Idempotency-Key; a retry of the same action
 * (after a network error or 409 REQUEST_IN_PROGRESS) sends the SAME key again.
 */
export async function moneyRequest(path, body) {
  const key = crypto.randomUUID();
  sessionStorage.setItem('lastMoney', JSON.stringify({ path, body, key })); // for the admin Diagnostics
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

/** Diagnostics: a POST that returns the status code and the Idempotent-Replayed header. */
export async function rawMoneyPost(request) {
  try {
    const response = await fetch(request.path, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json',
        Authorization: `Bearer ${token}`,
        'Idempotency-Key': request.key,
      },
      body: JSON.stringify(request.body),
    });
    return { status: response.status, replayed: response.headers.get('Idempotent-Replayed') === 'true' };
  } catch (e) {
    return { status: 0, replayed: false };
  }
}

// UI-6: a message for every error code of Section 6
export const MESSAGES = {
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
  REQUEST_IN_PROGRESS: 'This request is still being processed. Check your activity in a moment.',
  INTERNAL_ERROR: 'Something went wrong on the server. Try again.',
  NETWORK: 'The server cannot be reached. Check your connection and try again.',
};

export function messageFor(error) {
  const base = MESSAGES[error.code] || error.message;
  if (error.errors && error.errors.length) {
    return `${base} ${error.errors.map(e => `${e.field} ${e.message}`).join('; ')}.`;
  }
  return base;
}

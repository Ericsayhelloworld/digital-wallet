import { useState } from 'react';
import { api, messageFor } from '../api.js';
import { Field, Notice } from '../components.jsx';
import { navigate } from '../hooks.js';
import { useSession } from '../session.js';

export function Login() {
  const { login } = useSession();
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);

  async function submit(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await login(username.trim(), password);
      navigate('home');
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="stack" onSubmit={submit}>
      <h1>Welcome back</h1>
      <p className="muted">Log in to your wallet.</p>
      <Field label="Username">
        <input value={username} onChange={e => setUsername(e.target.value)} autoComplete="username" autoCapitalize="none" required />
      </Field>
      <Field label="Password">
        <input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" required />
      </Field>
      <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Logging in' : 'Log in'}</button>
      <Notice kind="error">{error}</Notice>
      <p className="muted center">New here? <a href="#/signup">Create an account</a></p>
    </form>
  );
}

export function Signup() {
  const { login } = useSession();
  const [form, setForm] = useState({ username: '', displayName: '', password: '' });
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const set = key => e => setForm({ ...form, [key]: e.target.value });

  async function submit(e) {
    e.preventDefault();
    setError('');
    setBusy(true);
    try {
      await api('POST', '/api/v1/auth/register', {
        username: form.username.trim(), password: form.password, displayName: form.displayName.trim(),
      });
      await login(form.username.trim(), form.password);
      navigate('home');
    } catch (err) {
      setError(messageFor(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="stack" onSubmit={submit}>
      <h1>Open your account</h1>
      <p className="muted">It takes a minute. No fees.</p>
      <Field label="Username" hint="3 to 20 characters: lowercase letters, digits and _">
        <input value={form.username} onChange={set('username')} autoComplete="username" autoCapitalize="none" required />
      </Field>
      <Field label="Your name" hint="Shown to people you send money to">
        <input value={form.displayName} onChange={set('displayName')} autoComplete="name" required />
      </Field>
      <Field label="Password" hint="At least 8 characters">
        <input type="password" value={form.password} onChange={set('password')} autoComplete="new-password" required />
      </Field>
      <button className="btn btn-primary btn-block" disabled={busy}>{busy ? 'Creating account' : 'Create account'}</button>
      <Notice kind="error">{error}</Notice>
      <p className="muted center">Already have an account? <a href="#/login">Log in</a></p>
    </form>
  );
}

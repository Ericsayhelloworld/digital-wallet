import { createContext, useCallback, useContext, useMemo, useRef, useState } from 'react';
import { initials } from './format.js';

// ------------------------------------------------------------------ icons (stroke, 24 x 24)
const PATHS = {
  home: 'M3 10.5 12 3l9 7.5V20a1 1 0 0 1-1 1h-5v-6H9v6H4a1 1 0 0 1-1-1z',
  send: 'M21 3 10 14M21 3l-7 18-4-7-7-4z',
  add: 'M12 5v14M5 12h14',
  withdraw: 'M12 3v12M7 10l5 5 5-5M4 21h16',
  activity: 'M4 6h16M4 12h16M4 18h10',
  bell: 'M18 9a6 6 0 1 0-12 0c0 6-3 8-3 8h18s-3-2-3-8M10.5 20.5a2 2 0 0 0 3 0',
  shield: 'M12 21s8-3.5 8-10V5.5L12 3 4 5.5V11c0 6.5 8 10 8 10z',
  logout: 'M15 4h3a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-3M10 16l-4-4 4-4M6 12h11',
  check: 'M20 6 9 17l-5-5',
  x: 'M18 6 6 18M6 6l12 12',
  out: 'M7 17 17 7M8 7h9v9',
  in: 'M17 7 7 17M16 17H7V8',
  bank: 'M3 10h18M5 10v8M9.5 10v8M14.5 10v8M19 10v8M3 21h18M12 3l9 5H3z',
  refresh: 'M20 12a8 8 0 1 1-2.3-5.7L20 8.5M20 3.5v5h-5',
  copy: 'M9 9h10v10H9zM5 15V5h10',
  search: 'M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14zM20 20l-4-4',
  reserve: 'M6 11V8a6 6 0 0 1 12 0v3M5 11h14v10H5z',
  undo: 'M9 14 4 9l5-5M4 9h11a5 5 0 0 1 0 10h-3',
  link: 'M10 14a4 4 0 0 0 6 0l3-3a4 4 0 0 0-6-6l-1 1M14 10a4 4 0 0 0-6 0l-3 3a4 4 0 0 0 6 6l1-1',
  lock: 'M7 11V8a5 5 0 0 1 10 0v3M5 11h14v10H5zM12 15v2',
  bolt: 'M13 2 4 14h7l-1 8 9-12h-7z',
  ledger: 'M5 4h11a3 3 0 0 1 3 3v13H8a3 3 0 0 1-3-3zM5 17a3 3 0 0 1 3-3h11M9 8h6',
  trendUp: 'M3 17l6-6 4 4 8-8M15 7h6v6',
  trendDown: 'M3 7l6 6 4-4 8 8M15 17h6v-6',
  clock: 'M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 7v5l3 2',
};

export function Icon({ name, size = 18, className }) {
  return (
    <svg className={className} width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name]} />
    </svg>
  );
}

// ------------------------------------------------------------------ brand
export const BRAND = 'Tally';

/** Tally marks crossed by a stroke: counting, the ledger. */
export function Logo({ size = 34 }) {
  return (
    <svg className="logo" width={size} height={size} viewBox="0 0 40 40" aria-hidden="true">
      <rect width="40" height="40" rx="11" className="logo-tile" />
      <path d="M11 11v18M17 11v18M23 11v18M29 11v18" className="logo-marks" />
      <path d="M8 27 32 13" className="logo-cross" />
    </svg>
  );
}

/** Every kind of money movement has its own colour and icon, used everywhere in the app. */
export const CATEGORY = {
  DEPOSIT: { icon: 'add', tone: 'add' },
  TRANSFER_OUT: { icon: 'out', tone: 'send' },
  TRANSFER_IN: { icon: 'in', tone: 'add' },
  WITHDRAWAL: { icon: 'bank', tone: 'withdraw' },
  WITHDRAWAL_REVERSAL: { icon: 'undo', tone: 'return' },
};

export function Tone({ tone, icon, size = 40 }) {
  return (
    <span className={`tone tone-${tone}`} style={{ width: size, height: size }}>
      <Icon name={icon} size={Math.round(size * 0.48)} />
    </span>
  );
}

// ------------------------------------------------------------------ toasts
const ToastContext = createContext(() => {});
export const useToast = () => useContext(ToastContext);

export function ToastProvider({ children }) {
  const [toasts, setToasts] = useState([]);
  const counter = useRef(0);
  const push = useCallback((kind, text) => {
    const id = ++counter.current;
    setToasts(list => [...list.slice(-3), { id, kind, text }]);
    setTimeout(() => setToasts(list => list.filter(t => t.id !== id)), 4500);
  }, []);
  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="toasts" role="status" aria-live="polite">
        {toasts.map(t => (
          <div key={t.id} className={`toast toast-${t.kind}`}>
            <Icon name={t.kind === 'error' ? 'x' : 'check'} size={16} />
            <span>{t.text}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
  );
}

// ------------------------------------------------------------------ small pieces
export function Notice({ kind = 'info', children }) {
  if (!children) return null;
  return <p className={`notice notice-${kind}`} role={kind === 'error' ? 'alert' : 'status'}>{children}</p>;
}

export function Field({ label, hint, children }) {
  return (
    <label className="field">
      <span className="field-label">{label}</span>
      {children}
      {hint && <span className="field-hint">{hint}</span>}
    </label>
  );
}

export function Avatar({ name, size = 40 }) {
  // a stable hue per name, so the same person always has the same colour
  const hue = [...(name || '')].reduce((sum, ch) => sum + ch.charCodeAt(0), 0) % 360;
  return (
    <span className="avatar" style={{ width: size, height: size, '--hue': hue, fontSize: size * 0.38 }} aria-hidden="true">
      {initials(name)}
    </span>
  );
}

const STATUS_LABEL = { PENDING: 'In progress', SUCCEEDED: 'Complete', FAILED: 'Failed', COMPLETED: 'Complete' };

export function StatusPill({ status }) {
  return <span className={`pill pill-${status}`}>{STATUS_LABEL[status] || status}</span>;
}

export function Empty({ icon = 'activity', title, children }) {
  return (
    <div className="empty">
      <span className="empty-icon"><Icon name={icon} size={22} /></span>
      <strong>{title}</strong>
      {children && <p>{children}</p>}
    </div>
  );
}

// ------------------------------------------------------------------ the banknote pattern (guilloche)
/** Fine interlaced wave lines like the security print on a banknote. */
export function Guilloche({ width = 640, height = 300, lines = 22 }) {
  const paths = useMemo(() => {
    const out = [];
    for (let i = 0; i < lines; i++) {
      const amplitude = 18 + (i % 5) * 7;
      const frequency = 0.012 + (i % 4) * 0.003;
      const phase = i * 0.55;
      const base = (height / (lines + 1)) * (i + 1);
      let d = '';
      for (let x = -10; x <= width + 10; x += 6) {
        const y = base + amplitude * Math.sin(x * frequency + phase) + 6 * Math.sin(x * 0.05 + i);
        d += `${x === -10 ? 'M' : 'L'}${x.toFixed(1)} ${y.toFixed(1)}`;
      }
      out.push(d);
    }
    return out;
  }, [width, height, lines]);
  return (
    <svg className="guilloche" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" aria-hidden="true">
      {paths.map((d, i) => <path key={i} d={d} className={i % 3 === 0 ? 'g-b' : 'g-a'} />)}
      <circle cx={width - 70} cy={70} r="110" />
      <circle cx={width - 70} cy={70} r="80" />
      <circle cx={width - 70} cy={70} r="50" />
    </svg>
  );
}

// ------------------------------------------------------------------ the journey of an asynchronous operation
const JOURNEYS = {
  deposit: {
    PENDING: [['Request received', 'done'], ['Waiting for the bank', 'active'], ['Money in your wallet', 'todo']],
    SUCCEEDED: [['Request received', 'done'], ['Bank confirmed', 'done'], ['Money in your wallet', 'done']],
    FAILED: [['Request received', 'done'], ['Bank declined', 'failed'], ['Nothing was charged', 'done']],
  },
  withdraw: {
    PENDING: [['Money reserved', 'done'], ['Bank paying out', 'active'], ['Sent to your bank', 'todo']],
    SUCCEEDED: [['Money reserved', 'done'], ['Bank paid out', 'done'], ['Sent to your bank', 'done']],
    FAILED: [['Money reserved', 'done'], ['Bank declined', 'failed'], ['Money back in your wallet', 'done']],
  },
};

/** Shows where a deposit or withdrawal is: the asynchronous PENDING state made visible. */
export function Journey({ kind, status }) {
  const steps = JOURNEYS[kind][status] || JOURNEYS[kind].PENDING;
  return (
    <ol className="journey">
      {steps.map(([label, state]) => (
        <li key={label} className={`journey-step journey-${state}`}>
          <span className="journey-dot">
            {state === 'done' && <Icon name="check" size={13} />}
            {state === 'failed' && <Icon name="x" size={13} />}
          </span>
          <span className="journey-label">{label}</span>
        </li>
      ))}
    </ol>
  );
}

// ------------------------------------------------------------------ balance trend chart
/** A small area chart of the balance after each entry, oldest to newest. */
export function TrendChart({ points, height = 120 }) {
  const width = 600;
  if (points.length < 2) {
    return <div className="trend-empty">The chart appears after a few movements.</div>;
  }
  const values = points.map(p => p.value);
  const min = Math.min(...values, 0);
  const max = Math.max(...values, 1);
  const x = i => 8 + (i / (points.length - 1)) * (width - 16);
  const y = v => height - 8 - ((v - min) / (max - min || 1)) * (height - 24);
  const line = points.map((p, i) => `${i ? 'L' : 'M'}${x(i).toFixed(1)} ${y(p.value).toFixed(1)}`).join('');
  const area = `${line}L${width} ${height}L0 ${height}Z`;
  const last = points[points.length - 1];
  return (
    <svg className="trend" viewBox={`0 0 ${width} ${height}`} preserveAspectRatio="none" role="img"
         aria-label={`Balance over the last ${points.length} movements`}>
      <defs>
        <linearGradient id="trend-fill" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--c-add)" stopOpacity="0.35" />
          <stop offset="100%" stopColor="var(--c-add)" stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={area} fill="url(#trend-fill)" />
      <path d={line} className="trend-line" vectorEffect="non-scaling-stroke" />
      <circle cx={x(points.length - 1)} cy={y(last.value)} r="5" className="trend-dot" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

// ------------------------------------------------------------------ celebration
/** A short burst of coloured dots when money has been sent. */
export function Burst() {
  const dots = Array.from({ length: 18 }, (_, i) => {
    const angle = (i / 18) * Math.PI * 2;
    const distance = 60 + (i % 3) * 22;
    return { x: Math.cos(angle) * distance, y: Math.sin(angle) * distance, tone: ['add', 'send', 'withdraw', 'return', 'bell'][i % 5] };
  });
  return (
    <span className="burst" aria-hidden="true">
      {dots.map((d, i) => <i key={i} className={`burst-dot tone-${d.tone}`} style={{ '--x': `${d.x}px`, '--y': `${d.y}px` }} />)}
    </span>
  );
}

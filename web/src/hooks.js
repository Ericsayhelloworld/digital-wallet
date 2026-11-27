import { useEffect, useRef, useState } from 'react';

/** Runs fn at once and then every ms milliseconds while the component is shown (UI-5). ms = null: once. */
export function usePoll(fn, ms, deps = []) {
  const latest = useRef(fn);
  latest.current = fn;
  useEffect(() => {
    let alive = true;
    const tick = () => { if (alive) latest.current(); };
    tick();
    if (!ms) return () => { alive = false; };
    const id = setInterval(tick, ms);
    return () => { alive = false; clearInterval(id); };
  }, [ms, ...deps]); // eslint-disable-line react-hooks/exhaustive-deps
}

const readHash = () => window.location.hash.replace(/^#\/?/, '');

/** The page part of the hash: "#/send?to=w1" gives "send". */
export function useHashRoute() {
  const [hash, setHash] = useState(readHash());
  useEffect(() => {
    const update = () => setHash(readHash());
    window.addEventListener('hashchange', update);
    return () => window.removeEventListener('hashchange', update);
  }, []);
  return hash.split('?')[0];
}

/** The parameters after "?" in the hash. */
export function routeParams() {
  const query = readHash().split('?')[1] || '';
  return new URLSearchParams(query);
}

export function navigate(route) {
  if (readHash() !== route) window.location.hash = `#/${route}`;
}

import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, request, type OffsetPage } from './api';

export function useResource<T>(path: string, refresh = 0) {
  const [state, setState] = useState<{ path: string; data?: T; error?: Error }>({ path });
  useEffect(() => {
    const controller = new AbortController();
    setState({ path });
    request<T>(path, { signal: controller.signal }).then(data => { if (!controller.signal.aborted) setState({ path, data }); }).catch(error => {
      if (!controller.signal.aborted) setState({ path, error });
    });
    return () => controller.abort();
  }, [path, refresh]);
  return state.path === path ? state : { path };
}
export function Loading() { return <p role="status"><span className="spinner-border spinner-border-sm me-2" aria-hidden="true"/>Loading…</p>; }
export function Failure({ error, retry }: { error: Error; retry?: () => void }) {
  return <div role="alert" className="alert alert-danger"><p>{error instanceof ApiError && error.status === 404 ? 'Not found. This item may have been removed.' : error.message}</p>{error instanceof ApiError && error.errors.length > 0 && <ul>{error.errors.map((e, i) => <li key={i}>{e.field}: {e.message}</li>)}</ul>}{retry && <button type="button" className="btn btn-outline-danger" onClick={retry}>Try again</button>}</div>;
}
export function Pagination<T>({ page, previous, onPrevious, onNext }: { page: OffsetPage<T>; previous: boolean; onPrevious: () => void; onNext: () => void }) {
  return <nav className="d-flex align-items-center gap-3 my-4" aria-label="Pagination"><button type="button" className="btn btn-outline-primary" disabled={!previous} onClick={onPrevious}>Previous</button><span>{page.total === 0 ? '0 results' : `${page.skip + 1}–${page.skip + page.items.length} of ${page.total}`}</span><button type="button" className="btn btn-outline-primary" disabled={!page.nextLink} onClick={onNext}>Next</button></nav>;
}
export function usePage(initial: string) {
  const [history, setHistory] = useState([initial]);
  // Reset synchronously when filters/parent change, without fetching the old page again.
  const [root, setRoot] = useState(initial);
  if (root !== initial) { setRoot(initial); setHistory([initial]); }
  return { path: root === initial ? history[history.length - 1] : initial, previous: history.length > 1, next: (link: string | null) => { if (link) setHistory(h => [...h, link]); }, back: () => setHistory(h => h.slice(0, -1)) };
}
export function SpaceImage({ url, name }: { url: string | null; name: string }) {
  const [failed, setFailed] = useState(false);
  const safe = url && (/^https?:\/\//i.test(url) || (url.startsWith('/') && !url.startsWith('//')));
  return safe && !failed ? <img className="space-image" src={url} alt={name} onError={() => setFailed(true)} loading="lazy"/> : <div className="image-placeholder">SpotPulse · Coworking space</div>;
}
export function NotFound() { return <section><h1>Page not found</h1><p>Let’s find your next workspace.</p><Link to="/spaces" className="btn btn-primary">Explore spaces</Link></section>; }

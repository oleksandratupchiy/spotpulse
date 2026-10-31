import { Component, useEffect, useState, type ReactNode } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, apiUrl, request, type OffsetPage } from './api';
import { ArrowLeft, ArrowRight, ArrowUpRight, CircleAlert, Building2, Sparkles } from 'lucide-react';

export function PageHeader({ eyebrow, title, description, children }: { eyebrow: string; title: string; description?: string; children?: ReactNode }) {
  return <header className="page-heading"><div><p className="eyebrow">{eyebrow}</p><h1>{title}</h1>{description && <p className="page-description">{description}</p>}</div>{children && <div className="page-actions">{children}</div>}</header>;
}
export function EmptyState({ title, description }: { title: string; description: string }) {
  return <section role="status" className="empty-state"><Sparkles aria-hidden="true"/><div><h3>{title}</h3><p>{description}</p></div></section>;
}

// A failed lazy map chunk must not take down discovery or booking navigation.
export class MapBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed
      ? <p role="alert" className="alert alert-warning">The map could not be loaded. Refresh the page to try again, or explore the space list below.</p>
      : this.props.children;
  }
}

export class AnalyticsBoundary extends Component<{ children: ReactNode }, { failed: boolean }> {
  state = { failed: false };
  static getDerivedStateFromError() { return { failed: true }; }
  render() {
    return this.state.failed
      ? <p role="alert" className="alert alert-warning">Analytics could not be displayed. Refresh the page to try again.</p>
      : this.props.children;
  }
}

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
export function Loading() { return <p role="status" className="loading-state"><span className="loading-orbit" aria-hidden="true"/>Loading…</p>; }
export function BookingStatus({ status }: { status: string }) {
  return <p>Status: <span className={`booking-status ${status === 'CONFIRMED' ? 'confirmed' : 'cancelled'}`}>{status === 'CONFIRMED' ? 'Confirmed' : status === 'CANCELLED' ? 'Cancelled' : status}</span></p>;
}
export function Failure({ error, retry }: { error: Error; retry?: () => void }) {
  return <div role="alert" className="alert alert-danger failure-state"><CircleAlert aria-hidden="true" size={20}/><p>{error instanceof ApiError && error.status === 404 ? 'Not found. This item may have been removed.' : error.message}</p>{error instanceof ApiError && error.errors.length > 0 && <ul>{error.errors.map((e, i) => <li key={i}>{e.field}: {e.message}</li>)}</ul>}{retry && <button type="button" className="sp-button sp-button--ghost" onClick={retry}>Try again</button>}</div>;
}
export function Pagination<T>({ page, previous, onPrevious, onNext }: { page: OffsetPage<T>; previous: boolean; onPrevious: () => void; onNext: () => void }) {
  return <nav className="pagination-bar" aria-label="Pagination"><button type="button" className="sp-button sp-button--ghost" disabled={!previous} onClick={onPrevious}><ArrowLeft aria-hidden="true"/> Previous</button><span>{page.total === 0 ? '0 results' : `${page.skip + 1}–${page.skip + page.items.length} of ${page.total}`}</span><button type="button" className="sp-button sp-button--ghost" disabled={!page.nextLink} onClick={onNext}>Next <ArrowRight aria-hidden="true"/></button></nav>;
}
export function usePage(initial: string) {
  const [history, setHistory] = useState([initial]);
  // Reset synchronously when filters/parent change, without fetching the old page again.
  const [root, setRoot] = useState(initial);
  if (root !== initial) { setRoot(initial); setHistory([initial]); }
  return { path: root === initial ? history[history.length - 1] : initial, previous: history.length > 1, next: (link: string | null) => { if (link) setHistory(h => [...h, link]); }, back: () => setHistory(h => h.slice(0, -1)) };
}
export function SpaceImage({ url, name }: { url: string | null; name: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>();
  const safe = url && (/^https?:\/\//i.test(url) || (url.startsWith('/') && !url.startsWith('//')));
  return safe && failedUrl !== url ? <img className="space-image" src={url.startsWith('/api/spaces/images/') ? apiUrl(url) : url} alt={name} onError={() => setFailedUrl(url)} loading="lazy"/> : <div className="image-placeholder"><Building2 aria-hidden="true" strokeWidth={1}/><span>Space for possibility.</span><small>Photography coming soon</small></div>;
}
export function NotFound() { return <section className="auth-panel"><p className="eyebrow">A LITTLE DETOUR / 404</p><h1>Page not found</h1><p>Let’s find your next workspace.</p><Link to="/spaces" className="sp-button sp-button--primary">Explore spaces <ArrowUpRight aria-hidden="true"/></Link></section>; }

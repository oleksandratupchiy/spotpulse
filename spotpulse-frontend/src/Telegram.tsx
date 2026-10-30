import { useEffect, useState } from 'react';
import { request } from './api';
import { Failure, Loading } from './components';

interface Status { configured: boolean; available: boolean; connected: boolean; expiresAt: string | null; bookingNotificationsEnabled: boolean }
interface Link { url: string; expiresAt: string }

export function TelegramAccount() {
  const [status, setStatus] = useState<Status>();
  const [link, setLink] = useState<Link>();
  const [error, setError] = useState<Error>();
  const [pending, setPending] = useState(false);
  const [revision, setRevision] = useState(0);
  useEffect(() => {
    const controller = new AbortController();
    async function refresh() {
      try {
        const next = await request<Status>('/api/account/telegram', { signal: controller.signal });
        if (!controller.signal.aborted) {
          setStatus(next); setError(undefined);
          if (next.connected || !next.expiresAt) setLink(undefined);
        }
      } catch (error) { if (!controller.signal.aborted) setError(error as Error); }
    }
    void refresh();
    const interval = window.setInterval(() => { void refresh(); }, 5000);
    return () => { controller.abort(); window.clearInterval(interval); };
  }, [revision]);

  async function connect() {
    setPending(true); setError(undefined);
    try {
      const next = await request<Link>('/api/account/telegram/connect', { method: 'POST' });
      setLink(next); setStatus(current => current ? { ...current, connected: false, expiresAt: next.expiresAt } : current);
      // The visible link also works when the browser blocks asynchronous popups.
      window.open(next.url, '_blank', 'noopener,noreferrer');
    } catch (error) { setError(error as Error); }
    finally { setPending(false); }
  }
  async function disconnect() {
    setPending(true); setError(undefined);
    try {
      await request('/api/account/telegram', { method: 'DELETE' });
      setLink(undefined); setStatus(current => current ? { ...current, connected: false, expiresAt: null } : current);
      setRevision(value => value + 1);
    } catch (error) { setError(error as Error); }
    finally { setPending(false); }
  }
  async function preferences(enabled: boolean) {
    setPending(true); setError(undefined);
    try {
      const next = await request<Status>('/api/account/telegram/preferences', {
        method: 'PATCH', body: JSON.stringify({ bookingNotificationsEnabled: enabled })
      });
      setStatus(next);
    } catch (error) { setError(error as Error); }
    finally { setPending(false); }
  }
  return <section className="card card-body mt-4" aria-labelledby="telegram-heading">
    <h2 id="telegram-heading" className="h4">Telegram</h2>
    {error && <Failure error={error} retry={() => setRevision(value => value + 1)}/>}
    {!status && !error && <Loading/>}
    {status && <>
      <p role="status">{status.connected ? 'Telegram connected.' : 'Telegram is not connected.'}</p>
      <label className="form-check">
        <input className="form-check-input" type="checkbox" checked={status.bookingNotificationsEnabled}
          disabled={pending} onChange={event => void preferences(event.target.checked)}/>
        <span className="form-check-label">Booking confirmation and cancellation notifications</span>
      </label>
      <p className="text-muted">{status.bookingNotificationsEnabled
        ? (status.connected ? 'Booking notifications are enabled.' : 'Booking notifications are enabled. Connect Telegram to receive them.')
        : 'Booking notifications are disabled.'}</p>
      {!status.configured ? <p>Telegram integration is unavailable on this server.</p>
        : !status.available ? <p>Telegram is starting or temporarily unavailable. Please try again shortly.</p> : null}
      {!status.connected && <button className="btn btn-primary align-self-start" disabled={pending || !status.available} onClick={connect}>Connect Telegram</button>}
      {link && <p className="mt-3"><a href={link.url} target="_blank" rel="noopener noreferrer" referrerPolicy="no-referrer">Open Telegram to finish connecting</a>. Press Start in the bot chat. This private connection link expires in five minutes; keep it to yourself.</p>}
      {status.expiresAt && !link && <p>A connection is pending. Finish in Telegram or create a new link.</p>}
      {(status.connected || status.expiresAt || link) && <button className="btn btn-outline-danger align-self-start mt-3" disabled={pending} onClick={disconnect}>{status.connected ? 'Disconnect Telegram' : 'Cancel connection'}</button>}
    </>}
  </section>;
}

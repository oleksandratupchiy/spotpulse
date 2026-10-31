import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import { request, type OffsetPage } from './api';
import { useAuth } from './Auth';
import { Failure, Loading } from './components';

export interface AvailabilityAlert {
  id: number; roomId: number; roomName: string; spaceName: string; city: string;
  startTime: string; endTime: string; status: 'ACTIVE' | 'READY' | 'SENT' | 'CANCELLED' | 'EXPIRED';
  createdAt: string; notifiedAt: string | null; deliveryReason: string | null;
}
export const activeAlert = (alert: AvailabilityAlert) => alert.status === 'ACTIVE' || alert.status === 'READY';
export const roomAlertsUrl = (roomId: number) => `/api/availability-alerts?skip=0&limit=1&activeOnly=true&roomId=${roomId}`;

function TelegramDelivery() {
  const [status, setStatus] = useState<{ connected: boolean; available: boolean; bookingNotificationsEnabled: boolean }>();
  const [failed, setFailed] = useState(false);
  useEffect(() => {
    const controller = new AbortController();
    const refresh = () => request<NonNullable<typeof status>>('/api/account/telegram', { signal: controller.signal })
      .then(next => { if (!controller.signal.aborted) { setStatus(next); setFailed(false); } })
      .catch(() => { if (!controller.signal.aborted) setFailed(true); });
    void refresh(); const timer = window.setInterval(() => { void refresh(); }, 15000);
    return () => { controller.abort(); window.clearInterval(timer); };
  }, []);
  return <p className="small text-secondary">{failed ? 'Telegram status could not be checked. Saving an alert does not guarantee delivery.'
    : !status ? 'Checking Telegram delivery…'
    : !status.connected ? 'Telegram is not linked. Your alert can be saved, but delivery requires connecting Telegram.'
    : !status.bookingNotificationsEnabled ? 'Telegram notifications are disabled. Enable booking notifications to receive availability alerts too.'
    : !status.available ? 'Telegram is temporarily unavailable. Your saved alert will wait for delivery while the time slot remains eligible.'
    : 'Telegram is connected. We will attempt delivery when this time slot becomes available.'} <Link to="/account">Telegram preferences</Link></p>;
}

export function AvailabilitySubscription({ roomId, from, to, occupied }: { roomId: number; from: string; to: string; occupied: boolean }) {
  const { user, loading: sessionLoading } = useAuth();
  const [alert, setAlert] = useState<AvailabilityAlert>();
  const mutationGeneration = useRef(0);
  const [loading, setLoading] = useState(true), [pending, setPending] = useState(false);
  const [error, setError] = useState<Error>(), [revision, setRevision] = useState(0);
  useEffect(() => {
    setAlert(undefined); setError(undefined); setLoading(true);
    if (!user) return;
    const controller = new AbortController();
    let timer: number | undefined;
    async function refresh() {
      const generation = mutationGeneration.current;
      try {
        const page = await request<OffsetPage<AvailabilityAlert>>(`${roomAlertsUrl(roomId)}&startTime=${encodeURIComponent(from)}&endTime=${encodeURIComponent(to)}`, { signal: controller.signal });
        if (!controller.signal.aborted && generation === mutationGeneration.current) { setAlert(page.items.find(activeAlert)); setLoading(false); setError(undefined); }
      } catch (error) { if (!controller.signal.aborted && generation === mutationGeneration.current) { setError(error as Error); setLoading(false); } }
      finally { if (!controller.signal.aborted) timer = window.setTimeout(() => { void refresh(); }, 15000); }
    }
    void refresh();
    return () => { controller.abort(); window.clearTimeout(timer); };
  }, [user?.id, roomId, from, to, revision]);
  async function save() {
    mutationGeneration.current++;
    setPending(true); setError(undefined);
    try {
      const next = await request<AvailabilityAlert>('/api/availability-alerts', { method: 'POST', body: JSON.stringify({ roomId, startTime: from, endTime: to }) });
      setAlert(next);
    } catch (error) { setError(error as Error); }
    finally { mutationGeneration.current++; setPending(false); }
  }
  async function cancel() {
    if (!alert) return;
    mutationGeneration.current++;
    setPending(true); setError(undefined);
    try { await request(`/api/availability-alerts/${alert.id}`, { method: 'DELETE' }); setAlert(undefined); }
    catch (error) { setError(error as Error); }
    finally { mutationGeneration.current++; setPending(false); }
  }
  if (sessionLoading) return <p>Checking session…</p>;
  if (!user) return occupied ? <p><Link to="/login">Sign in to create an availability alert</Link>. Alerts are saved to your account.</p> : null;
  return <div className="mt-3" aria-label="Persistent availability alert">
    {loading ? <p role="status">Loading saved alert…</p> : alert ? <>
      <p role="status">You're watching this time slot. Your alert is saved and stays active after you close the browser.</p>
      {alert.status === 'READY' && <p>Availability detected; Telegram delivery is pending.</p>}
      <button className="sp-button sp-button--ghost" disabled={pending} onClick={() => void cancel()}>{pending ? 'Cancelling…' : 'Cancel alert'}</button>
    </> : occupied ? <button className="sp-button sp-button--primary" disabled={pending || !!error} onClick={() => void save()}>{pending ? 'Saving alert…' : 'Notify me when available'}</button> : null}
    {error && <Failure error={error} retry={() => setRevision(value => value + 1)}/>}
    {(occupied || alert) && <><TelegramDelivery/><p className="small">An alert does not reserve the room. Delivery stops when the requested start time is reached. <Link to="/account">My availability alerts</Link></p></>}
  </div>;
}

export function MyAvailabilityAlerts() {
  const [page, setPage] = useState<OffsetPage<AvailabilityAlert>>();
  const [skip, setSkip] = useState(0), [revision, setRevision] = useState(0);
  const [error, setError] = useState<Error>(), [pending, setPending] = useState<number>();
  useEffect(() => {
    const controller = new AbortController(); setPage(undefined); setError(undefined);
    request<OffsetPage<AvailabilityAlert>>(`/api/availability-alerts?skip=${skip}&limit=10`, { signal: controller.signal })
      .then(next => { if (!controller.signal.aborted) setPage(next); })
      .catch(error => { if (!controller.signal.aborted) setError(error); });
    return () => controller.abort();
  }, [skip, revision]);
  async function cancel(id: number) {
    setPending(id); setError(undefined);
    try { await request(`/api/availability-alerts/${id}`, { method: 'DELETE' }); setRevision(v => v + 1); }
    catch (error) { setError(error as Error); }
    finally { setPending(undefined); }
  }
  const labels = { ACTIVE: 'Watching', READY: 'Available · Telegram delivery pending', SENT: 'Telegram sent', CANCELLED: 'Cancelled', EXPIRED: 'Expired' };
  return <section className="card card-body mt-4" aria-labelledby="my-alerts-heading">
    <p className="eyebrow">KEEP YOUR OPTIONS OPEN</p><h2 id="my-alerts-heading" className="h3">My availability alerts</h2>
    <p>Alerts are saved to your account, even while you're signed out. They do not reserve a room.</p><TelegramDelivery/>
    {error && <Failure error={error} retry={() => setRevision(v => v + 1)}/>}
    {!page && !error && <Loading/>}
    {page && <>{page.items.length === 0 ? <p>No availability alerts yet.</p> : page.items.map(alert => <article key={alert.id} className="border-top py-3">
      <h3 className="h5"><Link to={`/rooms/${alert.roomId}`}>{alert.roomName}</Link> · {alert.spaceName}</h3>
      <p>{alert.city} · {alert.startTime.replace('T', ' ')} → {alert.endTime.replace('T', ' ')} (times as entered)</p>
      <p>{labels[alert.status]}</p>
      {alert.deliveryReason && activeAlert(alert) && <p className="small">Delivery pending. Check your Telegram connection and notification preferences; failed attempts retry automatically while eligible.</p>}
      {activeAlert(alert) && <button className="sp-button sp-button--ghost" disabled={pending !== undefined} onClick={() => void cancel(alert.id)}>{pending === alert.id ? 'Cancelling…' : 'Cancel alert'}</button>}
    </article>)}
    <div className="d-flex gap-2"><button className="sp-button sp-button--small sp-button--ghost" disabled={skip === 0} onClick={() => setSkip(v => Math.max(0, v - 10))}>Previous alerts</button><button className="sp-button sp-button--small sp-button--ghost" disabled={!page.nextLink} onClick={() => setSkip(v => v + 10)}>Next alerts</button></div></>}
  </section>;
}

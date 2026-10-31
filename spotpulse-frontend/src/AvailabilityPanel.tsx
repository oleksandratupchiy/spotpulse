import { useEffect, useState, type FormEvent } from 'react';
import { watchAvailability, type AvailabilityMode, type AvailabilityState } from './availability';
import { useAuth } from './Auth';
import { Bell, RefreshCw } from 'lucide-react';
import { BOOKING_DURATION_MESSAGE, bookingEndLimit, calendarTime, exceedsBookingDuration } from './bookingRules';

export function AvailabilityPanel({ roomId }: { roomId: number }) {
  const { user } = useAuth();
  const technical = user?.role === 'OWNER' || user?.role === 'ADMIN';
  const [mode, setMode] = useState<AvailabilityMode>('polling');
  const [interval, setInterval] = useState(5000);
  const [from, setFrom] = useState(''), [to, setTo] = useState('');
  const [range, setRange] = useState<{ from: string; to: string }>();
  const [state, setState] = useState<AvailabilityState>();
  const [error, setError] = useState(''), [restart, setRestart] = useState(0);
  useEffect(() => {
    setState(undefined);
    if (range) return watchAvailability(roomId, range.from, range.to, technical ? mode : 'polling', interval, setState);
  }, [roomId, range, restart, mode, interval, technical]);
  function select(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!Number.isFinite(calendarTime(from)) || !Number.isFinite(calendarTime(to)) || to <= from) {
      setError('Choose an end time after the start time.'); return;
    }
    if (exceedsBookingDuration(from, to)) { setError(BOOKING_DURATION_MESSAGE); return; }
    setError(''); setRange({ from, to });
  }
  return <section className="card card-body mt-4 availability-panel" aria-labelledby="availability-title">
    <p className="eyebrow"><Bell aria-hidden="true" size={16}/> KEEP AN EYE ON YOUR PLANS</p><h2 id="availability-title" className="h3">Waiting for a room?</h2>
    <p id="availability-help">Choose your times and we’ll keep you updated here while this page is open. Only a confirmed booking reserves the room.</p>
    <form noValidate onSubmit={select} className="row g-3 align-items-end" aria-describedby="availability-help">
      <div className="col-sm-6 col-lg-4"><label htmlFor="availability-from" className="form-label">Availability from</label><input id="availability-from" name="from" type="datetime-local" className="form-control" required value={from} onChange={e => setFrom(e.target.value)}/></div>
      <div className="col-sm-6 col-lg-4"><label htmlFor="availability-to" className="form-label">Availability to</label><input id="availability-to" name="to" type="datetime-local" className="form-control" required min={from || undefined} max={bookingEndLimit(from)} value={to} onChange={e => setTo(e.target.value)}/></div>
      <div className="col-lg-4"><button className="sp-button sp-button--primary"><Bell aria-hidden="true"/>{range ? 'Update times' : 'Notify me when available'}</button></div>
    </form>
    {error && <p role="alert" className="text-danger mt-3">{error}</p>}
    {range && <div className="mt-3">
      <p className="small">Watching {range.from.replace('T', ' ')} → {range.to.replace('T', ' ')}. Times are shown as entered. Updates pause when this tab is hidden.</p>
      <p role="status">{state?.error ? 'Availability updates are delayed.' : !state?.data ? 'Checking availability…' : state.data.occupied.length ? 'This time is currently booked. We’ll let you know here when it’s available.' : 'Available for your selected time. You can book above.'}</p>
      {state?.error && <p role="alert">We couldn’t refresh availability. We’ll try again automatically; any earlier result may be out of date.</p>}
      {state?.lastUpdated && <p className="small text-secondary">Updated at {new Date(state.lastUpdated).toLocaleTimeString()}</p>}
      {state?.data && state.data.occupied.length > 0 && <><h3 className="h5">Booked times</h3><ul>{state.data.occupied.map((slot, i) => <li key={i}>{slot.start.replace('T', ' ')} → {slot.end.replace('T', ' ')}</li>)}</ul></>}
      <div className="d-flex flex-wrap gap-2"><button type="button" className="sp-button sp-button--secondary" onClick={() => setRestart(v => v + 1)}><RefreshCw aria-hidden="true"/> Refresh</button><button type="button" className="sp-button sp-button--ghost" onClick={() => { setRange(undefined); setError(''); }}>Stop updates</button></div>
    </div>}
    {technical && <details className="technical-comparison"><summary>Connection comparison (C10)</summary><p className="small">Compare update methods for this room. Both methods refresh the same availability data.</p><div className="row g-3"><div className="col-sm-6"><label htmlFor="availability-mode" className="form-label">Update mode</label><select id="availability-mode" className="form-select" value={mode} onChange={event => setMode(event.target.value as AvailabilityMode)}><option value="polling">HTTP Polling</option><option value="websocket">WebSocket</option></select></div><div className="col-sm-6"><label htmlFor="availability-interval" className="form-label">Polling interval</label><select id="availability-interval" className="form-select" disabled={mode !== 'polling'} value={interval} onChange={event => setInterval(Number(event.target.value))}><option value={3000}>3 seconds</option><option value={5000}>5 seconds</option><option value={10000}>10 seconds</option></select></div></div>{state && <div className="connection-metrics"><p>{state.status}</p><dl><div><dt>HTTP requests</dt><dd>{state.httpRequests}</dd></div><div><dt>WebSocket messages</dt><dd>{state.wsMessages}</dd></div><div><dt>Latest HTTP refresh</dt><dd>{state.requestMs === undefined ? 'Waiting' : `${state.requestMs.toFixed(1)} ms`}</dd></div><div><dt>Notification to refresh</dt><dd>{state.notificationRefreshMs === undefined ? 'Waiting' : `${state.notificationRefreshMs.toFixed(1)} ms`}</dd></div></dl><p className="small">Client timings do not measure server commit-to-client latency.</p>{state.error && <p role="alert">{state.error}</p>}</div>}</details>}
  </section>;
}

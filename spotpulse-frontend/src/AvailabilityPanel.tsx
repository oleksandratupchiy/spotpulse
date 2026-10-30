import { useEffect, useState, type FormEvent } from 'react';
import { watchAvailability, type AvailabilityMode, type AvailabilityState } from './availability';

export function AvailabilityPanel({ roomId }: { roomId: number }) {
  const [mode, setMode] = useState<AvailabilityMode>('polling');
  const [interval, setInterval] = useState(5000);
  const [range, setRange] = useState<{ from: string; to: string }>();
  const [state, setState] = useState<AvailabilityState>();
  const [error, setError] = useState(''), [restart, setRestart] = useState(0);
  useEffect(() => {
    setState(undefined);
    if (range) return watchAvailability(roomId, range.from, range.to, mode, interval, setState);
  }, [roomId, range, mode, interval, restart]);
  function select(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const data = new FormData(event.currentTarget);
    const from = String(data.get('from')), to = String(data.get('to'));
    if (!from || !to || !Number.isFinite(Date.parse(from)) || !Number.isFinite(Date.parse(to)) || to <= from || Date.parse(to) - Date.parse(from) > 31 * 86400000) {
      setError('Select an increasing interval of at most 31 days.'); return;
    }
    setError(''); setRange({ from, to });
  }
  return <section className="card card-body mt-4" aria-labelledby="availability-title">
    <h2 id="availability-title" className="h3">Live room availability</h2>
    <p>Open this room in two tabs to compare updates. Availability is advisory; only a confirmed booking reserves a room. Times use stored calendar time, without timezone conversion.</p>
    <div className="row g-3"><div className="col-sm-6"><label htmlFor="availability-mode" className="form-label">Update mode</label><select id="availability-mode" className="form-select" value={mode} onChange={e => setMode(e.target.value as AvailabilityMode)}><option value="websocket">WebSocket</option><option value="polling">HTTP Polling</option></select></div>
    <div className="col-sm-6"><label htmlFor="availability-interval" className="form-label">Polling interval</label><select id="availability-interval" className="form-select" value={interval} disabled={mode !== 'polling'} onChange={e => setInterval(Number(e.target.value))}><option value={3000}>3 seconds</option><option value={5000}>5 seconds</option><option value={10000}>10 seconds</option></select></div></div>
    <form onSubmit={select} className="row g-3 mt-1"><div className="col-sm-5"><label htmlFor="availability-from" className="form-label">Availability from</label><input id="availability-from" name="from" type="datetime-local" className="form-control" required/></div><div className="col-sm-5"><label htmlFor="availability-to" className="form-label">Availability to</label><input id="availability-to" name="to" type="datetime-local" className="form-control" required/></div><div className="col-sm-2 align-self-end"><button className="btn btn-primary">Watch availability</button></div></form>
    {error && <p role="alert">{error}</p>}
    {state && <><p role="status" className="mt-3">{state.status}</p>{state.error && <p role="alert">{state.error} Last successful data may be stale.</p>}
      <p className="small">Last successful update: {state.lastUpdated ? new Date(state.lastUpdated).toLocaleTimeString() : 'Waiting'} · HTTP requests: {state.httpRequests} · WebSocket messages: {state.wsMessages}</p>
      {state.requestMs !== undefined && <p className="small">Latest HTTP refresh: {state.requestMs.toFixed(1)} ms{state.notificationRefreshMs !== undefined && ` · Notification to refreshed state: ${state.notificationRefreshMs.toFixed(1)} ms`}. These client durations do not measure commit-to-client latency.</p>}
      {state.data && <>{state.data.occupied.length ? <><h3 className="h5">Occupied intervals</h3><ul>{state.data.occupied.map((slot, i) => <li key={i}>{slot.start.replace('T', ' ')} → {slot.end.replace('T', ' ')}</li>)}</ul></> : <p>No confirmed bookings overlap this interval.</p>}</>}
      <button type="button" className="btn btn-outline-primary align-self-start" onClick={() => setRestart(v => v + 1)}>Refresh / reconnect</button>
    </>}
  </section>;
}

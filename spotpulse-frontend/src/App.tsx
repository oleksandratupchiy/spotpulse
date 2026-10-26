import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useParams, useSearchParams } from 'react-router-dom';
import { request, type Booking, type OffsetPage, type Room, type Space, type User } from './api';
import { AnalyticsBoundary, Failure, Loading, MapBoundary, NotFound, Pagination, SpaceImage, usePage, useResource } from './components';

const Analytics = lazy(() => import('./Analytics'));
const SpacesMap = lazy(() => import('./SpacesMap'));

export default function App() {
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  useEffect(() => { main.current?.focus(); }, [location.pathname]);
  return <><a className="skip-link" href="#main">Skip to content</a><header className="site-header"><nav className="container d-flex flex-wrap align-items-center justify-content-between gap-3 py-3" aria-label="Main navigation"><Link className="brand" to="/spaces">SpotPulse<span className="brand-dot">●</span></Link><NavLink to="/spaces" className="nav-link">Explore spaces</NavLink><NavLink to="/analytics" className="nav-link">Analytics</NavLink></nav></header><main id="main" tabIndex={-1} ref={main} className="container py-5"><Routes><Route path="/" element={<Navigate to="/spaces" replace/>}/><Route path="/spaces" element={<Spaces/>}/><Route path="/analytics" element={<AnalyticsBoundary><Suspense fallback={<Loading/>}><Analytics/></Suspense></AnalyticsBoundary>}/><Route path="/spaces/:id" element={<SpaceDetails/>}/><Route path="/rooms/:id" element={<RoomDetails/>}/><Route path="*" element={<NotFound/>}/></Routes></main><footer className="container py-4 border-top text-secondary">SpotPulse · A place for your next idea.</footer></>;
}

function Spaces() {
  const [params, setParams] = useSearchParams();
  const mode = params.get('mode') === 'city' ? 'city' : 'search';
  const query = params.get('q') || '';
  const filter = query ? `&${mode}=${encodeURIComponent(query)}` : '';
  const paging = usePage('/api/spaces?skip=0&limit=6' + filter);
  const [showMap, setShowMap] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<OffsetPage<Space>>(paging.path, refresh);
  function search(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const form = new FormData(event.currentTarget);
    setParams({ mode: String(form.get('mode')), q: String(form.get('q')).trim() });
  }
  return <><section className="hero mb-4"><p className="eyebrow">WORK. MEET. CONNECT.</p><h1>Find your space to focus.</h1><p className="lead">Explore coworking spaces and book a meeting room for your next session.</p></section><form onSubmit={search} className="search-panel row g-3 align-items-end mb-5"><div className="col-sm-4 col-lg-3"><label htmlFor="mode" className="form-label">Search by</label><select id="mode" name="mode" className="form-select" value={mode} onChange={e => setParams({ mode: e.target.value, q: query }, { replace: true })}><option value="search">Name, city or address</option><option value="city">Exact city</option></select></div><div className="col-sm-8 col-lg-7"><label htmlFor="query" className="form-label">Search spaces</label><input id="query" name="q" className="form-control" type="search" maxLength={255} value={query} onChange={e => setParams({ mode, q: e.target.value }, { replace: true })} placeholder="Where would you like to work?"/></div><div className="col-lg-2"><button className="btn btn-primary w-100">Search</button></div></form><div className="d-flex flex-wrap align-items-center justify-content-between gap-3 mb-4"><h2 className="h4 mb-0">Discover coworking spaces</h2><button type="button" className="btn btn-outline-primary" aria-pressed={showMap} onClick={() => setShowMap(v => !v)}>{showMap ? 'Hide map' : 'Show map'}</button></div>{showMap && <MapBoundary><Suspense fallback={<Loading/>}><SpacesMap filter={filter}/></Suspense></MapBoundary>}{error ? <Failure error={error} retry={() => setRefresh(r => r + 1)}/> : !data ? <Loading/> : <>{data.items.length === 0 && <p role="status">No spaces found. Try a different search.</p>}<div className="row g-4">{data.items.map(space => <article key={space.id} className="col-md-6 col-xl-4"><div className="card h-100 overflow-hidden"><SpaceImage url={space.imageUrl} name={space.name}/><div className="card-body d-flex flex-column"><p className="eyebrow">{space.city}</p><h3 className="h5"><Link to={`/spaces/${space.id}`}>{space.name}</Link></h3><p className="text-secondary">{space.address}</p><Link to={`/spaces/${space.id}`} className="btn btn-outline-primary mt-auto align-self-start">View space</Link></div></div></article>)}</div><Pagination page={data} previous={paging.previous} onPrevious={paging.back} onNext={() => paging.next(data.nextLink)}/></>}</>;
}

function SpaceDetails() {
  const { id } = useParams();
  const [refresh, setRefresh] = useState(0);
  const { data: space, error } = useResource<Space>(`/api/spaces/${encodeURIComponent(id!)}`, refresh);
  return <><Link to="/spaces" className="back-link">← All spaces</Link>{error ? <Failure error={error} retry={() => setRefresh(r => r + 1)}/> : !space ? <Loading/> : <><div className="row g-4 my-2"><div className="col-lg-6"><SpaceImage key={space.imageUrl} url={space.imageUrl} name={space.name}/></div><section className="col-lg-6"><p className="eyebrow">{space.city}</p><h1>{space.name}</h1><p>{space.address}</p><p className="description">{space.description || 'No description provided.'}</p>{space.contactEmail ? <p>Contact: <a href={`mailto:${space.contactEmail}`}>{space.contactEmail}</a></p> : <p className="text-secondary">No contact email provided.</p>}</section></div><h2 className="h3 mt-5">Meeting rooms</h2><Rooms spaceId={space.id}/></>}</>;
}
function Rooms({ spaceId }: { spaceId: number }) {
  const paging = usePage(`/api/rooms?spaceId=${spaceId}&skip=0&limit=6`);
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<OffsetPage<Room>>(paging.path, refresh);
  if (error) return <Failure error={error} retry={() => setRefresh(r => r + 1)}/>;
  if (!data) return <Loading/>;
  return <>{!data.items.length && <p>No meeting rooms listed for this space.</p>}<div className="row g-3">{data.items.map(room => <article key={room.id} className="col-md-6"><div className="card card-body h-100"><h3 className="h5">{room.name}</h3><RoomFacts room={room}/><Link className="btn btn-outline-primary align-self-start" to={`/rooms/${room.id}`}>View room & book</Link></div></article>)}</div><Pagination page={data} previous={paging.previous} onPrevious={paging.back} onNext={() => paging.next(data.nextLink)}/></>;
}
function RoomFacts({ room }: { room: Room }) { return <dl className="row"><dt className="col-6">Capacity</dt><dd className="col-6">{room.capacity} people</dd><dt className="col-6">Hourly rate</dt><dd className="col-6">{room.hourlyRate} / hour</dd></dl>; }
function RoomDetails() {
  const { id } = useParams();
  const [refresh, setRefresh] = useState(0);
  const { data: room, error } = useResource<Room>(`/api/rooms/${encodeURIComponent(id!)}`, refresh);
  if (error) return <Failure error={error} retry={() => setRefresh(r => r + 1)}/>;
  if (!room) return <Loading/>;
  return <><Link className="back-link" to={`/spaces/${room.spaceId}`}>← {room.spaceName}</Link><div className="row g-5 mt-1"><section className="col-lg-5"><p className="eyebrow">MEETING ROOM</p><h1>{room.name}</h1><p className="text-secondary">{room.spaceName}</p><RoomFacts room={room}/><p>Availability is checked when you submit your booking. A room is reserved only after the server confirms it.</p><p className="small text-secondary">Rates are shown as supplied by the space. The API does not specify a currency.</p></section><section className="col-lg-7"><div className="card card-body p-4"><h2 className="h3">Book this room</h2><BookingForm key={room.id} room={room}/></div></section></div></>;
}

function BookingForm({ room }: { room: Room }) {
  const paging = usePage('/api/users?skip=0&limit=20');
  const [refresh, setRefresh] = useState(0);
  const users = useResource<OffsetPage<User>>(paging.path, refresh);
  const [user, setUser] = useState<{ id: number; label: string } | null>(null);
  const [start, setStart] = useState('');
  const [end, setEnd] = useState('');
  const [validation, setValidation] = useState<string[]>([]);
  const [error, setError] = useState<Error>();
  const [booking, setBooking] = useState<Booking>();
  const [pending, setPending] = useState(false);
  const result = useRef<HTMLDivElement>(null);
  useEffect(() => { if (error || booking || validation.length) result.current?.focus(); }, [error, booking, validation]);
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (pending || booking) return;
    setError(undefined); setBooking(undefined);
    const problems = [];
    if (!user) problems.push('Choose an existing user.');
    if (!start || !end) problems.push('Enter both start and end times.');
    if (start && new Date(start).getTime() <= Date.now()) problems.push('Start time must be in the future.');
    if (start && end && end <= start) problems.push('End time must be after start time.');
    setValidation(problems);
    if (problems.length) return;
    setPending(true);
    try {
      setBooking(await request<Booking>('/api/bookings', { method: 'POST', body: JSON.stringify({ roomId: room.id, userId: user!.id, startTime: start, endTime: end }) }));
    } catch (e) { setError(e as Error); }
    finally { setPending(false); }
  }
  return <form noValidate onSubmit={submit}><p className="small text-secondary" id="booking-help">Choose an existing user for this demonstration. This selection does not sign you in. Enter times in the backend’s local time; no timezone conversion is applied.</p><fieldset disabled={pending}><legend className="visually-hidden">Booking details</legend><label htmlFor="user" className="form-label">Existing user</label>{users.error ? <Failure error={users.error} retry={() => setRefresh(r => r + 1)}/> : !users.data ? <Loading/> : <><select id="user" className="form-select" value={users.data.items.some(u => u.id === user?.id) ? user!.id : ''} onChange={e => { const found = users.data!.items.find(u => u.id === Number(e.target.value)); setUser(found ? { id: found.id, label: `${found.name} (${found.email})` } : null); }} aria-describedby="booking-help" required><option value="">Choose a user</option>{users.data.items.map(u => <option key={u.id} value={u.id}>{u.name} ({u.email})</option>)}</select>{users.data.total === 0 && <p>No users exist yet. Create one through the existing REST API before booking.</p>}<Pagination page={users.data} previous={paging.previous} onPrevious={paging.back} onNext={() => paging.next(users.data!.nextLink)}/></>}{user && <p className="small mt-2">Selected: {user.label}</p>}<label htmlFor="start" className="form-label mt-3">Start time</label><input id="start" className="form-control" type="datetime-local" required value={start} onChange={e => setStart(e.target.value)}/><label htmlFor="end" className="form-label mt-3">End time</label><input id="end" className="form-control" type="datetime-local" required value={end} onChange={e => setEnd(e.target.value)}/><button className="btn btn-primary mt-4" disabled={pending || !!booking}>{pending ? 'Submitting booking…' : booking ? 'Booking confirmed' : 'Confirm booking'}</button></fieldset><div ref={result} tabIndex={-1} className="mt-3">{pending && <p role="status">Checking availability and saving your booking…</p>}{validation.length > 0 && <div role="alert" className="alert alert-danger"><ul className="mb-0">{validation.map(message => <li key={message}>{message}</li>)}</ul></div>}{error && <Failure error={error}/>} {booking && <div role="status" className="alert alert-success"><h3 className="h5">Booking #{booking.id} saved</h3><p>{booking.roomName} · {booking.userName}</p><p>{booking.startTime.replace('T', ' ')} → {booking.endTime.replace('T', ' ')}</p><p>Status: {booking.status}</p><button type="button" className="btn btn-outline-success" onClick={() => { setBooking(undefined); setStart(''); setEnd(''); }}>Make another booking</button></div>}</div></form>;
}

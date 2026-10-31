import { lazy, Suspense, useEffect, useRef, useState, type FormEvent } from 'react';
import { Link, NavLink, Navigate, Route, Routes, useLocation, useParams, useSearchParams } from 'react-router-dom';
import { AuthProvider, SessionNavigation, RequireRole, Login, Profile, useAuth } from './Auth';
import { Management, SpaceEditor, Bookings, Administration } from './Management';
import { request, type Booking, type OffsetPage, type Room, type Space } from './api';
import { AnalyticsBoundary, BookingStatus, Failure, Loading, MapBoundary, NotFound, EmptyState, Pagination, SpaceImage, usePage, useResource } from './components';

import { PremiumBadge } from './Premium';
import { AvailabilityPanel } from './AvailabilityPanel';
import { AnalyticsConsent } from './AnalyticsConsent';
import { usageAnalytics } from './usageAnalytics';
import { ArrowDown, ArrowLeft, ArrowUpRight, CalendarDays, MapPin, Map as MapIcon, Menu, Search, Sparkles, Users, X } from 'lucide-react';
import { BOOKING_DURATION_MESSAGE, bookingEndLimit, exceedsBookingDuration } from './bookingRules';

const Analytics = lazy(() => import('./Analytics'));
const SpacesMap = lazy(() => import('./SpacesMap'));

export default function App() {
  const [privacyTarget, setPrivacyTarget] = useState<HTMLDivElement | null>(null);
  return <AuthProvider><AnalyticsConsent portal={privacyTarget}/><AppLayout privacyRef={setPrivacyTarget}/></AuthProvider>;
}

function AppLayout({ privacyRef }: { privacyRef: (node: HTMLDivElement | null) => void }) {
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  const menuButton = useRef<HTMLButtonElement>(null);
  useEffect(() => {
    main.current?.focus({ preventScroll: true });
    if (window.scrollY) window.scrollTo(0, 0);
  }, [location.pathname]);
  const [menuOpen, setMenuOpen] = useState(false);
  useEffect(() => { setMenuOpen(false); }, [location.pathname]);
  const workspace = /^\/(owner|admin|analytics)(\/|$)/.test(location.pathname);
  return <>
    <a className="skip-link" href="#main">Skip to content</a>
    <header className="site-header">
      <nav className="container site-navigation" aria-label="Main navigation" onKeyDown={event => { if (event.key === 'Escape') { setMenuOpen(false); menuButton.current?.focus(); } }}>
        <Link className="brand" to="/spaces">SpotPulse<span className="brand-dot" aria-hidden="true">.</span></Link>
        <button ref={menuButton} className="sp-button sp-button--ghost mobile-menu" aria-expanded={menuOpen} aria-controls="navigation-links" onClick={() => setMenuOpen(value => !value)}>{menuOpen ? <X aria-hidden="true"/> : <Menu aria-hidden="true"/>} Menu</button>
        <div id="navigation-links" className="navigation-links" data-open={menuOpen}>
          <NavLink to="/spaces" className="nav-link">Explore spaces</NavLink><SessionNavigation/>
        </div>
      </nav>
    </header>
    <main id="main" tabIndex={-1} ref={main} className={`container app-main${workspace ? ' workspace' : ''}`}>
      <Routes><Route path="/" element={<Navigate to="/spaces" replace/>}/><Route path="/spaces" element={<Spaces/>}/><Route path="/analytics" element={<RequireRole roles={['OWNER', 'ADMIN']}><AnalyticsBoundary><Suspense fallback={<Loading/>}><Analytics/></Suspense></AnalyticsBoundary></RequireRole>}/><Route path="/login" element={<Login/>}/><Route path="/account" element={<RequireRole><Profile/></RequireRole>}/><Route path="/bookings" element={<RequireRole><Bookings/></RequireRole>}/><Route path="/owner" element={<RequireRole roles={['OWNER', 'ADMIN']}><Management/></RequireRole>}/><Route path="/owner/spaces/new" element={<RequireRole roles={['OWNER', 'ADMIN']}><SpaceEditor/></RequireRole>}/><Route path="/owner/spaces/:id" element={<RequireRole roles={['OWNER', 'ADMIN']}><SpaceEditor/></RequireRole>}/><Route path="/admin" element={<RequireRole roles={['ADMIN']}><Administration/></RequireRole>}/><Route path="/spaces/:id" element={<SpaceDetails/>}/><Route path="/rooms/:id" element={<RoomDetails/>}/><Route path="*" element={<NotFound/>}/></Routes>
    </main>
    <footer className="site-footer"><div className="container"><div className="footer-top"><p>A place for your next idea.</p><Link to="/spaces">Find your space <ArrowUpRight aria-hidden="true" size={18}/></Link></div><p className="footer-wordmark" aria-hidden="true">SpotPulse.</p><div className="footer-bottom"><span>Work. Meet. Make things happen.</span><span>Space for a better day.</span></div><div ref={privacyRef}/></div></footer>
  </>;

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
    usageAnalytics.search(mode === 'city' ? 'city' : 'text', !!String(form.get('q')).trim());
  }
  return <>
    <section className="discovery-hero" aria-labelledby="discovery-title">
      <div className="hero-copy"><p className="eyebrow">A LITTLE SPACE. A LOT OF POSSIBILITY.</p><h1 id="discovery-title">Find your space to focus.</h1><p className="hero-intro">Good work starts with the right space. Discover somewhere to think clearly, meet freely and make your next move.</p><a className="hero-jump" href="#discover">Find your place <ArrowDown aria-hidden="true" size={20}/></a></div>
      <div className="hero-aside" aria-hidden="true"><div className="hero-symbol"><Sparkles strokeWidth={1}/></div><p>Room for<br/>your next<br/><em>big idea.</em></p><span>01 / THE POSSIBILITIES ARE OPEN</span></div>
    </section>
    <form onSubmit={search} className="discovery-search" aria-label="Find a workspace">
      <div><label htmlFor="mode" className="form-label">Search by</label><select id="mode" name="mode" className="form-select" value={mode} onChange={e => setParams({ mode: e.target.value, q: query }, { replace: true })}><option value="search">Name, city or address</option><option value="city">Exact city</option></select></div>
      <div className="search-query"><label htmlFor="query" className="form-label">Search spaces</label><input id="query" name="q" className="form-control" type="search" maxLength={255} value={query} onChange={e => setParams({ mode, q: e.target.value }, { replace: true })} placeholder="Where would you like to work?"/></div>
      <button className="sp-button sp-button--primary"><Search aria-hidden="true"/> Search</button>
    </form>
    <section id="discover" aria-labelledby="catalog-title"><div className="section-heading"><div><p className="eyebrow">THE SPACE EDIT</p><h2 id="catalog-title">Spaces to make<br/>things happen.</h2></div><button type="button" className="sp-button sp-button--secondary" aria-pressed={showMap} onClick={() => setShowMap(v => !v)}><MapIcon aria-hidden="true"/>{showMap ? 'Hide map' : 'Show map'}</button></div>
      {showMap && <MapBoundary><Suspense fallback={<Loading/>}><SpacesMap filter={filter}/></Suspense></MapBoundary>}
      {error ? <Failure error={error} retry={() => setRefresh(r => r + 1)}/> : !data ? <Loading/> : <>
        {data.items.length === 0 && <EmptyState title="No spaces found." description="Try a different search. Your next workspace could be one word away."/>}
        <div className="catalog-grid">{data.items.map((space, index) => <article key={space.id} className="space-card">
          <div className="space-card-media"><SpaceImage url={space.imageUrl} name={space.name}/><PremiumBadge space={space}/><span className="space-index" aria-hidden="true">{String(data.skip + index + 1).padStart(2, '0')}</span></div>
          <div className="space-card-body"><p className="space-location"><MapPin aria-hidden="true" size={15}/>{space.city}</p><h3><Link to={`/spaces/${space.id}`}>{space.name}</Link></h3><p className="space-address">{space.address}</p>{space.rooms?.length > 0 && <p className="space-card-rate">Rooms from {Math.min(...space.rooms.map(room => room.hourlyRate))} / hour</p>}<Link to={`/spaces/${space.id}`} className="sp-button sp-button--ghost">View space <ArrowUpRight aria-hidden="true"/></Link></div>
        </article>)}</div>
        <Pagination page={data} previous={paging.previous} onPrevious={paging.back} onNext={() => paging.next(data.nextLink)}/>
      </>}
    </section>
  </>;

}

function SpaceDetails() {
  const { id } = useParams();
  const [refresh, setRefresh] = useState(0);
  const { data: space, error } = useResource<Space>(`/api/spaces/${encodeURIComponent(id!)}`, refresh);
  useEffect(() => { if (space) usageAnalytics.spaceView(); }, [space]);
  return <><Link to="/spaces" className="back-link"><ArrowLeft aria-hidden="true" size={18}/> All spaces</Link>
    {error ? <Failure error={error} retry={() => setRefresh(r => r + 1)}/> : !space ? <Loading/> : <>
      <header className="space-detail-heading"><div><p className="eyebrow">YOUR NEXT WORKING DAY / {space.city}</p><h1>{space.name}</h1><p className="detail-address"><MapPin aria-hidden="true" size={18}/>{space.address}</p></div><PremiumBadge space={space}/></header>
      <div className="space-detail-layout"><div className="detail-photo"><SpaceImage key={space.imageUrl} url={space.imageUrl} name={space.name}/></div><section className="space-story"><p className="eyebrow">GOOD TO KNOW</p><h2>Make yourself<br/>at home.</h2><p className="description">{space.description || 'A new space to discover. Explore the meeting rooms below.'}</p>{space.contactEmail ? <a className="sp-button sp-button--primary" href={`mailto:${space.contactEmail}`}>Contact the space <ArrowUpRight aria-hidden="true"/></a> : <p className="text-secondary">No contact email provided.</p>}</section></div>
      <div className="section-heading"><div><p className="eyebrow">MEET, THINK, CREATE</p><h2>Meeting rooms</h2></div><span className="section-note">A little room for better conversations.</span></div><Rooms spaceId={space.id}/>
    </>}
  </>;

}
function Rooms({ spaceId }: { spaceId: number }) {
  const paging = usePage(`/api/rooms?spaceId=${spaceId}&skip=0&limit=6`);
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<OffsetPage<Room>>(paging.path, refresh);
  if (error) return <Failure error={error} retry={() => setRefresh(r => r + 1)}/>;
  if (!data) return <Loading/>;
  return <>{!data.items.length && <EmptyState title="Rooms are on their way." description="No meeting rooms listed for this space yet."/>}<div className="room-grid">{data.items.map((room, index) => <article key={room.id} className="room-card"><div className="room-card-top"><span className="eyebrow">ROOM {String(index + 1).padStart(2, '0')}</span><Users aria-hidden="true" size={24}/></div><h3>{room.name}</h3><RoomFacts room={room}/><Link className="sp-button sp-button--primary" to={`/rooms/${room.id}`}>Book a room <ArrowUpRight aria-hidden="true"/></Link></article>)}</div><Pagination page={data} previous={paging.previous} onPrevious={paging.back} onNext={() => paging.next(data.nextLink)}/></>;
}
function RoomFacts({ room }: { room: Room }) { return <dl className="room-facts"><div><dt>Capacity</dt><dd><Users aria-hidden="true" size={17}/>{room.capacity} people</dd></div><div><dt>Hourly rate</dt><dd>{room.hourlyRate} / hour</dd></div></dl>; }

function RoomDetails() {
  const { id } = useParams();
  const [refresh, setRefresh] = useState(0);
  const { data: room, error } = useResource<Room>(`/api/rooms/${encodeURIComponent(id!)}`, refresh);
  if (error) return <Failure error={error} retry={() => setRefresh(r => r + 1)}/>;
  if (!room) return <Loading/>;
  return <><Link className="back-link" to={`/spaces/${room.spaceId}`}><ArrowLeft aria-hidden="true" size={18}/>{room.spaceName}</Link>
    <div className="room-detail-layout"><section className="room-introduction"><p className="eyebrow">A ROOM FOR YOUR PLANS</p><h1>{room.name}</h1><p className="room-space-name">{room.spaceName}</p><RoomFacts room={room}/><div className="booking-note"><CalendarDays aria-hidden="true"/><p>Availability is checked when you book. Your room is reserved once your booking is confirmed.</p></div><p className="small text-secondary">For currency and payment details, contact the space.</p></section>
      <section className="booking-panel"><p className="eyebrow">MAKE IT A DATE</p><h2>Book this room</h2><BookingForm key={room.id} room={room}/></section>
    </div><AvailabilityPanel key={room.id} roomId={room.id}/>
  </>;

}

function BookingForm({ room }: { room: Room }) {
  const auth = useAuth();
  const user = auth.user;
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
    if (!user) problems.push('Sign in to book this room.');
    if (!start || !end) problems.push('Enter both start and end times.');
    if (start && new Date(start).getTime() <= Date.now()) problems.push('Start time must be in the future.');
    if (start && end && end <= start) problems.push('End time must be after start time.');
    if (start && end && exceedsBookingDuration(start, end)) problems.push(BOOKING_DURATION_MESSAGE);
    setValidation(problems);
    if (problems.length) return;
    setPending(true);
    try {
      const saved = await request<Booking>('/api/bookings', { method: 'POST', body: JSON.stringify({ roomId: room.id, startTime: start, endTime: end }) });
      if (saved.status === 'CONFIRMED') usageAnalytics.bookingSuccess();
      setBooking(saved);
    } catch (e) { setError(e as Error); }
    finally { setPending(false); }
  }
  if (auth.loading) return <Loading/>;
  if (!user) return <p>Sign in to book this room. <Link to="/login">Sign in with Google</Link></p>;
  return <form noValidate onSubmit={submit} aria-describedby="booking-help"><p id="booking-help" className="booking-help">Booking as {user.name}. Times are shown as entered. Book up to 12 hours at a time.</p><fieldset disabled={pending} className="booking-fields"><legend className="visually-hidden">Booking details</legend><label htmlFor="start" className="form-label mt-3">Start time</label><input id="start" className="form-control" type="datetime-local" required value={start} onChange={e => setStart(e.target.value)}/><label htmlFor="end" className="form-label mt-3">End time</label><input id="end" className="form-control" type="datetime-local" min={start || undefined} max={bookingEndLimit(start)} required value={end} onChange={e => setEnd(e.target.value)}/><button className="sp-button sp-button--primary mt-4" disabled={pending || !!booking}>{pending ? 'Booking…' : booking ? 'Booking confirmed' : 'Confirm booking'}</button></fieldset><div ref={result} tabIndex={-1} className="mt-3">{pending && <p role="status">Confirming your room…</p>}{validation.length > 0 && <div role="alert" className="alert alert-danger"><ul className="mb-0">{validation.map(message => <li key={message}>{message}</li>)}</ul></div>}{error && <Failure error={error}/>} {booking && <div role="status" className="alert alert-success"><h3 className="h5">Booking #{booking.id} saved</h3><p>{booking.roomName} · {booking.userName}</p><p>{booking.startTime.replace('T', ' ')} → {booking.endTime.replace('T', ' ')}</p><BookingStatus status={booking.status}/><button type="button" className="sp-button sp-button--secondary" onClick={() => { setBooking(undefined); setStart(''); setEnd(''); }}>Book again</button></div>}</div></form>;
}

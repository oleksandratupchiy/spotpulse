import { Sparkles, Settings2 } from 'lucide-react';
import { useState, type FormEvent } from 'react';
import { request, type Space, type FeatureFlag, type OffsetPage, type PremiumState, type RoomPerformance } from './api';
import { Failure, Loading, Pagination, usePage, useResource } from './components';

export function PremiumBadge({ space }: { space: Space }) {
  return space.featured && space.premiumBadge ? <span className="featured-badge"><Sparkles aria-hidden="true"/> Featured</span> : null;
}

const featureTitles: Record<string, string> = {
  PREMIUM_FEATURES_ENABLED: 'Premium features', FEATURED_SPACES_ENABLED: 'Featured spaces',
  ADVANCED_ANALYTICS_ENABLED: 'Room analytics', PREMIUM_BADGES_ENABLED: 'Premium badges',
};

export function PremiumAdministration() {
  const [refresh, setRefresh] = useState(0);
  const [pending, setPending] = useState(false);
  const [failure, setFailure] = useState<Error>();
  const [message, setMessage] = useState('');
  const flags = useResource<FeatureFlag[]>('/api/admin/features', refresh);
  const page = usePage('/api/management/spaces?skip=0&limit=10');
  const spaces = useResource<OffsetPage<Space>>(page.path, refresh);
  async function toggle(path: string, enabled: boolean, label: string) {
    if (!window.confirm(`${enabled ? 'Enable' : 'Disable'} ${label}?`)) return;
    setPending(true); setFailure(undefined); setMessage('');
    try {
      await request(path, { method: 'PUT', body: JSON.stringify({ enabled }) });
      setRefresh(v => v + 1); setMessage(`${label} ${enabled ? 'enabled' : 'disabled'}.`);
    } catch (error) { setFailure(error as Error); } finally { setPending(false); }
  }
  return <section className="premium-administration" aria-labelledby="premium-admin-title"><p className="eyebrow"><Settings2 aria-hidden="true" size={16}/> PLATFORM SETTINGS</p><h2 id="premium-admin-title" className="h3">Feature flags and premium access</h2>
    <p>Premium is an administrator-managed demonstration tier. No purchases or payments are involved. The master switch controls all premium functionality.</p>
    {failure && <Failure error={failure}/>}<p role="status">{message}</p>
    {flags.error ? <Failure error={flags.error} retry={() => setRefresh(v => v + 1)}/> : !flags.data ? <Loading/> : <div className="feature-grid">{Array.isArray(flags.data) && flags.data.map(flag => <div key={flag.key} className="feature-cell"><article className="card card-body h-100 feature-card"><h3 className="h5">{featureTitles[flag.key] || flag.key}</h3><code className="feature-key">{flag.key}</code><p>{flag.description}</p><p>State: <strong>{flag.enabled ? 'Enabled' : 'Disabled'}</strong></p><button type="button" aria-label={`${flag.enabled ? 'Disable' : 'Enable'} ${flag.key}`} aria-pressed={flag.enabled} disabled={pending} className="sp-button sp-button--secondary align-self-start" onClick={() => toggle(`/api/admin/features/${flag.key}`, !flag.enabled, flag.key)}>{flag.enabled ? 'Disable' : 'Enable'} <span className="visually-hidden">{featureTitles[flag.key]}</span></button></article></div>)}</div>}
    <h3 className="h4 mt-4">Space entitlements</h3>
    {spaces.error ? <Failure error={spaces.error} retry={() => setRefresh(v => v + 1)}/> : !spaces.data ? <Loading/> : <>
      {!spaces.data.items.length && <p>No spaces found.</p>}
      {spaces.data.items.map(space => <article className="entitlement-row" key={space.id}><div><h4 className="h5">{space.name}</h4><p>{space.city} · Space #{space.id} · Premium entitlement: <strong>{space.premium ? 'Active' : 'None'}</strong></p></div><button type="button" disabled={pending} className="sp-button sp-button--secondary align-self-start" onClick={() => toggle(`/api/admin/spaces/${space.id}/premium`, !space.premium, `premium access for ${space.name}`)}>{space.premium ? 'Revoke' : 'Grant'} premium for {space.name}</button></article>)}
      <Pagination page={spaces.data} previous={page.previous} onPrevious={page.back} onNext={() => page.next(spaces.data!.nextLink)}/>
    </>}
  </section>;
}

export function SpacePremium({ space }: { space: Space }) {
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<PremiumState>('/api/management/premium-features', refresh);
  return <section className="premium-panel" aria-label={`Premium access for ${space.name}`}>
    <p className="eyebrow"><Sparkles aria-hidden="true" size={15}/> A LITTLE EXTRA</p><h3 className="h5">Premium access: {space.premium ? 'Active' : 'Not entitled'}</h3>
    {!space.premium ? <p>Ask an administrator to grant premium access to this space. There is no self-service purchase.</p>
      : error ? <Failure error={error} retry={() => setRefresh(v => v + 1)}/>
      : !data ? <Loading/>
      : !data.analytics ? <p>Advanced analytics is currently disabled by the administrator. Your entitlement is retained.</p>
      : <PremiumRoomAnalytics space={space}/>}
  </section>;
}

export function PremiumRoomAnalytics({ space }: { space: Space }) {
  const today = new Date().toISOString().slice(0, 10);
  const [range, setRange] = useState({ from: today.slice(0, 7) + '-01', to: today });
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<RoomPerformance>(`/api/analytics/spaces/${space.id}/room-performance?from=${range.from}&to=${range.to}`, refresh);
  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const fields = new FormData(event.currentTarget);
    setRange({ from: String(fields.get('from')), to: String(fields.get('to')) }); setRefresh(v => v + 1);
  }
  return <><h4 className="h5">Advanced room performance</h4><p>Compare confirmed bookings and cancellation rates for every room, including rooms with no bookings. Counts use booking start dates, inclusive dates and stored calendar time. Maximum range: 366 days.</p>
    <form onSubmit={submit} className="row g-2 align-items-end"><div className="col-md-5"><label className="form-label" htmlFor={`premium-from-${space.id}`}>From</label><input id={`premium-from-${space.id}`} name="from" className="form-control" type="date" required defaultValue={range.from}/></div><div className="col-md-5"><label className="form-label" htmlFor={`premium-to-${space.id}`}>To</label><input id={`premium-to-${space.id}`} name="to" className="form-control" type="date" required defaultValue={range.to}/></div><div className="col-md-2"><button className="sp-button sp-button--primary">Apply</button></div></form>
    {error ? <Failure error={error} retry={() => setRefresh(v => v + 1)}/> : !data ? <Loading/> : <>
      <div className="table-responsive mt-3" role="region" aria-label="Room performance results" tabIndex={0}><table className="table"><caption>Room performance for {space.name}: {data.from} to {data.to}. Cancellation rate = cancelled / all bookings.</caption><thead><tr><th scope="col">Room</th><th scope="col">Confirmed</th><th scope="col">Cancelled</th><th scope="col">Cancellation rate</th></tr></thead><tbody>{data.rooms.map(room => <tr key={room.roomId}><th scope="row">{room.roomName}</th><td>{room.confirmed}</td><td>{room.cancelled}</td><td>{room.confirmed + room.cancelled === 0 ? 'No bookings' : `${room.cancellationRate.toFixed(1)}%`}</td></tr>)}</tbody></table></div>
      {!data.rooms.length && <p>No rooms in this space yet.</p>}
    </>}
  </>;
}

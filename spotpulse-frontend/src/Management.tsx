import { useState, type FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { request, type Space, type Booking, type OffsetPage, type User } from './api';
import { Failure, Loading, Pagination, SpaceImage, usePage, useResource } from './components';
import { ImagePicker } from './ImagePicker';
import { attachSpaceImage } from './images';
import { useAuth } from './Auth';
import { PremiumAdministration, SpacePremium } from './Premium';
import { usageAnalytics } from './usageAnalytics';

export function Bookings({ managed = false }: { managed?: boolean }) {
  const page = usePage(`/api/${managed ? 'management/' : ''}bookings?skip=0&limit=10`);
  const [refresh, setRefresh] = useState(0); const [failure, setFailure] = useState<Error>(); const [pending, setPending] = useState<number>();
  const { data, error } = useResource<OffsetPage<Booking>>(page.path, refresh);
  return <section className="mt-4"><h1 className={managed ? 'h3' : ''}>{managed ? 'Space bookings' : 'My bookings'}</h1>
    <p>Confirmed bookings can be cancelled before their start time. Cancellation releases availability.</p>
    {failure && <Failure error={failure}/>}
    {error ? <Failure error={error} retry={() => setRefresh(v => v + 1)}/> : !data ? <Loading/> : <>
      {!data.items.length && <p>No bookings found.</p>}
      <div className="row g-3">{data.items.map(booking => <article key={booking.id} className="col-md-6"><div className="card card-body h-100"><h2 className="h5"><Link to={`/rooms/${booking.roomId}`}>{booking.roomName}</Link> · #{booking.id}</h2>
        <p>{booking.startTime.replace('T', ' ')} → {booking.endTime.replace('T', ' ')}</p><p>Status: {booking.status}</p>
        {managed && <p>Booked by: {booking.userName}</p>}
        <button className="btn btn-outline-danger align-self-start" disabled={pending !== undefined || booking.status === 'CANCELLED' || new Date(booking.startTime) <= new Date()} onClick={async () => {
          setPending(booking.id); setFailure(undefined);
          try {
            const saved = await request<Booking>(`/api/bookings/${booking.id}/cancel`, { method: 'POST' });
            if (saved.status === 'CANCELLED') usageAnalytics.bookingCancelled();
            setRefresh(v => v + 1);
          }
          catch (error) { setFailure(error as Error); } finally { setPending(undefined); }
        }}>{pending === booking.id ? 'Cancelling…' : 'Cancel booking'}</button></div></article>)}</div>
      <Pagination page={data} previous={page.previous} onPrevious={page.back} onNext={() => page.next(data.nextLink)}/>
    </>}
  </section>;
}

export function Management() {
  const page = usePage('/api/management/spaces?skip=0&limit=10');
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<OffsetPage<Space>>(page.path, refresh);
  return <><section className="hero"><h1>Manage coworking spaces</h1><p>Owners manage their spaces; administrators can manage all spaces.</p><Link className="btn btn-primary" to="/owner/spaces/new">Create space</Link> <Link to="/analytics">Booking analytics</Link></section>
    {error ? <Failure error={error} retry={() => setRefresh(v => v + 1)}/> : !data ? <Loading/> : <>
      {!data.items.length && <p>No managed spaces yet.</p>}
      <div className="row g-3 mt-2">{data.items.map(space => <article className="col-md-6" key={space.id}><div className="card card-body"><h2 className="h5">{space.name}</h2><p>{space.city} · {space.address}</p><Link to={`/owner/spaces/${space.id}`}>Edit space and rooms</Link><SpacePremium space={space}/></div></article>)}</div>
      <Pagination page={data} previous={page.previous} onPrevious={page.back} onNext={() => page.next(data.nextLink)}/>
    </>}
    <Bookings managed/>
  </>;
}

export function SpaceEditor() {
  const { id } = useParams();
  const [refresh, setRefresh] = useState(0);
  // A separate component avoids fetching a fake ID on the create route.
  return <><Link to="/owner">← Manage spaces</Link>{id ? <ExistingSpace id={id} refresh={refresh} changed={() => setRefresh(v => v + 1)}/> : <SpaceForm/>}</>;
}
function ExistingSpace({ id, refresh, changed }: { id: string; refresh: number; changed: () => void }) {
  const { data, error } = useResource<Space>(`/api/management/spaces/${encodeURIComponent(id)}`, refresh);
  if (error) return <Failure error={error} retry={changed}/>;
  if (!data) return <Loading/>;
  return <><SpaceForm key={refresh} space={data} changed={changed}/><SpacePremium space={data}/><RoomEditor space={data} changed={changed}/></>;
}

export function SpaceForm({ space, changed }: { space?: Space; changed?: () => void }) {
  const navigate = useNavigate(); const [error, setError] = useState<Error>(); const [pending, setPending] = useState(false);
  const [image, setImage] = useState<File>(); const [validating, setValidating] = useState(false);
  const [savedId, setSavedId] = useState<number | undefined>(space?.id);
  const [attachmentPending, setAttachmentPending] = useState(false);
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); const fields = new FormData(event.currentTarget);
    if (pending || validating) return;
    const body: Record<string, unknown> = Object.fromEntries(fields.entries());
    // Attaching a selected file is a separate protected operation. Keep the current image until it succeeds.
    if (image) body.imageUrl = space?.imageUrl || '';
    for (const key of ['latitude', 'longitude']) body[key] = fields.get(key) === '' ? null : Number(fields.get(key));
    setPending(true); setError(undefined);
    try {
      const saved = await request<Space>(savedId ? `/api/spaces/${savedId}` : '/api/spaces', { method: savedId ? 'PUT' : 'POST', body: JSON.stringify(body) });
      setSavedId(saved.id);
      if (image) {
        setAttachmentPending(true);
        try { await attachSpaceImage(saved.id, image); setAttachmentPending(false); }
        catch (failure) { throw new Error(`Space #${saved.id} was saved, but the image was not attached. ${(failure as Error).message} Your selection is retained; Save space retries on the same space.`); }
      }
      if (!space) navigate(`/owner/spaces/${saved.id}`); else changed?.();
    } catch (error) { setError(error as Error); } finally { setPending(false); }
  }
  return <section className="card card-body p-4 mt-4"><h1 className="h3">{space ? 'Edit space' : 'Create coworking space'}</h1>
    {error && <Failure error={error}/>}
    {attachmentPending && <p role="status">{pending ? 'Uploading selected image…' : `The image still needs to be attached to saved space #${savedId}.`}</p>}
    {space && <SpaceImage key={space.imageUrl} url={space.imageUrl} name={space.name}/>}
    <form onSubmit={submit}><fieldset disabled={pending}><legend className="visually-hidden">Space details</legend><div className="row g-3">
      {(['name', 'city', 'address', 'imageUrl', 'contactEmail'] as const).map(field => <div className="col-md-6" key={field}><label htmlFor={`space-${field}`} className="form-label">{{ name: 'Space name', city: 'City', address: 'Address', imageUrl: 'Image URL', contactEmail: 'Public contact email' }[field]}</label><input id={`space-${field}`} name={field} className="form-control" maxLength={field === 'contactEmail' ? 254 : 255} type={field === 'contactEmail' ? 'email' : 'text'} required={['name', 'city', 'address'].includes(field)} defaultValue={space?.[field] || ''}/></div>)}
      <ImagePicker onChange={setImage} onValidating={setValidating}/>
      <div className="col-12"><label htmlFor="space-description" className="form-label">Description</label><textarea id="space-description" name="description" maxLength={2000} className="form-control" defaultValue={space?.description || ''}/></div>
      {(['latitude', 'longitude'] as const).map(field => <div className="col-sm-6" key={field}><label htmlFor={`space-${field}`} className="form-label">{field === 'latitude' ? 'Latitude' : 'Longitude'} (optional)</label><input id={`space-${field}`} className="form-control" name={field} type="number" step="any" min={field === 'latitude' ? -90 : -180} max={field === 'latitude' ? 90 : 180} defaultValue={space?.[field] ?? ''}/></div>)}
    </div><p className="small mt-3">Only enter independently confirmed coordinates, as a pair. Changing the address without coordinates clears the old location.</p><button className="btn btn-primary mt-2" disabled={validating}>{pending ? 'Saving…' : 'Save space'}</button></fieldset></form>
    {space && <button className="btn btn-outline-danger mt-4 align-self-start" disabled={pending} onClick={async () => {
      if (!window.confirm('Delete this space, its rooms and bookings?')) return;
      setPending(true); try { await request(`/api/spaces/${space.id}`, { method: 'DELETE' }); navigate('/owner'); } catch (error) { setError(error as Error); setPending(false); }
    }}>Delete space</button>}
  </section>;
}

function RoomEditor({ space, changed }: { space: Space; changed: () => void }) {
  const [editing, setEditing] = useState<Space['rooms'][number]>(); const [error, setError] = useState<Error>(); const [pending, setPending] = useState(false);
  return <section className="card card-body p-4 mt-4"><h2 className="h3">Meeting rooms</h2>{error && <Failure error={error}/>}
    <ul>{space.rooms.map(room => <li className="mb-3" key={room.id}>{room.name} · {room.capacity} people · {room.hourlyRate} / hour <button className="btn btn-sm btn-outline-primary ms-2" onClick={() => setEditing(room)}>Edit room</button> <button className="btn btn-sm btn-outline-danger" disabled={pending} onClick={async () => {
      if (!window.confirm('Delete this room and its bookings?')) return;
      setPending(true); try { await request(`/api/rooms/${room.id}`, { method: 'DELETE' }); changed(); } catch (error) { setError(error as Error); } finally { setPending(false); }
    }}>Delete room</button></li>)}</ul>
    <h3 className="h5">{editing ? `Edit ${editing.name}` : 'Add room'}</h3>
    <form key={editing?.id ?? 'new'} onSubmit={async event => {
      event.preventDefault(); const fields = new FormData(event.currentTarget); setError(undefined); setPending(true);
      try { await request(editing ? `/api/rooms/${editing.id}` : '/api/rooms', { method: editing ? 'PUT' : 'POST', body: JSON.stringify({ name: fields.get('name'), capacity: Number(fields.get('capacity')), hourlyRate: Number(fields.get('rate')), spaceId: space.id }) }); setEditing(undefined); changed(); }
      catch (error) { setError(error as Error); } finally { setPending(false); }
    }}><fieldset disabled={pending}><div className="row g-3"><div className="col-md-6"><label htmlFor="room-name" className="form-label">Room name</label><input id="room-name" name="name" required maxLength={255} className="form-control" defaultValue={editing?.name}/></div><div className="col-md-3"><label htmlFor="room-capacity" className="form-label">Capacity</label><input id="room-capacity" name="capacity" type="number" min={1} required className="form-control" defaultValue={editing?.capacity ?? 4}/></div><div className="col-md-3"><label htmlFor="room-rate" className="form-label">Hourly rate</label><input id="room-rate" name="rate" type="number" min={0} step="0.01" required className="form-control" defaultValue={editing?.hourlyRate ?? 0}/></div></div><button className="btn btn-primary mt-3">Save room</button> {editing && <button className="btn btn-outline-secondary mt-3" type="button" onClick={() => setEditing(undefined)}>Cancel edit</button>}</fieldset></form>
  </section>;
}

export function Administration() {
  const auth = useAuth();
  const [message, setMessage] = useState('');
  const page = usePage('/api/users?skip=0&limit=10'); const [refresh, setRefresh] = useState(0); const [failure, setFailure] = useState<Error>(); const [pending, setPending] = useState(false);
  const { data, error } = useResource<OffsetPage<User>>(page.path, refresh);
  return <><h1>Platform administration</h1><p>Manage user roles and assign legacy spaces after independently verifying ownership.</p><p><Link to="/owner">Manage all spaces and bookings</Link> · <Link to="/analytics">Platform analytics</Link></p>
    <PremiumAdministration/>{failure && <Failure error={failure}/>}{message && <p role="status">{message}</p>}
    {error ? <Failure error={error} retry={() => setRefresh(v => v + 1)}/> : !data ? <Loading/> : <><h2 className="h3">Users · {data.total}</h2>
      {data.items.map(user => <form key={user.id} className="card card-body mb-3" onSubmit={async event => {
        event.preventDefault(); const fields = new FormData(event.currentTarget); setPending(true); setFailure(undefined);
        try { await request(`/api/users/${user.id}`, { method: 'PUT', body: JSON.stringify({ name: user.name, email: user.email, role: fields.get('role') }) }); setRefresh(v => v + 1); setMessage('Role saved.'); auth.refresh(); }
        catch (error) { setFailure(error as Error); } finally { setPending(false); }
      }}><p>{user.name} · {user.email} · Account #{user.id}</p><label htmlFor={`role-${user.id}`} className="form-label">Role for {user.name}</label><select id={`role-${user.id}`} name="role" className="form-select" defaultValue={user.role}><option>USER</option><option>OWNER</option><option>ADMIN</option></select><button className="btn btn-primary mt-2 align-self-start" disabled={pending}>Save role</button></form>)}
      <Pagination page={data} previous={page.previous} onPrevious={page.back} onNext={() => page.next(data.nextLink)}/></>}
    <section className="card card-body mt-4"><h2 className="h3">Assign space ownership</h2><p>Legacy spaces remain unassigned until this explicit administrator action. Use a verified OWNER or ADMIN account ID.</p>
      <form onSubmit={async event => {
        event.preventDefault(); const fields = new FormData(event.currentTarget); setPending(true); setFailure(undefined);
        try { await request(`/api/admin/spaces/${fields.get('space')}/owner`, { method: 'PUT', body: JSON.stringify({ ownerId: Number(fields.get('owner')) }) }); setRefresh(v => v + 1); setMessage('Ownership assignment saved.'); }
        catch (error) { setFailure(error as Error); } finally { setPending(false); }
      }}><label htmlFor="assign-space" className="form-label">Space ID</label><input id="assign-space" name="space" type="number" min={1} required className="form-control"/><label htmlFor="assign-owner" className="form-label mt-2">Owner account ID</label><input id="assign-owner" name="owner" type="number" min={1} required className="form-control"/><button className="btn btn-primary mt-3" disabled={pending}>Assign owner</button></form>
    </section>
  </>;
}

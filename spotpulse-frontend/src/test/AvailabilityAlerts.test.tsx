import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { AvailabilitySubscription, MyAvailabilityAlerts, type AvailabilityAlert } from '../AvailabilityAlerts';
import { AvailabilityPanel } from '../AvailabilityPanel';

const session = vi.hoisted(() => ({ user: { id: 7, role: 'USER' } as { id: number; role: string } | null, loading: false }));
vi.mock('../Auth', () => ({ useAuth: () => session }));
const from = '2030-01-01T10:00:00', to = '2030-01-01T12:00:00';
const saved: AvailabilityAlert = { id: 42, roomId: 3, roomName: 'Blue room', spaceName: 'Hub', city: 'Kyiv', startTime: from, endTime: to,
  status: 'ACTIVE', createdAt: '2026-10-01T00:00:00Z', notifiedAt: null, deliveryReason: null };
let records: AvailabilityAlert[];
function json(body: unknown, status = 200) { return new Response(JSON.stringify(body), { status }); }
function mockApi() {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    const url = String(input);
    if (url === '/api/auth/csrf') return json({ token: 'synthetic', headerName: 'X-CSRF-TOKEN' });
    if (url === '/api/account/telegram') return json({ configured: true, connected: false, available: false, bookingNotificationsEnabled: true });
    if (url.startsWith('/api/rooms/')) return json({ roomId: 3, from, to, occupied: [{ start: from, end: to }] });
    if (options?.method === 'POST') { records = [saved]; return json(saved, 201); }
    if (options?.method === 'DELETE') { records = []; return new Response(null, { status: 204 }); }
    return json({ items: records, total: records.length, skip: 0, limit: 10, nextLink: null });
  });
}
function open() { return render(<MemoryRouter><AvailabilitySubscription roomId={3} from={from} to={to} occupied/></MemoryRouter>); }
beforeEach(() => { records = []; session.user = { id: 7, role: 'USER' }; session.loading = false; });

test('saves through CSRF-protected API, honestly explains disconnected Telegram and cancels', async () => {
  const fetch = mockApi(); open();
  expect(await screen.findByText(/Telegram is not linked/)).toBeVisible();
  await userEvent.click(await screen.findByRole('button', { name: 'Notify me when available' }));
  expect(await screen.findByText(/You're watching this time slot/)).toBeVisible();
  expect(fetch).toHaveBeenCalledWith('/api/availability-alerts', expect.objectContaining({ method: 'POST',
    body: JSON.stringify({ roomId: 3, startTime: from, endTime: to }), headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'synthetic' }) }));
  await userEvent.click(screen.getByRole('button', { name: 'Cancel alert' }));
  expect(await screen.findByRole('button', { name: 'Notify me when available' })).toBeEnabled();
  expect(records).toEqual([]);
});
test('restores confirmed subscription after unmount and new mount without another POST', async () => {
  const fetch = mockApi(); const view = open();
  await userEvent.click(await screen.findByRole('button', { name: 'Notify me when available' }));
  await screen.findByText(/You're watching this time slot/); view.unmount();
  open(); expect(await screen.findByRole('button', { name: 'Cancel alert' })).toBeVisible();
  expect(fetch.mock.calls.filter(([, options]) => options?.method === 'POST')).toHaveLength(1);
});
test('restores the saved room interval and subscribed state on a fresh room page', async () => {
  records = [saved]; mockApi();
  render(<MemoryRouter><AvailabilityPanel roomId={3}/></MemoryRouter>);
  expect(await screen.findByText(/You're watching this time slot/)).toBeVisible();
  expect(screen.getByLabelText('Availability from')).toHaveValue('2030-01-01T10:00');
  expect(screen.getByLabelText('Availability to')).toHaveValue('2030-01-01T12:00');
});
test('loading retrieval and pending creation never show an optimistic subscribed state', async () => {
  let respond!: (response: Response) => void;
  const fetch = mockApi(); const normal = fetch.getMockImplementation()!;
  fetch.mockImplementation((input, options) => String(input).startsWith('/api/availability-alerts?')
    ? new Promise(resolve => { respond = resolve; }) : normal(input, options));
  open(); expect(screen.getByRole('status')).toHaveTextContent('Loading saved alert');
  expect(screen.queryByRole('button', { name: 'Notify me when available' })).not.toBeInTheDocument();
  await act(async () => respond(json({ items: [], nextLink: null })));
  fetch.mockImplementation((input, options) => options?.method === 'POST' ? new Promise(resolve => { respond = resolve; }) : normal(input, options));
  await userEvent.click(screen.getByRole('button', { name: 'Notify me when available' }));
  expect(screen.getByRole('button', { name: 'Saving alert…' })).toBeDisabled();
  expect(screen.queryByText(/You're watching/)).not.toBeInTheDocument();
  await act(async () => respond(json(saved, 201)));
  expect(screen.getByText(/You're watching/)).toBeVisible();
});
test('failed creation stays unsubscribed and can retry after reloading backend state', async () => {
  const fetch = mockApi(); const normal = fetch.getMockImplementation()!;
  fetch.mockImplementation((input, options) => options?.method === 'POST' ? Promise.resolve(json({}, 503)) : normal(input, options));
  open(); await userEvent.click(await screen.findByRole('button', { name: 'Notify me when available' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('server error');
  expect(screen.queryByText(/You're watching/)).not.toBeInTheDocument();
  fetch.mockImplementation(normal);
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
  await userEvent.click(await screen.findByRole('button', { name: 'Notify me when available' }));
  expect(await screen.findByText(/You're watching/)).toBeVisible();
});
test('anonymous visitors must sign in and never request or create account subscriptions', async () => {
  session.user = null; const fetch = mockApi(); open();
  expect(screen.getByRole('link', { name: 'Sign in to create an availability alert' })).toHaveAttribute('href', '/login');
  expect(screen.queryByRole('button', { name: 'Notify me when available' })).not.toBeInTheDocument();
  expect(fetch).not.toHaveBeenCalled();
});
test('available interval has no unnecessary subscribe action', async () => {
  mockApi(); render(<MemoryRouter><AvailabilitySubscription roomId={3} from={from} to={to} occupied={false}/></MemoryRouter>);
  await waitFor(() => expect(screen.queryByText('Loading saved alert…')).not.toBeInTheDocument());
  expect(screen.queryByRole('button', { name: 'Notify me when available' })).not.toBeInTheDocument();
});
test('account management lists persisted alerts and cancels through the backend', async () => {
  records = [saved]; mockApi(); render(<MemoryRouter><MyAvailabilityAlerts/></MemoryRouter>);
  expect(await screen.findByRole('link', { name: 'Blue room' })).toHaveAttribute('href', '/rooms/3');
  await userEvent.click(screen.getByRole('button', { name: 'Cancel alert' }));
  expect(await screen.findByText('No availability alerts yet.')).toBeVisible();
});

test('a late status response cannot restore an alert after cancellation succeeds', async () => {
  vi.useFakeTimers(); records = [saved]; const fetch = mockApi(); const normal = fetch.getMockImplementation()!;
  const view = open();
  try {
    await act(async () => {});
    expect(screen.getByRole('button', { name: 'Cancel alert' })).toBeVisible();
    let respond!: (response: Response) => void;
    fetch.mockImplementation((input, options) => String(input).startsWith('/api/availability-alerts?')
      ? new Promise(resolve => { respond = resolve; }) : normal(input, options));
    await act(async () => { await vi.advanceTimersByTimeAsync(15000); });
    await act(async () => { fireEvent.click(screen.getByRole('button', { name: 'Cancel alert' })); });
    expect(screen.queryByRole('button', { name: 'Cancel alert' })).not.toBeInTheDocument();
    await act(async () => respond(json({ items: [saved], nextLink: null })));
    expect(screen.queryByRole('button', { name: 'Cancel alert' })).not.toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Notify me when available' })).toBeEnabled();
  } finally { view.unmount(); vi.useRealTimers(); }
});

import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { usageAnalytics } from '../usageAnalytics';

configure({ asyncUtilTimeout: 10_000 });
const space = { id: 3, name: 'Managed Hub', city: 'Kyiv', address: 'Street 1', description: '', imageUrl: null, contactEmail: '', latitude: null, longitude: null, rooms: [{ id: 8, name: 'Oak', capacity: 4, hourlyRate: 10, spaceId: 3, spaceName: 'Managed Hub' }] };
const page = (items: unknown[]) => ({ items, total: items.length, skip: 0, limit: 10, nextLink: null });
function mockApi(role: string | null, override?: (url: string, options?: RequestInit) => Response | undefined) {
  let cancelled = false;
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    const url = String(input); const custom = override?.(url, options); if (custom) return custom;
    if (url === '/api/auth/me') return new Response(JSON.stringify({ authenticated: !!role, user: role ? { id: 1, name: 'Signed member', email: 'member@example.test', role } : null, loginEnabled: true }));
    if (url === '/api/auth/csrf') return new Response(JSON.stringify({ headerName: 'X-CSRF-TOKEN', token: 'masked-test-token' }));
    if (url === '/api/account/telegram') return new Response(JSON.stringify({ configured: false, available: false, connected: false, expiresAt: null, bookingNotificationsEnabled: true }));
    if (url === '/api/auth/logout') return new Response(null, { status: 204 });
    if (url.endsWith('/cancel')) { cancelled = true; return new Response(JSON.stringify({ status: 'CANCELLED' })); }
    if (url.startsWith('/api/users')) return new Response(JSON.stringify(page([{ id: 42, name: 'Other member', email: 'other@example.test', role: 'USER' }])));
    if (url.startsWith('/api/bookings?') || url.startsWith('/api/management/bookings?')) return new Response(JSON.stringify(page([{ id: 9, roomId: 8, roomName: 'Oak', userId: 1, userName: 'Signed member', startTime: `${new Date().getFullYear()+1}-01-01T10:00:00`, endTime: `${new Date().getFullYear()+1}-01-01T11:00:00`, status: cancelled ? 'CANCELLED' : 'CONFIRMED' }])));
    if (url === '/api/management/spaces/3' || (url === '/api/spaces' && options?.method === 'POST')) return new Response(JSON.stringify(space));
    if (url.startsWith('/api/management/spaces?') || url.startsWith('/api/spaces?')) return new Response(JSON.stringify(page([space])));
    return new Response('{}');
  });
}
function open(path: string) { return render(<MemoryRouter initialEntries={[path]}><App/></MemoryRouter>); }

test('public visitor can discover spaces and reach the real Google login entry', async () => {
  mockApi(null); open('/login');
  expect(await screen.findByRole('link', { name: 'Continue with Google' })).toHaveAttribute('href', 'http://localhost:8080/oauth2/authorization/google');
  expect(screen.queryByRole('link', { name: 'Administration' })).not.toBeInTheDocument();
});

test('profile shows session identity and logout uses CSRF and clears authenticated interfaces', async () => {
  const fetch = mockApi('USER'); open('/account');
  expect(await screen.findByText('member@example.test')).toBeVisible();
  expect(screen.getByText('Role: USER')).toBeVisible();
  await screen.findByText('Telegram is not connected.');
  await userEvent.click(screen.getByRole('button', { name: 'Sign out' }));
  expect(await screen.findByRole('heading', { name: 'Sign in to continue' })).toBeVisible();
  expect(fetch).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST', credentials: 'same-origin', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'masked-test-token' }) }));
});

test.each(['/owner', '/admin', '/analytics'])('USER cannot open management route %s', async path => {
  const fetch = mockApi('USER'); open(path);
  expect(await screen.findByRole('heading', { name: 'Access denied' })).toBeVisible();
  expect(fetch.mock.calls.some(([url]) => /\/api\/(users|management|analytics)/.test(String(url)))).toBe(false);
});

test('unauthenticated access to personal bookings asks for login', async () => {
  mockApi(null); open('/bookings');
  expect(await screen.findByRole('heading', { name: 'Sign in to continue' })).toBeVisible();
});

test('OWNER dashboard uses protected owned-space and booking endpoints', async () => {
  const fetch = mockApi('OWNER'); open('/owner');
  expect(await screen.findByRole('heading', { name: 'Managed Hub' })).toBeVisible();
  expect(screen.getByRole('link', { name: 'Create space' })).toHaveAttribute('href', '/owner/spaces/new');
  expect(screen.queryByRole('link', { name: 'Administration' })).not.toBeInTheDocument();
  expect(fetch).toHaveBeenCalledWith('/api/management/spaces?skip=0&limit=10', expect.anything());
  expect(fetch).toHaveBeenCalledWith('/api/management/bookings?skip=0&limit=10', expect.anything());
});

test('OWNER creates spaces without submitting an owner identity', async () => {
  const fetch = mockApi('OWNER'); open('/owner/spaces/new');
  await userEvent.type(await screen.findByLabelText('Space name'), 'New Hub');
  await userEvent.type(screen.getByLabelText('City'), 'Kyiv');
  await userEvent.type(screen.getByLabelText('Address'), 'Street 5');
  await userEvent.click(screen.getByRole('button', { name: 'Save space' }));
  await screen.findByRole('heading', { name: 'Edit space' });
  const post = fetch.mock.calls.find(([url, options]) => url === '/api/spaces' && options?.method === 'POST')!;
  expect(JSON.parse(String(post[1]?.body))).toMatchObject({ name: 'New Hub', city: 'Kyiv', address: 'Street 5' });
  expect(JSON.parse(String(post[1]?.body))).not.toHaveProperty('ownerId');
});

test('foreign-space denial from the backend is displayed instead of allowing edit', async () => {
  mockApi('OWNER', url => url === '/api/management/spaces/999' ? new Response(JSON.stringify({ detail: 'Access denied' }), { status: 403 }) : undefined);
  open('/owner/spaces/999');
  expect(await screen.findByRole('alert')).toHaveTextContent('Access denied');
  expect(screen.queryByRole('button', { name: 'Save space' })).not.toBeInTheDocument();
});

test('personal booking cancellation updates the list through authenticated CSRF POST', async () => {
  const analytics = vi.spyOn(usageAnalytics, 'bookingCancelled');
  const fetch = mockApi('USER'); open('/bookings');
  await userEvent.click(await screen.findByRole('button', { name: 'Cancel booking' }));
  expect(await screen.findByText('Status: CANCELLED')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Cancel booking' })).toBeDisabled();
  expect(fetch).toHaveBeenCalledWith('/api/bookings/9/cancel', expect.objectContaining({ method: 'POST', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'masked-test-token' }) }));
  expect(analytics).toHaveBeenCalledExactlyOnceWith();
});

test('failed cancellation does not emit an analytics success', async () => {
  const analytics = vi.spyOn(usageAnalytics, 'bookingCancelled');
  mockApi('USER', url => url.endsWith('/cancel') ? new Response(JSON.stringify({detail:'Cannot cancel'}),{status:409}) : undefined);
  open('/bookings'); await userEvent.click(await screen.findByRole('button',{name:'Cancel booking'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('Cannot cancel'); expect(analytics).not.toHaveBeenCalled();
});

test('ADMIN can submit explicit role changes and ownership assignment', async () => {
  const fetch = mockApi('ADMIN'); open('/admin');
  await userEvent.selectOptions(await screen.findByLabelText('Role for Other member'), 'OWNER');
  await userEvent.click(screen.getByRole('button', { name: 'Save role' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/users/42', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ name: 'Other member', email: 'other@example.test', role: 'OWNER' }) })));
  fireEvent.change(screen.getByLabelText('Space ID'), { target: { value: '3' } });
  fireEvent.change(screen.getByLabelText('Owner account ID'), { target: { value: '42' } });
  await userEvent.click(screen.getByRole('button', { name: 'Assign owner' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/admin/spaces/3/owner', expect.objectContaining({ method: 'PUT', body: JSON.stringify({ ownerId: 42 }) })));
});

test('legacy email collision is explained without automatic linking or signup role controls', async () => {
  mockApi(null); open('/login?error=account_migration_required');
  expect(await screen.findByRole('alert')).toHaveTextContent('Automatic linking is disabled');
  expect(screen.queryByLabelText(/Role/)).not.toBeInTheDocument();
});

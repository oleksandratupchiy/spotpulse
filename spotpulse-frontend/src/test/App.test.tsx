import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { usageAnalytics } from '../usageAnalytics';

const space = { id: 3, name: 'River Hub', city: 'Kyiv', address: 'River Street 7', description: 'Quiet work by the river', imageUrl: null, contactEmail: 'hello@river.test', rooms: [] };
const room = { id: 8, name: 'Oak room', capacity: 6, hourlyRate: 150, spaceId: 3, spaceName: 'River Hub' };
const existingUser = { id: 42, name: 'Alex', email: 'alex@example.test', role: 'USER' };
const page = (items: unknown[], extra = {}) => ({ items, skip: 0, limit: 6, total: items.length, nextLink: null, ...extra });
function mockApi(override?: (url: string, options?: RequestInit) => { status?: number; body: unknown } | undefined) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    const url = String(input);
    if (url === '/api/auth/me') return new Response(JSON.stringify({ authenticated: true, user: existingUser, loginEnabled: true }));
    if (url === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'test-csrf', headerName: 'X-CSRF-TOKEN' }));
    const replacement = override?.(url, options);
    const body = url.startsWith('/api/spaces?') ? page([space]) : url === '/api/spaces/3' ? space : url.startsWith('/api/rooms?') ? page([room]) : url === '/api/rooms/8' ? room : url.startsWith('/api/users?') ? page([existingUser]) : {};
    return new Response(JSON.stringify(replacement?.body ?? body), { status: replacement?.status ?? 200, headers: { 'Content-Type': 'application/json' } });
  });
}
function open(path = '/spaces') { return render(<MemoryRouter initialEntries={[path]}><App/></MemoryRouter>); }
async function fillBooking() {
  const user = userEvent.setup();
  await screen.findByText(/Booking as Alex/);
  const year = new Date().getFullYear() + 1;
  // datetime-local requires an ISO value; exercise React's change handler directly.
  const { fireEvent } = await import('@testing-library/react');
  fireEvent.change(screen.getByLabelText('Start time'), { target: { value: `${year}-11-01T10:00` } });
  fireEvent.change(screen.getByLabelText('End time'), { target: { value: `${year}-11-01T11:00` } });
  return user;
}

test('loads and renders real space fields', async () => {
  const fetch = mockApi(); open();
  expect(await screen.findByRole('heading', { name: 'River Hub' })).toBeVisible();
  expect(screen.getByText('River Street 7')).toBeVisible();
  expect(fetch).toHaveBeenCalledWith('/api/spaces?skip=0&limit=6', expect.objectContaining({ signal: expect.any(AbortSignal) }));
});
test('shows loading until the API responds', () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise(() => {})); open();
  expect(screen.getByRole('status')).toHaveTextContent('Loading');
});
test('handles server failure and supports retry', async () => {
  const fetch = mockApi(() => ({ status: 500, body: { detail: 'internal information' } })); open();
  expect(await screen.findByRole('alert')).toHaveTextContent('server error');
  expect(screen.queryByText('internal information')).not.toBeInTheDocument();
  fetch.mockRestore(); mockApi();
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByRole('heading', { name: 'River Hub' })).toBeVisible();
});
test('shows an empty result', async () => {
  mockApi(() => ({ body: page([]) })); open();
  expect(await screen.findByText(/No spaces found/)).toBeVisible();
});
test('follows nextLink exactly and returns to the previous page', async () => {
  const next = '/api/spaces?search=Hub&skip=6&limit=6';
  const fetch = mockApi(url => ({ body: url === next ? page([{ ...space, id: 4, name: 'Second Hub' }], { skip: 6, total: 7 }) : page([space], { nextLink: next, total: 7 }) })); open();
  await screen.findByText('River Street 7');
  await userEvent.click(screen.getByRole('button', { name: 'Next' }));
  expect(await screen.findByRole('heading', { name: 'Second Hub' })).toBeVisible();
  expect(fetch).toHaveBeenCalledWith(next, expect.anything());
  expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
  await userEvent.click(screen.getByRole('button', { name: 'Previous' }));
  expect(await screen.findByRole('heading', { name: 'River Hub' })).toBeVisible();
});
test('submits one supported search mode and resets pagination', async () => {
  const analytics = vi.spyOn(usageAnalytics, 'search');
  const fetch = mockApi(); open();
  await screen.findByText('River Street 7');
  await userEvent.selectOptions(screen.getByLabelText('Search by'), 'city');
  await userEvent.type(screen.getByLabelText('Search spaces'), 'Kyiv');
  await userEvent.click(screen.getByRole('button', { name: 'Search' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/spaces?skip=0&limit=6&city=Kyiv', expect.anything()));
  expect(analytics).toHaveBeenCalledExactlyOnceWith('city', true);
});
test('navigates from space list to dynamic details and room', async () => {
  mockApi(); open();
  await userEvent.click(await screen.findByRole('link', { name: 'View space' }));
  expect(await screen.findByText(space.description)).toBeVisible();
  expect(screen.getByRole('link', { name: space.contactEmail })).toHaveAttribute('href', 'mailto:' + space.contactEmail);
  await userEvent.click(await screen.findByRole('link', { name: 'View room & book' }));
  expect(await screen.findByRole('heading', { name: 'Oak room' })).toBeVisible();
  expect(screen.getByText('6 people')).toBeVisible();
  expect(screen.getByText('150 / hour')).toBeVisible();
  expect(screen.getByText(/Availability is checked/)).toBeVisible();
});
test('handles missing space with friendly 404', async () => {
  mockApi(() => ({ status: 404, body: { detail: 'Space not found' } })); open('/spaces/999');
  expect(await screen.findByRole('alert')).toHaveTextContent('Not found');
});
test('validates booking before sending a request', async () => {
  const fetch = mockApi(); open('/rooms/8');
  await userEvent.click(await screen.findByRole('button', { name: 'Confirm booking' }));
  expect(screen.getByText(/Booking as Alex/)).toBeVisible();
  expect(screen.queryByLabelText('Existing user')).not.toBeInTheDocument();
  expect(screen.getByRole('alert')).toHaveTextContent('Enter both start and end times');
  expect(fetch.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});
test('rejects reversed and past time intervals locally', async () => {
  const fetch = mockApi(); open('/rooms/8'); await fillBooking();
  const { fireEvent } = await import('@testing-library/react');
  fireEvent.change(screen.getByLabelText('Start time'), { target: { value: '2000-01-02T10:00' } });
  fireEvent.change(screen.getByLabelText('End time'), { target: { value: '2000-01-01T10:00' } });
  await userEvent.click(screen.getByRole('button', { name: 'Confirm booking' }));
  expect(screen.getByRole('alert')).toHaveTextContent('Start time must be in the future');
  expect(screen.getByRole('alert')).toHaveTextContent('End time must be after');
  expect(fetch.mock.calls.some(([, options]) => options?.method === 'POST')).toBe(false);
});
test('posts DTO fields and displays confirmed booking response', async () => {
  const analytics = vi.spyOn(usageAnalytics, 'bookingSuccess');
  const fetch = mockApi((url, options) => url === '/api/bookings' ? { status: 201, body: { ...JSON.parse(String(options?.body)), id: 91, roomName: room.name, userName: existingUser.name, status: 'CONFIRMED' } } : undefined);
  open('/rooms/8'); const user = await fillBooking();
  await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
  expect(await screen.findByText('Booking #91 saved')).toBeVisible();
  expect(screen.getByText('Status: CONFIRMED')).toBeVisible();
  const post = fetch.mock.calls.find(([, options]) => options?.method === 'POST')!;
  expect(JSON.parse(String(post[1]?.body))).toEqual({ roomId: 8, startTime: expect.stringContaining('T10:00'), endTime: expect.stringContaining('T11:00') });
  expect(screen.getByRole('button', { name: 'Booking confirmed' })).toBeDisabled();
  expect(analytics).toHaveBeenCalledExactlyOnceWith();
});
test('displays HTTP 409 without claiming a booking succeeded', async () => {
  const analytics = vi.spyOn(usageAnalytics, 'bookingSuccess');
  mockApi(url => url === '/api/bookings' ? { status: 409, body: { detail: 'Room is already booked for this time interval' } } : undefined);
  open('/rooms/8'); const user = await fillBooking();
  await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('Room is already booked');
  expect(screen.queryByText(/Booking #/)).not.toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Confirm booking' })).toBeEnabled();
  expect(analytics).not.toHaveBeenCalled();
});
test('displays backend field validation errors', async () => {
  mockApi(url => url === '/api/bookings' ? { status: 400, body: { detail: 'Invalid request input', errors: [{ field: 'startTime', message: 'must be a date in the present or in the future' }] } } : undefined);
  open('/rooms/8'); const user = await fillBooking(); await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
  expect(await screen.findByRole('alert')).toHaveTextContent('startTime: must be a date');
});
test('renders unknown route and navigates back to discovery', async () => {
  mockApi(); open('/unknown');
  expect(screen.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  await userEvent.click(screen.getAllByRole('link', { name: 'Explore spaces' })[1]);
  expect(await screen.findByRole('heading', { name: 'River Hub' })).toBeVisible();
});

test('books as the session identity without requesting a user directory or sending userId', async () => {
  const fetch = mockApi((url, options) => url === '/api/bookings' ? { status: 201, body: { ...JSON.parse(String(options?.body)), id: 92, roomName: room.name, userName: 'Alex', userId: 42, status: 'CONFIRMED' } } : undefined);
  open('/rooms/8'); const user = await fillBooking();
  await user.click(screen.getByRole('button', { name: 'Confirm booking' }));
  expect(await screen.findByText('Booking #92 saved')).toBeVisible();
  expect(fetch.mock.calls.some(([url]) => String(url).startsWith('/api/users'))).toBe(false);
  const post = fetch.mock.calls.find(([, options]) => options?.method === 'POST')!;
  expect(JSON.parse(String(post[1]?.body))).not.toHaveProperty('userId');
  expect(post[1]?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'test-csrf' });
});

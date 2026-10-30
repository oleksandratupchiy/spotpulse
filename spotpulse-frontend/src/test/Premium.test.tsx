import { render, screen, waitFor, within, fireEvent } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';

const flagKeys = ['PREMIUM_FEATURES_ENABLED', 'FEATURED_SPACES_ENABLED', 'ADVANCED_ANALYTICS_ENABLED', 'PREMIUM_BADGES_ENABLED'];
const page = (items: unknown[]) => ({ items, skip: 0, limit: 10, total: items.length, nextLink: null });
function api({ role = 'ADMIN', entitled = false, analytics = false, featured = false, error = 0, mutationError = 0 } = {}) {
  const flags = flagKeys.map(key => ({ key, description: `Control ${key}`, enabled: false }));
  const space = { id: 3, name: 'River Hub', city: 'Kyiv', address: 'River Street', rooms: [], premium: entitled, featured, premiumBadge: featured };
  const normal = { ...space, id: 4, name: 'Normal Hub', premium: false, featured: false, premiumBadge: false };
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    const url = String(input);
    const reply = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
    if (url === '/api/auth/me') return reply({ authenticated: true, user: { id: 1, name: 'Owner', role }, loginEnabled: true });
    if (url === '/api/auth/csrf') return reply({ token: 'b6-csrf', headerName: 'X-CSRF-TOKEN' });
    if (options?.method === 'PUT') {
      if (mutationError) return reply({ detail: mutationError === 401 ? 'Sign in to continue.' : 'Access denied' }, mutationError);
      const enabled = JSON.parse(String(options.body)).enabled;
      if (url.includes('/features/')) flags.find(f => url.endsWith(f.key))!.enabled = enabled;
      if (url.endsWith('/premium')) space.premium = enabled;
      return reply({ enabled });
    }
    if (url === '/api/admin/features') return reply(flags);
    if (url === '/api/management/premium-features') return reply({ premium: analytics, analytics, featured, badges: featured });
    if (url.startsWith('/api/management/spaces?')) return reply(page([space]));
    if (url.startsWith('/api/management/bookings?') || url.startsWith('/api/users?')) return reply(page([]));
    if (url.startsWith('/api/analytics/spaces/')) {
      if (error) return reply({ detail: error === 401 ? 'Sign in to continue.' : error === 403 ? 'Access denied' : 'Premium analytics is currently disabled by the administrator' }, error);
      return reply({ spaceId: 3, from: '2026-10-01', to: '2026-10-03', grouping: 'BOOKING_START_DATE', rooms: [
        { roomId: 7, roomName: 'Oak room', confirmed: 2, cancelled: 1, cancellationRate: 100 / 3 },
        { roomId: 8, roomName: 'Unused room', confirmed: 0, cancelled: 0, cancellationRate: 0 }
      ] });
    }
    if (url.startsWith('/api/spaces?')) return reply(page(featured ? [space, normal] : [normal, { ...space, featured: false, premiumBadge: false }]));
    return reply({});
  });
}
function open(path: string) { return render(<MemoryRouter initialEntries={[path]}><App/></MemoryRouter>); }

test('ADMIN sees persistent flag state and confirmed toggle uses CSRF then reloads state', async () => {
  const fetch = api(); const confirm = vi.spyOn(window, 'confirm').mockReturnValue(true);
  open('/admin');
  const enable = await screen.findByRole('button', { name: 'Enable PREMIUM_FEATURES_ENABLED' });
  expect(enable).toHaveAttribute('aria-pressed', 'false');
  await userEvent.click(enable);
  expect(confirm).toHaveBeenCalledWith('Enable PREMIUM_FEATURES_ENABLED?');
  const disable = await screen.findByRole('button', { name: 'Disable PREMIUM_FEATURES_ENABLED' });
  expect(disable).toHaveAttribute('aria-pressed', 'true');
  expect(fetch).toHaveBeenCalledWith('/api/admin/features/PREMIUM_FEATURES_ENABLED', expect.objectContaining({ method: 'PUT', body: '{"enabled":true}', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'b6-csrf' }) }));
  await userEvent.click(disable);
  expect(await screen.findByRole('button', { name: 'Enable PREMIUM_FEATURES_ENABLED' })).toBeVisible();
});

test('ADMIN can grant and revoke a specific space with visible entitlement feedback', async () => {
  api(); vi.spyOn(window, 'confirm').mockReturnValue(true); open('/admin');
  await userEvent.click(await screen.findByRole('button', { name: 'Grant premium for River Hub' }));
  expect(await screen.findByText('Active')).toBeVisible();
  await userEvent.click(await screen.findByRole('button', { name: 'Revoke premium for River Hub' }));
  expect(await screen.findByText('None')).toBeVisible();
  expect(screen.getByText(/No purchases or payments/)).toBeVisible();
});

test('cancelling confirmation performs no mutation', async () => {
  const fetch = api(); vi.spyOn(window, 'confirm').mockReturnValue(false); open('/admin');
  await userEvent.click(await screen.findByRole('button', { name: 'Enable ADVANCED_ANALYTICS_ENABLED' }));
  expect(fetch.mock.calls.some(([, options]) => options?.method === 'PUT')).toBe(false);
});

test.each([401, 403])('ADMIN mutation %s displays failure and retains prior state', async status => {
  api({ mutationError: status }); vi.spyOn(window, 'confirm').mockReturnValue(true); open('/admin');
  await userEvent.click(await screen.findByRole('button', { name: 'Enable PREMIUM_BADGES_ENABLED' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(status === 401 ? 'Sign in to continue.' : 'Access denied');
  expect(screen.getByRole('button', { name: 'Enable PREMIUM_BADGES_ENABLED' })).toBeEnabled();
});

test('discovery renders Featured badge and preserves featured order supplied by paginated server', async () => {
  api({ featured: true }); open('/spaces');
  expect(await screen.findByText('Featured')).toBeVisible();
  const cards = screen.getAllByRole('article');
  expect(within(cards[0]).getByRole('heading')).toHaveTextContent('River Hub');
  expect(within(cards[1]).getByRole('heading')).toHaveTextContent('Normal Hub');
  expect(screen.getByRole('button', { name: 'Show map' })).toBeVisible();
});

test('disabled discovery flags remove badge and preserve ordinary server order', async () => {
  api({ entitled: true }); open('/spaces');
  await screen.findByRole('heading', { name: 'River Hub' });
  expect(screen.queryByText('Featured')).not.toBeInTheDocument();
  expect(within(screen.getAllByRole('article')[0]).getByRole('heading')).toHaveTextContent('Normal Hub');
});

test('entitled OWNER sees additional room comparison with accessible dates and real zero state', async () => {
  const fetch = api({ role: 'OWNER', entitled: true, analytics: true }); open('/owner');
  expect(await screen.findByRole('heading', { name: 'Advanced room performance' })).toBeVisible();
  expect(await screen.findByRole('table')).toBeVisible();
  expect(screen.getByText('33.3%')).toBeVisible();
  expect(screen.getByText('No bookings')).toBeVisible();
  expect(screen.queryByRole('button', { name: /Grant premium/ })).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('From'), { target: { value: '2026-10-01' } });
  fireEvent.change(screen.getByLabelText('To'), { target: { value: '2026-10-03' } });
  await userEvent.click(screen.getByRole('button', { name: 'Apply' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/analytics/spaces/3/room-performance?from=2026-10-01&to=2026-10-03', expect.anything()));
});

test('unentitled OWNER has an explanation and cannot self grant or request premium data', async () => {
  const fetch = api({ role: 'OWNER' }); open('/owner');
  expect(await screen.findByText(/Ask an administrator/)).toBeVisible();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
  expect(fetch.mock.calls.some(([url]) => String(url).includes('/api/analytics/spaces/'))).toBe(false);
});

test('disabled premium analytics shows explanation and never fetches aggregates', async () => {
  const fetch = api({ role: 'OWNER', entitled: true }); open('/owner');
  expect(await screen.findByText(/Advanced analytics is currently disabled/)).toBeVisible();
  expect(screen.queryByRole('heading', { name: 'Advanced room performance' })).not.toBeInTheDocument();
  expect(fetch.mock.calls.some(([url]) => String(url).includes('/api/analytics/spaces/'))).toBe(false);
});

test.each([401, 403, 409])('premium analytics handles %s without rendering data', async status => {
  api({ role: 'OWNER', entitled: true, analytics: true, error: status }); open('/owner');
  const alert = await screen.findByRole('alert');
  expect(alert).toHaveTextContent(status === 401 ? 'Sign in to continue.' : status === 403 ? 'Access denied' : 'currently disabled');
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});

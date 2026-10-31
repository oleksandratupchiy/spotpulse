import { test, expect, login, mutate, watch } from './fixtures';

test('public catalog, search, city filter, pagination and refreshed details', async ({ page, fixture }) => {
  await page.goto('/');
  await expect(page).toHaveURL(/\/app\/spaces$/);
  await expect(page.getByRole('heading', { name: 'Find your space to focus.' })).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(6);
  await page.getByRole('button', { name: 'Next', exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(2);
  await expect(page.getByRole('button', { name: 'Next', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: 'Previous', exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(6);
  await page.getByLabel('Search spaces').fill('QA2 Hub 08');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(1);
  await expect(page.getByRole('heading', { name: 'QA2 Hub 08' })).toBeVisible();
  await page.getByLabel('Search by').selectOption('city');
  await page.getByLabel('Search spaces').fill('Kyiv');
  await page.getByRole('button', { name: 'Search', exact: true }).click();
  await expect(page.getByRole('article')).toHaveCount(4);
  await page.goto(`/app/spaces/${fixture.spaces[0]}`);
  await expect(page.getByRole('heading', { name: 'QA2 Hub 01' })).toBeVisible();
  await expect(page.getByText('QA2 Room 1')).toBeVisible();
  await page.reload();
  await expect(page.getByText('QA2 Room 1')).toBeVisible();
});

test('anonymous boundaries, missing resource and client fallback', async ({ page, fixture }) => {
  await page.goto('/app/bookings');
  await expect(page.getByRole('heading', { name: 'Sign in to continue' })).toBeVisible();
  expect((await page.request.get('/api/bookings')).status()).toBe(401);
  await page.goto(`/app/rooms/${fixture.rooms[0]}`);
  await expect(page.getByText('Sign in to book this room.')).toBeVisible();
  expect((await mutate(page.request, '/api/bookings', { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to })).status()).toBe(401);
  await page.goto('/app/spaces/999999');
  await expect(page.getByRole('alert')).toContainText(/not.*found/i);
  await page.goto('/app/spaces');
  // Client-side navigation tests React's wildcard without altering Spring's explicit route policy.
  await page.evaluate(() => { history.pushState({}, '', '/app/unknown'); dispatchEvent(new PopStateEvent('popstate')); });
  await expect(page.getByRole('heading', { name: 'Page not found' })).toBeVisible();
  expect((await page.request.get('/app/unknown')).status()).toBe(404);
});

test('Google redirect initiation uses the real OAuth filter but no Google login', async ({ page }) => {
  await page.goto('/app/login');
  await expect(page.getByRole('link', { name: 'Continue with Google' })).toHaveAttribute('href', '/oauth2/authorization/google');
  const response = await page.request.get('/oauth2/authorization/google', { maxRedirects: 0 });
  expect(response.status()).toBe(302);
  const location = new URL(response.headers().location);
  expect(location.origin).toBe('https://accounts.google.com');
  expect(location.searchParams.get('client_id')).toBe('qa2-synthetic');
  expect(location.searchParams.get('redirect_uri')).toContain('/login/oauth2/code/google');
  expect(location.searchParams.get('state')).toBeTruthy();
});

test('USER session, role denial, real CSRF rejection and logout', async ({ page, fixture }) => {
  await login(page, 'user');
  await page.goto('/app/account');
  await expect(page.getByText('Role: USER')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Manage spaces', exact: true })).toHaveCount(0);
  await page.goto('/app/owner');
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  await page.goto('/app/admin');
  await expect(page.getByRole('heading', { name: 'Access denied' })).toBeVisible();
  expect((await page.request.get('/api/management/spaces')).status()).toBe(403);
  expect((await mutate(page.request, `/api/admin/spaces/${fixture.spaces[0]}/premium`, { enabled: false }, 'PUT')).status()).toBe(403);
  expect((await mutate(page.request, '/api/admin/features/PREMIUM_FEATURES_ENABLED', { enabled: false }, 'PUT')).status()).toBe(403);
  expect((await page.request.post('/api/bookings', { data: { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to } })).status()).toBe(403);
  expect((await page.request.post('/__e2e/login/admin')).status()).toBe(403);
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('link', { name: 'Sign in with Google', exact: true }).first()).toBeVisible();
  expect((await page.request.get('/api/bookings')).status()).toBe(401);
  expect((await page.request.get('/api/auth/me')).ok()).toBeTruthy();
  expect((await (await page.request.get('/api/auth/me')).json()).authenticated).toBe(false);
});

test('OWNER creates a space, validates inputs, adds and edits a room', async ({ page }) => {
  await login(page, 'owner');
  await page.goto('/app/owner');
  await expect(page.getByRole('heading', { name: 'QA2 Hub 02', exact: true })).toHaveCount(0);
  await page.getByRole('link', { name: 'Add space', exact: true }).click();
  await page.getByRole('button', { name: 'Save space', exact: true }).click();
  expect(await page.getByLabel('Space name').evaluate((element: HTMLInputElement) => element.validity.valueMissing)).toBe(true);
  await page.getByLabel('Space name').fill('QA2 Created Hub');
  await page.getByLabel('City', { exact: true }).fill('Odesa');
  await page.getByLabel('Address', { exact: true }).fill('Isolated street');
  await page.getByRole('button', { name: 'Save space', exact: true }).click();
  await expect(page.getByRole('heading', { name: 'Edit space', exact: true })).toBeVisible();
  await page.getByLabel('Room name').fill('QA2 Created Room');
  await page.getByLabel('Capacity', { exact: true }).fill('0');
  await page.getByRole('button', { name: 'Save room', exact: true }).click();
  expect(await page.getByLabel('Capacity', { exact: true }).evaluate((element: HTMLInputElement) => element.validity.rangeUnderflow)).toBe(true);
  await page.getByLabel('Capacity', { exact: true }).fill('8');
  await page.getByLabel('Hourly rate').fill('35');
  await page.getByRole('button', { name: 'Save room', exact: true }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'QA2 Created Room' })).toBeVisible();
  await page.getByRole('button', { name: 'Edit room', exact: true }).click();
  await page.getByLabel('Room name').fill('QA2 Edited Room');
  await page.getByRole('button', { name: 'Save room', exact: true }).click();
  await expect(page.getByRole('listitem').filter({ hasText: 'QA2 Edited Room' })).toBeVisible();
  await page.getByLabel('Space name').fill('QA2 Edited Hub');
  await Promise.all([
    page.waitForResponse(response => /\/api\/spaces\/\d+$/.test(response.url()) && response.request().method() === 'PUT'),
    page.getByRole('button', { name: 'Save space', exact: true }).click(),
  ]);
  await page.reload();
  await expect(page.getByLabel('Space name')).toHaveValue('QA2 Edited Hub');
});

test('OWNER cannot edit another owner space or room; ADMIN can manage it', async ({ page, fixture }) => {
  await login(page, 'owner');
  await page.goto(`/app/owner/spaces/${fixture.spaces[1]}`);
  await expect(page.getByRole('alert')).toBeVisible();
  expect((await mutate(page.request, `/api/spaces/${fixture.spaces[1]}`, { name: 'Forbidden', city: 'Kyiv', address: 'Street' }, 'PUT')).status()).toBe(403);
  expect((await mutate(page.request, `/api/rooms/${fixture.rooms[1]}`, { name: 'Forbidden', spaceId: fixture.spaces[1], capacity: 4, hourlyRate: 10 }, 'PUT')).status()).toBe(403);
  await login(page, 'admin');
  await page.goto('/app/admin');
  await expect(page.getByRole('heading', { name: 'Platform administration' })).toBeVisible();
  await page.goto(`/app/owner/spaces/${fixture.spaces[1]}`);
  await expect(page.getByLabel('Space name')).toHaveValue('QA2 Hub 02');
  await page.getByLabel('Space name').fill('QA2 Admin Edit');
  await Promise.all([
    page.waitForResponse(response => /\/api\/spaces\/\d+$/.test(response.url()) && response.request().method() === 'PUT'),
    page.getByRole('button', { name: 'Save space', exact: true }).click(),
  ]);
  await page.reload();
  await expect(page.getByLabel('Space name')).toHaveValue('QA2 Admin Edit');
});

test('book, persist across navigation/reload, reject overlap and cancel own booking', async ({ page, fixture }) => {
  await login(page, 'user');
  await page.goto(`/app/rooms/${fixture.rooms[0]}`);
  await page.getByLabel('Start time', { exact: true }).fill(fixture.from);
  await page.getByLabel('End time', { exact: true }).fill(fixture.to);
  await page.getByRole('button', { name: 'Confirm booking', exact: true }).click();
  await expect(page.getByRole('heading', { name: /Booking #\d+ saved/ })).toBeVisible();
  await page.getByRole('link', { name: 'My bookings', exact: true }).first().click();
  await page.reload();
  const booking = page.getByRole('article').filter({ hasText: 'QA2 Room 1' });
  await expect(booking).toContainText('Confirmed');
  await page.goto(`/app/rooms/${fixture.rooms[0]}`);
  await page.getByLabel('Start time', { exact: true }).fill(fixture.from);
  await page.getByLabel('End time', { exact: true }).fill(fixture.to);
  await page.getByRole('button', { name: 'Confirm booking', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText(/overlap|already|conflict|available/i);
  await page.goto('/app/bookings');
  await booking.getByRole('button', { name: 'Cancel booking', exact: true }).click();
  await expect(booking).toContainText('Cancelled');
  await page.reload();
  await expect(booking).toContainText('Cancelled');
});

test('committed booking and cancellation refresh availability without technical controls', async ({ page, fixture, request }) => {
  await watch(page, fixture.rooms[0], fixture);
  // A separate session writes the booking while the browser watches for updates.
  expect((await mutate(request, '/__e2e/login/user')).ok()).toBeTruthy();
  const created = await mutate(request, '/api/bookings', { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to });
  expect(created.ok()).toBeTruthy();
  const booking = await created.json();
  await expect(page.getByRole('heading', { name: 'Booked times' })).toBeVisible();
  expect((await mutate(request, `/api/bookings/${booking.id}/cancel`)).ok()).toBeTruthy();
  await expect(page.getByRole('status').filter({ hasText: 'Available for your selected time' })).toBeVisible();
  await page.getByRole('button', { name: 'Stop updates' }).click();
  await expect(page.getByRole('button', { name: 'Check availability' })).toBeVisible();
});

test('premium entitlement and master flag change visible behavior', async ({ page }) => {
  await page.goto('/app/spaces');
  await expect(page.getByText('Featured', { exact: true })).toHaveCount(1);
  await login(page, 'admin');
  await page.goto('/app/admin');
  page.on('dialog', dialog => dialog.accept());
  await page.getByRole('button', { name: 'Disable PREMIUM_FEATURES_ENABLED', exact: true }).click();
  await expect(page.getByRole('button', { name: 'Enable PREMIUM_FEATURES_ENABLED', exact: true })).toBeVisible();
  await page.goto('/app/spaces');
  await expect(page.getByRole('article')).toHaveCount(6);
  await expect(page.getByText('Featured', { exact: true })).toHaveCount(0);
});

test('known occupied fixture and invalid booking interval show useful feedback', async ({ page, fixture }) => {
  await login(page, 'user');
  await page.goto(`/app/rooms/${fixture.rooms[1]}`);
  await page.getByLabel('Availability from').fill(fixture.from);
  await page.getByLabel('Availability to').fill(fixture.to);
  await page.getByRole('button', { name: 'Check availability' }).click();
  await expect(page.getByRole('heading', { name: 'Booked times' })).toBeVisible();
  await page.getByLabel('Start time', { exact: true }).fill(fixture.to);
  await page.getByLabel('End time', { exact: true }).fill(fixture.from);
  await page.getByRole('button', { name: 'Confirm booking', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('End time must be after start time.');
});

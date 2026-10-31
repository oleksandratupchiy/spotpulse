import { test, expect, login, mutate } from './fixtures';

test('persistent watch survives refresh, a new browser session and logout; cancellation schedules delivery without an open room page', async ({ page, request, browser, baseURL, fixture }) => {
  expect((await mutate(request, '/__e2e/login/other')).ok()).toBeTruthy();
  const created = await mutate(request, '/api/bookings', { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to });
  expect(created.ok()).toBeTruthy(); const booking = await created.json();
  await login(page, 'user'); await page.goto(`/app/rooms/${fixture.rooms[0]}`);
  await page.getByLabel('Availability from').fill(fixture.from); await page.getByLabel('Availability to').fill(fixture.to);
  await page.getByRole('button', { name: 'Check availability' }).click();
  await page.getByRole('button', { name: 'Notify me when available' }).click();
  await expect(page.getByText(/You're watching this time slot/)).toBeVisible();
  await expect(page.getByText(/Telegram is not linked/)).toBeVisible();
  await page.reload();
  await expect(page.getByRole('button', { name: 'Cancel alert' })).toBeVisible();
  await expect(page.getByLabel('Availability from')).toHaveValue(fixture.from.slice(0, 16));
  await page.getByRole('button', { name: 'Sign out' }).click();
  await expect(page.getByRole('link', { name: 'Sign in with Google', exact: true }).first()).toBeVisible();
  expect((await page.request.get('/api/availability-alerts?skip=0&limit=10')).status()).toBe(401);
  await login(page, 'user'); await page.goto('/app/account');
  const alerts = page.getByRole('region', { name: 'My availability alerts' });
  await expect(alerts.getByRole('button', { name: 'Cancel alert' })).toBeVisible();
  // Close the actual browser context. The watcher has no open page when cancellation commits.
  await page.context().close();
  expect((await mutate(request, `/api/bookings/${booking.id}/cancel`)).ok()).toBeTruthy();
  expect((await mutate(request, '/__e2e/availability-alerts/process')).ok()).toBeTruthy();
  const fresh = await browser.newContext({ baseURL });
  await fresh.route('**/*', route => route.request().url().startsWith(baseURL + '/') ? route.continue() : route.abort());
  const returned = await fresh.newPage();
  try {
    await login(returned, 'user'); await returned.goto('/app/account');
    const section = returned.getByRole('region', { name: 'My availability alerts' });
    await expect(section.getByText('Available · Telegram delivery pending', { exact: true })).toBeVisible();
    const response = await returned.request.get('/api/availability-alerts?skip=0&limit=10');
    const result = await response.json();
    expect(result.items).toHaveLength(1); expect(result.items[0].status).toBe('READY'); expect(result.items[0].notifiedAt).toBeNull();
    await section.getByRole('button', { name: 'Cancel alert' }).click();
    await expect(section.getByText('Cancelled', { exact: true })).toBeVisible();
    expect((await (await returned.request.get('/api/availability-alerts?skip=0&limit=10&activeOnly=true')).json()).items).toHaveLength(0);
  } finally { await fresh.close(); }
});

test('anonymous visitors can check occupied intervals but must sign in to save an alert', async ({ page, request, fixture }) => {
  expect((await mutate(request, '/__e2e/login/other')).ok()).toBeTruthy();
  expect((await mutate(request, '/api/bookings', { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to })).ok()).toBeTruthy();
  await page.goto(`/app/rooms/${fixture.rooms[0]}`);
  await page.getByLabel('Availability from').fill(fixture.from); await page.getByLabel('Availability to').fill(fixture.to);
  await page.getByRole('button', { name: 'Check availability' }).click();
  await expect(page.getByRole('link', { name: 'Sign in to create an availability alert' })).toBeVisible();
  await expect(page.getByRole('button', { name: 'Notify me when available' })).toHaveCount(0);
});

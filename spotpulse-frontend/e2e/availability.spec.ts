import { test, expect, login, mutate } from './fixtures';

for (const actor of ['owner', 'admin'] as const) {
  test(`${actor} can compare WebSocket and polling availability (C10)`, async ({ page, fixture, request }) => {
    await login(page, actor);
    await page.goto(`/app/rooms/${fixture.rooms[0]}`);
    await page.getByText('Connection comparison (C10)', { exact: true }).click();
    await page.getByLabel('Update mode').selectOption('websocket');
    await page.getByLabel('Availability from').fill(fixture.from);
    await page.getByLabel('Availability to').fill(fixture.to);
    await page.getByRole('button', { name: 'Notify me when available' }).click();
    await expect(page.getByText('WebSocket connected', { exact: true })).toBeVisible();
    await expect(page.getByRole('status').filter({ hasText: 'Available for your selected time' })).toBeVisible();
    expect((await mutate(request, '/__e2e/login/user')).ok()).toBeTruthy();
    const created = await mutate(request, '/api/bookings', { roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to });
    expect(created.ok()).toBeTruthy();
    await expect(page.getByRole('heading', { name: 'Booked times' })).toBeVisible();
    const booking = await created.json();
    await page.getByLabel('Update mode').selectOption('polling');
    await page.getByLabel('Polling interval').selectOption('3000');
    expect((await mutate(request, `/api/bookings/${booking.id}/cancel`)).ok()).toBeTruthy();
    await expect(page.getByRole('status').filter({ hasText: 'Available for your selected time' })).toBeVisible();
    await expect(page.getByText('HTTP polling', { exact: true })).toBeVisible();
  });
}

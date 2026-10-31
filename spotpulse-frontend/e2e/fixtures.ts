import { test as base, expect, type APIRequestContext, type Page } from '@playwright/test';

export type Fixture = { spaces: number[]; rooms: number[]; from: string; to: string };
export async function mutate(request: APIRequestContext, path: string, data?: unknown, method = 'POST') {
  const csrf = await request.get('/api/auth/csrf');
  expect(csrf.ok()).toBeTruthy();
  const token = await csrf.json();
  return request.fetch(path, { method, headers: { [token.headerName]: token.token }, data });
}
export async function login(page: Page, actor: 'user' | 'owner' | 'other' | 'admin') {
  expect((await mutate(page.request, `/__e2e/login/${actor}`)).ok()).toBeTruthy();
}
export const test = base.extend<{ fixture: Fixture }>({
  fixture: [async ({ request, context, baseURL }, use) => {
    if (!baseURL || !/^http:\/\/localhost:\d+$/.test(baseURL)) throw new Error('Only the owned local harness is allowed');
    // Browsers cannot reach external APIs/images/maps/OAuth services during these tests.
    await context.route('**/*', route => {
      const url = route.request().url();
      return url.startsWith(baseURL + '/') || url.startsWith('blob:') || url.startsWith('data:')
        ? route.continue() : route.abort('blockedbyclient');
    });
    const response = await mutate(request, '/__e2e/reset');
    expect(response.ok()).toBeTruthy();
    await use(await response.json());
  }, { auto: true }],
});
export { expect };

export async function watch(page: Page, room: number, data: Fixture) {
  await page.goto(`/app/rooms/${room}`);
  await page.getByLabel('Availability from').fill(data.from);
  await page.getByLabel('Availability to').fill(data.to);
  await page.getByRole('button', { name: 'Check availability' }).click();
  await expect(page.getByRole('status').filter({ hasText: 'Available for your selected time' })).toBeVisible();
  await expect(page.getByLabel('Update mode')).toHaveCount(0);
}

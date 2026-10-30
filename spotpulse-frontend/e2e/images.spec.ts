import { test, expect, login, mutate } from './fixtures';

test('local image preview, upload failure retry, persistence and public delivery', async ({ page, fixture }) => {
  await login(page, 'owner');
  await page.goto(`/app/owner/spaces/${fixture.spaces[0]}`);
  await expect(page.getByLabel('Space name')).toBeVisible();
  const bytes = await page.evaluate(() => {
    const canvas = document.createElement('canvas'); canvas.width = 4; canvas.height = 3;
    const context = canvas.getContext('2d')!; context.fillStyle = '#1255aa'; context.fillRect(0, 0, 4, 3);
    return canvas.toDataURL('image/png').split(',')[1];
  });
  const file = { name: 'qa2-photo.png', mimeType: 'image/png', buffer: Buffer.from(bytes, 'base64') };
  const picker = page.getByLabel('Select coworking image');
  await picker.setInputFiles(file);
  await expect(page.getByRole('img', { name: 'Selected coworking image preview' })).toBeVisible();
  await expect(page.getByRole('status').filter({ hasText: 'qa2-photo.png' })).toContainText('image/png');
  await expect(page.getByRole('status').filter({ hasText: 'qa2-photo.png' })).toContainText('4 × 3 pixels');
  await page.getByRole('button', { name: 'Remove selection' }).click();
  await expect(page.getByRole('img', { name: 'Selected coworking image preview' })).toHaveCount(0);
  await picker.setInputFiles({ ...file, name: 'replacement.png' });
  await expect(page.getByRole('status').filter({ hasText: 'replacement.png' })).toBeVisible();
  await page.route(`**/api/spaces/${fixture.spaces[0]}/image`, route => route.fulfill({ status: 503,
    contentType: 'application/problem+json', body: JSON.stringify({ detail: 'Isolated simulated upload failure' }) }), { times: 1 });
  await page.getByRole('button', { name: 'Save space', exact: true }).click();
  await expect(page.getByRole('alert')).toContainText('image was not attached');
  await expect(page.getByRole('img', { name: 'Selected coworking image preview' })).toBeVisible();
  const uploaded = page.waitForResponse(response => response.url().endsWith(`/api/spaces/${fixture.spaces[0]}/image`) && response.request().method() === 'POST');
  await page.getByRole('button', { name: 'Save space', exact: true }).click();
  expect((await uploaded).ok()).toBeTruthy();
  await expect(page.getByRole('img', { name: 'QA2 Hub 01', exact: true })).toBeVisible();
  const saved = await (await page.request.get(`/api/spaces/${fixture.spaces[0]}`)).json();
  expect(saved.imageUrl).toMatch(/^\/api\/spaces\/images\/[a-f0-9-]+\.png$/);
  expect((await page.request.get(saved.imageUrl)).headers()['content-type']).toContain('image/png');
  const csrf = await (await page.request.get('/api/auth/csrf')).json();
  expect((await page.request.post(`/api/spaces/${fixture.spaces[1]}/image`, { headers: { [csrf.headerName]: csrf.token },
    multipart: { file: { name: file.name, mimeType: file.mimeType, buffer: file.buffer } } })).status()).toBe(403);
  await page.goto(`/app/spaces/${fixture.spaces[0]}`);
  await expect(page.getByRole('img', { name: 'QA2 Hub 01', exact: true })).toBeVisible();
  await page.reload();
  await expect(page.getByRole('img', { name: 'QA2 Hub 01', exact: true })).toHaveAttribute('src', saved.imageUrl);
  await page.goto('/app/spaces');
  await expect(page.getByRole('img', { name: 'QA2 Hub 01', exact: true })).toBeVisible();
});

test('invalid, empty and oversized images are rejected before upload', async ({ page, fixture }) => {
  await login(page, 'owner');
  await page.goto(`/app/owner/spaces/${fixture.spaces[0]}`);
  const picker = page.getByLabel('Select coworking image');
  await picker.setInputFiles({ name: 'unsafe.svg', mimeType: 'image/svg+xml', buffer: Buffer.from('<svg/>') });
  await expect(page.getByRole('alert')).toBeVisible();
  await picker.setInputFiles({ name: 'empty.png', mimeType: 'image/png', buffer: Buffer.alloc(0) });
  await expect(page.getByRole('alert')).toContainText('non-empty');
  await picker.setInputFiles({ name: 'large.png', mimeType: 'image/png', buffer: Buffer.alloc(8 * 1024 * 1024 + 1) });
  await expect(page.getByRole('alert')).toContainText('at most 8 MiB');
  await expect(page.getByRole('img', { name: 'Selected coworking image preview' })).toHaveCount(0);
  await page.getByRole('button', { name: 'Remove selection' }).click();
  await expect(page.getByRole('alert')).toHaveCount(0);
});

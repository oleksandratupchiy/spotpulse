import { mkdir } from 'node:fs/promises';
import { resolve } from 'node:path';
import { test, expect, login, mutate } from './fixtures';

// Real local application routes and synthetic accounts; no external services.
for (const width of [390, 768, 1440]) {
  test(`design review at ${width}px`, async ({ page, fixture }) => {
    test.setTimeout(180_000);
    await page.setViewportSize({ width, height: 1000 });
    const output = resolve('../spotpulse-backend/target/task22-visual/after');
    await mkdir(output, { recursive: true });
    const errors: string[] = [];
    page.on('pageerror', error => errors.push(error.message));
    async function capture(name: string, route: string, heading: string) {
      await page.goto(route);
      await expect(page.getByRole('heading', { name: heading, exact: true }).first()).toBeVisible();
      if (name === 'analytics') {
        await page.getByLabel('Date range').selectOption('next30');
        await expect(page.getByRole('img', { name: 'Bookings over time by scheduled start date' })).toBeVisible();
      }
      if (name === 'connection-comparison') {
        await page.getByText('Connection comparison (C10)', { exact: true }).click();
      }
      await page.evaluate(() => document.fonts.ready);
      await expect(page.getByRole('status').filter({ hasText: /^Loading/ })).toHaveCount(0);
      if (name === 'server-detail') {
        const clipped = await page.locator('.hero-media').evaluate(frame => {
          const placeholder = frame.querySelector<HTMLElement>('.image-placeholder')!;
          const caption = placeholder.querySelector<HTMLElement>('span')!;
          return placeholder.getBoundingClientRect().width > frame.clientWidth + 1 || caption.scrollWidth > caption.clientWidth + 1;
        });
        expect(clipped, 'server image fallback must wrap inside its frame').toBe(false);
      }
      expect(await page.evaluate(() => document.documentElement.scrollWidth), `${name}: page overflow`).toBeLessThanOrEqual(width);
      expect(await page.locator('.btn-primary, .btn-success, .btn-outline-primary, .btn-outline-success, .btn-outline-danger, .btn-light').count(), `${name}: legacy button classes`).toBe(0);
      const legacy = await page.locator('button, a.sp-button').evaluateAll(elements => elements.filter(element => {
        const color = getComputedStyle(element).backgroundColor;
        return ['rgb(35, 77, 67)', 'rgb(36, 104, 92)', 'rgb(13, 110, 253)', 'rgb(22, 62, 58)'].includes(color);
      }).map(element => element.textContent));
      expect(legacy, `${name}: legacy action colors`).toEqual([]);
      await page.screenshot({ path: resolve(output, `${name}-${width}.png`), fullPage: true, animations: 'disabled' });
      await page.screenshot({ path: resolve(output, `${name}-${width}-viewport.png`), animations: 'disabled' });
    }
    await login(page, 'user');
    await capture('catalog', '/app/spaces', 'Find your space to focus.');
    if (width < 992) {
      const menu = page.getByRole('button', { name: 'Menu', exact: true });
      await menu.click();
      await expect(menu).toHaveAttribute('aria-expanded', 'true');
      await page.keyboard.press('Tab');
      await expect(page.getByRole('link', { name: 'Explore spaces', exact: true })).toBeFocused();
      await page.screenshot({ path: resolve(output, `navigation-${width}.png`), animations: 'disabled' });
      await page.keyboard.press('Escape');
      await expect(menu).toHaveAttribute('aria-expanded', 'false');
    }
    await capture('space', `/app/spaces/${fixture.spaces[0]}`, 'QA2 Hub 01');
    await capture('room-booking', `/app/rooms/${fixture.rooms[0]}`, 'QA2 Room 1');
    await expect(page.getByLabel('Update mode')).toHaveCount(0);
    // Populate the user's list and the owner's future analytics using the real API.
    expect((await mutate(page.request, '/api/bookings', {
      roomId: fixture.rooms[0], startTime: fixture.from, endTime: fixture.to,
    })).ok()).toBeTruthy();
    await capture('bookings', '/app/bookings', 'My bookings');
    await capture('account', '/app/account', 'Your profile');
    await login(page, 'owner');
    await capture('owner', '/app/owner', 'Your spaces');
    await capture('space-editor', `/app/owner/spaces/${fixture.spaces[0]}`, 'Edit space');
    await capture('analytics', '/app/analytics', 'Booking analytics');
    await capture('connection-comparison', `/app/rooms/${fixture.rooms[0]}`, 'QA2 Room 1');
    await capture('server-catalog', '/spaces', 'Коворкінг-простори');
    const autocomplete = page.getByRole('combobox', { name: 'Пошук коворкінгу' });
    await autocomplete.fill('QA2');
    await expect(page.getByRole('option').first()).toBeVisible();
    await autocomplete.press('ArrowDown');
    await autocomplete.press('Enter');
    await expect(page).toHaveURL(/\/spaces\/\d+$/);
    await capture('server-detail', `/spaces/${fixture.spaces[0]}`, 'QA2 Hub 01');
    await capture('server-editor', `/spaces/${fixture.spaces[0]}/edit`, 'Редагувати простір');
    await login(page, 'admin');
    await capture('admin', '/app/admin', 'Platform administration');
    expect((await mutate(page.request, '/api/auth/logout')).ok()).toBeTruthy();
    await capture('login', '/app/login', 'Sign in to SpotPulse');
    expect(errors).toEqual([]);
  });
}


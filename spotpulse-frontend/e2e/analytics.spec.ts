import { test, expect, login } from './fixtures';
import type { Page } from '@playwright/test';
declare global { interface Window { dataLayer?: unknown[] } }

async function mockTag(page: Page) {
  let scripts=0;
  // This page route takes precedence over the fixture's external-request blocker. No Google connection occurs.
  await page.route('https://www.googletagmanager.com/gtag/js?*', route => {
    scripts++; return route.fulfill({contentType:'application/javascript',body:'/* offline GA4 transport mock */'});
  });
  return ()=>scripts;
}
async function events(page: Page, name: string) {
  return page.evaluate(eventName => (window.dataLayer || []).map(c=>Array.from(c as ArrayLike<unknown>)).filter(c=>c[0]==='event'&&c[1]===eventName), name);
}
test('analytics requires consent, tracks sanitized SPA actions once and can be withdrawn', async({page,fixture})=>{
  const scripts=await mockTag(page);
  await page.goto('/app/spaces?q=private%40example.test');
  await expect(page.getByRole('button',{name:'Accept analytics'})).toBeVisible();
  expect(scripts()).toBe(0); expect(await events(page,'page_view')).toHaveLength(0);
  await page.getByRole('button',{name:'Reject analytics'}).click(); expect(scripts()).toBe(0);
  await page.getByRole('button',{name:'Analytics preferences'}).click();
  await page.getByRole('button',{name:'Accept analytics'}).click();
  await expect.poll(()=>events(page,'page_view')).toHaveLength(1); expect(scripts()).toBe(1);
  await page.getByLabel('Search spaces').fill('QA2 Hub');
  expect(await events(page,'space_search')).toHaveLength(0);
  await page.getByRole('button',{name:'Search',exact:true}).click();
  await expect.poll(()=>events(page,'space_search')).toHaveLength(1);
  await page.getByRole('heading',{name:'QA2 Hub 01'}).getByRole('link').click();
  await expect.poll(()=>events(page,'space_view')).toHaveLength(1);
  await expect.poll(()=>events(page,'page_view')).toHaveLength(2); expect(scripts()).toBe(1);
  const commands=await page.evaluate(()=>JSON.stringify(window.dataLayer));
  expect(commands).not.toContain('private@example.test'); expect(commands).not.toContain('/spaces/'+fixture.spaces[0]);
  await page.getByRole('button',{name:'Analytics preferences'}).click();
  await page.getByRole('button',{name:'Reject analytics'}).click();
  await page.getByRole('link',{name:'View room & book'}).click();
  expect(await events(page,'page_view')).toHaveLength(2);
});
test('browser privacy preference blocks the mocked tracker even when consent was stored', async({page})=>{
  await page.addInitScript(()=>{
    localStorage.setItem('spotpulse.analytics-consent.v1','accepted');
    Object.defineProperty(navigator,'globalPrivacyControl',{value:true});
  });
  const scripts=await mockTag(page); await page.goto('/app/spaces');
  await page.getByRole('button',{name:'Analytics preferences'}).click();
  await expect(page.getByRole('button',{name:'Accept analytics'})).toBeDisabled();
  expect(scripts()).toBe(0); expect(await events(page,'page_view')).toHaveLength(0);
});
test('only confirmed booking and cancellation actions emit anonymous events',async({page,fixture})=>{
  await mockTag(page); await login(page,'user'); await page.goto('/app/rooms/'+fixture.rooms[0]);
  await page.getByRole('button',{name:'Accept analytics'}).click();
  await expect.poll(()=>events(page,'page_view')).toHaveLength(1);
  async function book() {
    await page.getByLabel('Start time',{exact:true}).fill(fixture.from);
    await page.getByLabel('End time',{exact:true}).fill(fixture.to);
    await page.getByRole('button',{name:'Confirm booking',exact:true}).click();
  }
  await book(); await expect(page.getByRole('heading',{name:/Booking #\d+ saved/})).toBeVisible();
  expect(await events(page,'booking_success')).toHaveLength(1);
  await page.getByRole('link',{name:'My bookings',exact:true}).first().click();
  const article=page.getByRole('article').filter({hasText:'QA2 Room 1'});
  await article.getByRole('link',{name:'QA2 Room 1'}).click(); await book();
  await expect(page.getByRole('alert')).toContainText(/already booked/i);
  expect(await events(page,'booking_success')).toHaveLength(1);
  await page.getByRole('link',{name:'My bookings',exact:true}).first().click();
  await article.getByRole('button',{name:'Cancel booking',exact:true}).click();
  await expect(article).toContainText('CANCELLED'); expect(await events(page,'booking_cancelled')).toHaveLength(1);
  const payload=JSON.stringify(await events(page,'booking_cancelled'));
  expect(payload).not.toContain(fixture.from); expect(payload).not.toMatch(/QA2|user@|booking_id|user_id|room_id/);
});

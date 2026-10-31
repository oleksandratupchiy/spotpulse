import { CONSENT_KEY, createUsageAnalytics, measurementId, routeLabel } from '../usageAnalytics';

const id = 'G-TEST000000';
function calls() { return (window.dataLayer || []).map(c => Array.from(c as ArrayLike<unknown>)); }
function events(name?: string) { return calls().filter(c => c[0] === 'event' && (!name || c[1] === name)); }
function loaded() { document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('load')); }
beforeEach(() => {
  delete (window as unknown as Record<string, unknown>)['ga-disable-'+id];
  localStorage.clear(); document.getElementById('spotpulse-ga4')?.remove();
  delete window.gtag; delete window.dataLayer;
  Object.defineProperty(navigator, 'doNotTrack', { configurable: true, value: undefined });
  Object.defineProperty(navigator, 'globalPrivacyControl', { configurable: true, value: false });
});
afterEach(() => { localStorage.clear(); document.getElementById('spotpulse-ga4')?.remove(); delete window.gtag; delete window.dataLayer; });

test.each([undefined, '', 'G-INVALID', 'UA-12345678', 'G-TEST000000<script>', 'g-test000000', ' G-TEST000000'])('missing/invalid ID %s stays completely inactive', value => {
  const analytics = createUsageAnalytics(value); analytics.page('/spaces'); analytics.choose('accepted'); analytics.bookingSuccess();
  expect(analytics.configured).toBe(false); expect(measurementId(value)).toBeUndefined();
  expect(window.dataLayer).toBeUndefined(); expect(document.getElementById('spotpulse-ga4')).toBeNull();
});
test('valid config loads exactly once after affirmative consent and never automatically sends page views', () => {
  const a=createUsageAnalytics(id); a.page('/spaces'); a.search('text', true);
  expect(window.dataLayer).toBeUndefined();
  a.choose('accepted'); a.page('/spaces'); a.choose('accepted'); loaded();
  expect(document.querySelectorAll('#spotpulse-ga4')).toHaveLength(1);
  expect((document.getElementById('spotpulse-ga4') as HTMLScriptElement).referrerPolicy).toBe('no-referrer');
  expect(calls().find(c=>c[0]==='config')?.[2]).toMatchObject({send_page_view:false,allow_google_signals:false,allow_ad_personalization_signals:false,page_referrer:''});
  expect(events('page_view')).toHaveLength(1); expect(events('space_search')).toHaveLength(0);
});
test('respects a pre-existing Google Analytics opt-out flag', () => {
  (window as unknown as Record<string, unknown>)['ga-disable-'+id]=true;
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces');
  expect(a.blocked()).toBe(true); expect(window.dataLayer).toBeUndefined();
});
test('does not inject a tag when the exact GA4 script already exists', () => {
  const script=document.createElement('script'); script.src='https://www.googletagmanager.com/gtag/js?id='+id; document.head.append(script);
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces');
  expect(document.querySelectorAll('script[src*="googletagmanager.com"]')).toHaveLength(1); script.remove();
});
test('rejection, withdrawal and reacceptance gate events without affecting necessary cookies', () => {
  const a=createUsageAnalytics(id); a.page('/spaces'); a.choose('rejected'); expect(window.dataLayer).toBeUndefined();
  a.choose('accepted'); loaded(); document.cookie='JSESSIONID=synthetic; path=/';
  a.choose('rejected'); const count=calls().length; a.bookingSuccess(); a.page('/rooms/123');
  expect(calls()).toHaveLength(count); expect((window as unknown as Record<string, unknown>)['ga-disable-'+id]).toBe(true);
  expect(document.cookie).toContain('JSESSIONID=synthetic');
  a.choose('accepted'); expect(events('page_view')).toHaveLength(2); expect(document.querySelectorAll('#spotpulse-ga4')).toHaveLength(1);
  expect(localStorage.getItem(CONSENT_KEY)).toBe('accepted');
});
test.each(['doNotTrack','globalPrivacyControl'] as const)('respects browser privacy signal %s', signal => {
  Object.defineProperty(navigator, signal, {configurable:true,value:signal==='doNotTrack'?'1':true});
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces'); a.bookingSuccess();
  expect(window.dataLayer).toBeUndefined();
});
test('route visits deduplicate rerenders but count different detail visits, omitting IDs/query/hash', () => {
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces'); loaded();
  a.page('/spaces'); a.page('/spaces/456'); a.spaceView(); a.spaceView(); a.page('/spaces/456');
  a.page('/spaces/789'); a.spaceView(); a.page('/rooms/123'); a.page('/login');
  expect(events('page_view')).toHaveLength(5); expect(events('space_view')).toHaveLength(2);
  expect(JSON.stringify(events())).not.toMatch(/456|789|123|email|token|user_id|booking_id/);
  expect(routeLabel('/spaces?email=private')).toBe('private');
});
test('private/unknown routes never produce page views or transmit raw page URLs', () => {
  const a=createUsageAnalytics(id); a.choose('accepted'); loaded();
  for (const route of ['/account','/owner/spaces/123','/admin','/analytics','/unknown/person@example.test']) a.page(route);
  expect(events()).toHaveLength(0);
  expect(JSON.stringify(calls())).not.toContain('person@example.test');
});
test('current detail can be measured once after consenting; earlier pages/actions are not replayed', () => {
  const a=createUsageAnalytics(id); a.page('/spaces'); a.page('/spaces/999'); a.spaceView(); a.bookingSuccess();
  a.choose('accepted'); loaded();
  expect(events('page_view')).toHaveLength(1); expect(events('space_view')).toHaveLength(1); expect(events('booking_success')).toHaveLength(0);
});
test('fixed event APIs contain only safe categories, never arbitrary customer data', () => {
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/rooms/987'); loaded();
  a.search('city',true); a.bookingSuccess(); a.bookingCancelled();
  expect(events('space_search')[0][2]).toMatchObject({search_mode:'city',query_present:true});
  for (const c of events()) expect(Object.keys(c[2] as object)).toEqual(expect.arrayContaining(['page_location','page_path','page_title','page_referrer']));
  expect(JSON.stringify(events())).not.toContain('987');
});
test('blocked or throwing GA never breaks actions; consent withdrawn before load discards queued data', () => {
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces');
  document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('error'));
  expect(()=>{for(let i=0;i<50;i++)a.bookingSuccess();}).not.toThrow();
  a.choose('rejected'); loaded(); expect(events()).toHaveLength(0);
  window.gtag=()=>{throw new Error('blocked');}; expect(()=>a.choose('accepted')).not.toThrow();
});
test('unavailable storage works in memory and cross-tab rejection stops tracking', () => {
  vi.spyOn(Storage.prototype,'setItem').mockImplementation(()=>{throw new Error('disabled');});
  const a=createUsageAnalytics(id); a.choose('accepted'); a.page('/spaces'); loaded();
  expect(events('page_view')).toHaveLength(1);
  a.sync(); a.bookingSuccess(); expect(events('booking_success')).toHaveLength(0);
});

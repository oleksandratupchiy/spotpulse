import { StrictMode } from 'react';
import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter } from 'react-router-dom';
import { AnalyticsConsent } from '../AnalyticsConsent';
import { CONSENT_KEY, createUsageAnalytics } from '../usageAnalytics';

const session = vi.hoisted(() => ({ user: { role: 'ADMIN' } as { role: string } | null }));
vi.mock('../Auth', () => ({ useAuth: () => session }));

beforeEach(()=>{session.user = { role: 'ADMIN' }; localStorage.clear(); delete (window as unknown as Record<string, unknown>)['ga-disable-G-TEST000000']; delete window.dataLayer; delete window.gtag; document.getElementById('spotpulse-ga4')?.remove();});
afterEach(()=>{localStorage.clear(); delete window.dataLayer; delete window.gtag; document.getElementById('spotpulse-ga4')?.remove();});
function open(id: string | undefined = 'G-TEST000000') {
  const analytics=createUsageAnalytics(id);
  render(<StrictMode><MemoryRouter initialEntries={['/spaces?email=private@example.test']}><AnalyticsConsent analytics={analytics}/><Link to="/rooms/987?q=secret#token">Next route</Link></MemoryRouter></StrictMode>);
  return analytics;
}
test('accessible affirmative/reject choices, change later and no StrictMode duplicates', async()=>{
  open(); expect(window.dataLayer).toBeUndefined();
  await userEvent.click(screen.getByRole('button',{name:'Reject analytics'}));
  expect(localStorage.getItem(CONSENT_KEY)).toBe('rejected'); expect(window.dataLayer).toBeUndefined();
  await userEvent.click(screen.getByRole('button',{name:'Analytics preferences'}));
  await userEvent.click(screen.getByRole('button',{name:'Accept analytics'}));
  document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('load'));
  const events=()=> (window.dataLayer || []).map(c=>Array.from(c as ArrayLike<unknown>)).filter(c=>c[0]==='event');
  expect(events()).toHaveLength(1);
  await userEvent.click(screen.getByRole('link',{name:'Next route'})); expect(events()).toHaveLength(2);
  expect(JSON.stringify(events())).not.toMatch(/987|secret|token|private@example/);
  await userEvent.click(screen.getByRole('button',{name:'Analytics preferences'}));
  await userEvent.click(screen.getByRole('button',{name:'Reject analytics'}));
  expect(document.querySelectorAll('#spotpulse-ga4')).toHaveLength(1);
});
test('missing ID is inactive and storage changes synchronize preferences',async()=>{
  const disabled=createUsageAnalytics(undefined);
  const view=render(<MemoryRouter><AnalyticsConsent analytics={disabled}/></MemoryRouter>);
  expect(screen.queryByRole('button',{name:'Analytics preferences'})).toBeNull(); view.unmount();
  open(); localStorage.setItem(CONSENT_KEY,'rejected');
  const {act}=await import('@testing-library/react'); act(()=>window.dispatchEvent(new StorageEvent('storage',{key:CONSENT_KEY,newValue:'rejected'})));
  expect(screen.queryByRole('button',{name:'Accept analytics'})).toBeNull(); expect(window.dataLayer).toBeUndefined();
});


test.each(['USER', 'OWNER', 'ADMIN', null])('visitor %s can accept, revoke and change consent without role restrictions', async role => {
  session.user = role ? { role } : null;
  const analytics = createUsageAnalytics('G-TEST000000', false);
  render(<MemoryRouter initialEntries={['/spaces']}><AnalyticsConsent analytics={analytics}/></MemoryRouter>);
  expect(screen.getByRole('region', { name: 'Analytics consent' })).toBeVisible();
  expect(window.dataLayer).toBeUndefined();
  await userEvent.click(screen.getByRole('button', { name: 'Accept analytics' }));
  expect(localStorage.getItem(CONSENT_KEY)).toBe('accepted');
  act(() => document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('load')));
  expect(screen.queryByRole('region', { name: 'Analytics consent' })).not.toBeInTheDocument();
  expect(window.dataLayer).toBeDefined();
  await userEvent.click(screen.getByRole('button', { name: 'Analytics preferences' }));
  await userEvent.click(screen.getByRole('button', { name: 'Reject analytics' }));
  const count = window.dataLayer!.length;
  analytics.page('/rooms/123'); analytics.search('text', true); analytics.bookingSuccess(); analytics.resume();
  expect(window.dataLayer).toHaveLength(count);
  expect((window as unknown as Record<string, unknown>)['ga-disable-G-TEST000000']).toBe(true);
  expect(localStorage.getItem(CONSENT_KEY)).toBe('rejected');
});

test.each(['accepted', 'rejected'])('stored %s choice hides initial prompt and remains editable', async choice => {
  localStorage.setItem(CONSENT_KEY, choice);
  const analytics = createUsageAnalytics('G-TEST000000', false);
  render(<MemoryRouter initialEntries={['/spaces']}><AnalyticsConsent analytics={analytics}/></MemoryRouter>);
  expect(screen.queryByRole('region', { name: 'Analytics consent' })).not.toBeInTheDocument();
  expect(!!document.getElementById('spotpulse-ga4')).toBe(choice === 'accepted');
  await userEvent.click(screen.getByRole('button', { name: 'Analytics preferences' }));
  expect(screen.getByRole('region', { name: 'Analytics consent' })).toBeVisible();
});

test('changing roles preserves consent and pending events', async () => {
  const analytics = open();
  await userEvent.click(screen.getByRole('button', { name: 'Accept analytics' }));
  session.user = { role: 'USER' };
  await userEvent.click(screen.getByRole('link', { name: 'Next route' }));
  act(() => document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('load')));
  expect((window as unknown as Record<string, unknown>)['ga-disable-G-TEST000000']).toBe(false);
  expect(screen.getByRole('button', { name: 'Analytics preferences' })).toBeVisible();
  expect(analytics.getChoice()).toBe('accepted');
});

test('rejection persists across a new controller and does not load analytics', async () => {
  const view = render(<MemoryRouter><AnalyticsConsent analytics={createUsageAnalytics('G-TEST000000')}/></MemoryRouter>);
  await userEvent.click(screen.getByRole('button', { name: 'Reject analytics' }));
  view.unmount();
  open();
  expect(screen.queryByRole('button', { name: 'Accept analytics' })).not.toBeInTheDocument();
  expect(window.dataLayer).toBeUndefined();
});

test('revoking before script load discards pending events', async () => {
  const analytics = open();
  await userEvent.click(screen.getByRole('button', { name: 'Accept analytics' }));
  await userEvent.click(screen.getByRole('button', { name: 'Analytics preferences' }));
  await userEvent.click(screen.getByRole('button', { name: 'Reject analytics' }));
  const count = window.dataLayer!.length;
  act(() => document.getElementById('spotpulse-ga4')!.dispatchEvent(new Event('load')));
  analytics.bookingSuccess();
  expect(window.dataLayer).toHaveLength(count);
});

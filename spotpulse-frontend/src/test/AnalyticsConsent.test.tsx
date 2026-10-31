import { StrictMode } from 'react';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { Link, MemoryRouter } from 'react-router-dom';
import { AnalyticsConsent } from '../AnalyticsConsent';
import { CONSENT_KEY, createUsageAnalytics } from '../usageAnalytics';

beforeEach(()=>{localStorage.clear(); delete (window as unknown as Record<string, unknown>)['ga-disable-G-TEST000000']; delete window.dataLayer; delete window.gtag; document.getElementById('spotpulse-ga4')?.remove();});
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

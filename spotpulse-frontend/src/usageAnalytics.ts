// Deliberately separate from F1 business/Chart.js statistics. No domain entities enter this module.
export const CONSENT_KEY = 'spotpulse.analytics-consent.v1';
export type AnalyticsChoice = 'accepted' | 'rejected' | 'unset';
type Label = 'catalog' | 'space_detail' | 'room_detail' | 'login' | 'private';
type Command = unknown[];
declare global {
  interface Window { dataLayer?: unknown[]; gtag?: (...args: unknown[]) => void; doNotTrack?: string }
  interface Navigator { readonly globalPrivacyControl?: boolean }
}
export function measurementId(value: unknown): string | undefined {
  return typeof value === 'string' && /^G-[A-Z0-9]{10}$/.test(value) ? value : undefined;
}
export function privateBrowser(): boolean {
  return navigator.globalPrivacyControl === true || navigator.doNotTrack === '1' || window.doNotTrack === '1';
}
export function routeLabel(path: string): Label {
  path = path.replace(/\/+$/, '');
  if (path === '/spaces') return 'catalog';
  if (/^\/spaces\/\d+$/.test(path)) return 'space_detail';
  if (/^\/rooms\/\d+$/.test(path)) return 'room_detail';
  if (path === '/login') return 'login';
  return 'private';
}
function storedChoice(): AnalyticsChoice {
  try { const choice = localStorage.getItem(CONSENT_KEY); return choice === 'accepted' || choice === 'rejected' ? choice : 'unset'; }
  catch { return 'unset'; }
}

export function createUsageAnalytics(configuredId: unknown) {
  const id = measurementId(configuredId);
  const externallyDisabled = !!id && (window as unknown as Record<string, unknown>)['ga-disable-' + id] === true;
  let choice = storedChoice(), ready = false, attempted = false;
  let currentPath: string | undefined, label: Label = 'private', visit = 0, pageSent = -1, spaceSent = -1, spaceReady = false;
  let pending: Command[] = [];
  const subscribers = new Set<() => void>();
  const disable = (value: boolean) => { if (id) (window as unknown as Record<string, unknown>)['ga-disable-' + id] = value; };
  const allowed = () => !!id && choice === 'accepted' && !privateBrowser() && !externallyDisabled;
  const safePage = () => ({ page_location: window.location.origin + '/usage/' + label,
    page_path: '/usage/' + label, page_title: 'SpotPulse ' + label, page_referrer: '' });
  function command(...args: unknown[]) {
    try { window.gtag?.(...args); } catch { /* analytics failures never affect app actions */ }
  }
  function send(...args: unknown[]) {
    if (!allowed()) { disable(true); return; }
    if (ready) command(...args);
    else if (pending.length < 20) pending.push(args); // bounded while a script is loading/blocked
  }
  function currentPage() {
    if (!allowed() || currentPath === undefined) return;
    if (pageSent !== visit && label !== 'private') {
      pageSent = visit; send('event', 'page_view', safePage());
    }
    if (spaceReady && label === 'space_detail' && spaceSent !== visit) {
      spaceSent = visit; send('event', 'space_view', safePage());
    }
  }
  function start() {
    if (!allowed()) { disable(true); return; }
    disable(false);
    if (attempted) { currentPage(); return; }
    attempted = true;
    window.dataLayer ??= [];
    window.gtag ??= function () { window.dataLayer!.push(arguments); };
    // Basic consent mode: none of these commands or the script exist before affirmative consent.
    command('consent', 'default', { analytics_storage: 'granted', ad_storage: 'denied', ad_user_data: 'denied', ad_personalization: 'denied' });
    command('js', new Date());
    command('config', id, { send_page_view: false, allow_google_signals: false,
      allow_ad_personalization_signals: false, cookie_expires: 30 * 86400, ...safePage() });
    const source = 'https://www.googletagmanager.com/gtag/js?id=' + id;
    const existing = document.getElementById('spotpulse-ga4') || Array.from(document.scripts).find(script => script.src === source);
    if (existing) return; // never inject another tag; a single app controller owns the lifecycle
    const script = document.createElement('script');
    script.id = 'spotpulse-ga4'; script.async = true; script.referrerPolicy = 'no-referrer';
    script.src = source;
    script.onload = () => {
      ready = true;
      if (!allowed()) { pending = []; disable(true); return; }
      command('set', safePage());
      const queued = pending; pending = [];
      queued.forEach(args => command(...args));
    };
    script.onerror = () => { pending = []; }; // no automatic retry loop
    document.head.append(script);
    currentPage();
  }
  function update(next: AnalyticsChoice, persist: boolean) {
    const changed = next !== choice;
    choice = next;
    if (persist) { try { localStorage.setItem(CONSENT_KEY, next); } catch { /* choice still applies in memory */ } }
    if (!allowed()) { disable(true); pending = []; pageSent = spaceSent = -1; }
    else start();
    if (changed) subscribers.forEach(notify => notify());
  }
  function event(name: string, extra: { search_mode?: 'text' | 'city'; query_present?: boolean } = {}) {
    if (!allowed()) { disable(true); return; }
    start(); send('event', name, { ...safePage(), ...extra });
  }
  return {
    configured: !!id,
    blocked: () => externallyDisabled || privateBrowser(),
    getChoice: () => choice,
    subscribe(notify: () => void) { subscribers.add(notify); return () => { subscribers.delete(notify); }; },
    choose(next: 'accepted' | 'rejected') { update(next, true); },
    sync() { update(storedChoice(), false); },
    resume() { if (allowed()) start(); else disable(true); },
    page(path: string) {
      if (path !== currentPath) { currentPath = path; label = routeLabel(path); visit++; spaceReady = false; }
      if (ready && allowed()) command('set', safePage());
      start(); currentPage();
    },
    spaceView() { spaceReady = true; currentPage(); },
    search(mode: 'text' | 'city', present: boolean) { event('space_search', { search_mode: mode === 'city' ? 'city' : 'text', query_present: !!present }); },
    bookingSuccess() { event('booking_success'); },
    bookingCancelled() { event('booking_cancelled'); },
  };
}

export const usageAnalytics = createUsageAnalytics(import.meta.env.VITE_GA_MEASUREMENT_ID);

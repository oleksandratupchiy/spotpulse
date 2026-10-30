import { apiUrl, request } from './api';

export interface Availability { roomId: number; from: string; to: string; occupied: { start: string; end: string }[] }
export type AvailabilityMode = 'websocket' | 'polling';
export interface AvailabilityState {
  status: string; httpRequests: number; wsMessages: number; data?: Availability; error?: string;
  lastUpdated?: string; requestMs?: number; notificationRefreshMs?: number;
}
export function availabilitySocketUrl(roomId: number) {
  const url = new URL(apiUrl(`/api/rooms/availability/ws?roomId=${roomId}`), window.location.href);
  url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:';
  return url.href;
}

/** One lifecycle owns every request, timer, socket and listener. Returns complete cancellation. */
export function watchAvailability(roomId: number, from: string, to: string, mode: AvailabilityMode,
  intervalMs: number, update: (state: AvailabilityState) => void): () => void {
  const interval = Math.max(1000, Math.min(30000, intervalMs));
  let state: AvailabilityState = { status: mode === 'polling' ? 'HTTP polling' : 'Connecting WebSocket', httpRequests: 0, wsMessages: 0 };
  let stopped = false, busy = false, dirty = false, retries = 0;
  let timer: ReturnType<typeof setTimeout> | undefined, retry: ReturnType<typeof setTimeout> | undefined;
  let watchdog: ReturnType<typeof setTimeout> | undefined;
  let requestTimer: ReturnType<typeof setTimeout> | undefined;
  let socket: WebSocket | undefined, controller: AbortController | undefined, notificationAt: number | undefined;
  const emit = (change: Partial<AvailabilityState>) => { if (!stopped) { state = { ...state, ...change }; update(state); } };
  function schedule() {
    clearTimeout(timer);
    if (!stopped && !document.hidden) timer = setTimeout(() => { void refresh(); }, mode === 'polling' ? interval : 60000);
  }
  async function refresh() {
    if (stopped || document.hidden) return;
    if (busy) { dirty = true; return; }
    busy = true; dirty = false; controller = new AbortController();
    let timedOut = false;
    requestTimer = setTimeout(() => { timedOut = true; controller?.abort(); }, 15000);
    emit({ httpRequests: state.httpRequests + 1 });
    const start = performance.now();
    try {
      const data = await request<Availability>(`/api/rooms/${roomId}/availability?from=${encodeURIComponent(from)}&to=${encodeURIComponent(to)}`, { signal: controller.signal, cache: 'no-store' });
      if (stopped || controller.signal.aborted || dirty) return;
      if (data.roomId !== roomId || !Array.isArray(data.occupied)) throw new Error('Invalid availability response.');
      emit({ data, error: undefined, lastUpdated: new Date().toISOString(), requestMs: performance.now() - start,
        notificationRefreshMs: notificationAt === undefined ? undefined : performance.now() - notificationAt });
      notificationAt = undefined;
    } catch (failure) {
      if (!stopped && timedOut) emit({ error: 'Availability request timed out. The next refresh will retry.' });
      else if (!stopped && !controller.signal.aborted) emit({ error: (failure as Error).message });
    } finally {
      clearTimeout(requestTimer);
      busy = false;
      if (dirty && !stopped && !document.hidden) { dirty = false; void refresh(); } else schedule();
    }
  }
  function connect() {
    if (stopped || document.hidden) return;
    if (typeof WebSocket === 'undefined') { emit({ status: 'WebSocket unavailable; select HTTP Polling' }); return; }
    emit({ status: retries ? `Reconnecting WebSocket (${retries}/6)` : 'Connecting WebSocket' });
    try { socket = new WebSocket(availabilitySocketUrl(roomId)); }
    catch { disconnected(); return; }
    const current = socket;
    watchdog = setTimeout(() => current.close(), 10000);
    current.onopen = () => { clearTimeout(watchdog); emit({ status: 'WebSocket connected' }); void refresh(); };
    current.onmessage = event => {
      if (stopped || typeof event.data !== 'string' || event.data.length > 512) return;
      try {
        const signal = JSON.parse(event.data);
        if (signal.roomId !== roomId || !['ready', 'availability.changed'].includes(signal.type) || typeof signal.signal !== 'string') return;
        emit({ wsMessages: state.wsMessages + 1 });
        if (signal.type === 'availability.changed') { notificationAt ??= performance.now(); void refresh(); }
      } catch { emit({ error: 'Invalid availability notification.' }); }
    };
    current.onerror = () => emit({ error: 'WebSocket connection failed. Reconnection is bounded; HTTP Polling is available.' });
    current.onclose = () => { clearTimeout(watchdog); if (socket === current) { socket = undefined; disconnected(); } };
  }
  function disconnected() {
    if (stopped || document.hidden) return;
    if (retries >= 6) { emit({ status: 'WebSocket disconnected; retry manually or select HTTP Polling' }); return; }
    const delay = Math.min(30000, 1000 * 2 ** retries++);
    emit({ status: `WebSocket reconnect in ${delay / 1000}s (${retries}/6)` });
    retry = setTimeout(connect, delay);
  }
  function closeSocket() {
    if (socket) { const current = socket; socket = undefined; current.onopen = current.onmessage = current.onerror = current.onclose = null; current.close(); }
  }
  function visibility() {
    clearTimeout(timer); clearTimeout(retry); clearTimeout(watchdog); clearTimeout(requestTimer);
    if (document.hidden) { controller?.abort(); closeSocket(); emit({ status: 'Paused while tab is hidden' }); }
    else { emit({ status: mode === 'polling' ? 'HTTP polling' : 'Resuming WebSocket' }); void refresh(); if (mode === 'websocket' && retries < 6) connect(); }
  }
  emit({}); document.addEventListener('visibilitychange', visibility);
  void refresh(); if (mode === 'websocket') connect();
  return () => {
    stopped = true; clearTimeout(timer); clearTimeout(retry); clearTimeout(watchdog); clearTimeout(requestTimer); controller?.abort();
    closeSocket(); document.removeEventListener('visibilitychange', visibility);
  };
}

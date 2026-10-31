import { act, fireEvent, render, screen } from '@testing-library/react';
import { AvailabilityPanel } from '../AvailabilityPanel';
import { watchAvailability, type AvailabilityState } from '../availability';

class FakeSocket {
  static instances: FakeSocket[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((event: { data: string }) => void) | null = null;
  onclose: (() => void) | null = null;
  onerror: (() => void) | null = null;
  close = vi.fn();
  constructor(public url: string) { FakeSocket.instances.push(this); }
  message(type = 'availability.changed', roomId = 3) { this.onmessage?.({ data: JSON.stringify({ type, roomId, signal: 'opaque-invalidation' }) }); }
}
const from = '2026-12-01T10:00', to = '2026-12-01T12:00';
const snapshot = { roomId: 3, from, to, occupied: [] as { start: string; end: string }[] };
let dispose: (() => void) | undefined;
let hidden = false;
beforeEach(() => {
  vi.useFakeTimers(); FakeSocket.instances = []; hidden = false;
  vi.spyOn(document, 'hidden', 'get').mockImplementation(() => hidden);
  vi.stubGlobal('WebSocket', FakeSocket);
  vi.spyOn(globalThis, 'fetch').mockImplementation(async () => new Response(JSON.stringify(snapshot)));
});
afterEach(() => { dispose?.(); dispose = undefined; vi.useRealTimers(); vi.unstubAllGlobals(); });
async function flush(ms = 0) { await act(async () => { await vi.advanceTimersByTimeAsync(ms); }); }

test('polling schedules after completion, pauses when hidden and resumes immediately', async () => {
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'polling', 3000, update);
  await flush(); expect(fetch).toHaveBeenCalledTimes(1);
  await flush(2999); expect(fetch).toHaveBeenCalledTimes(1);
  await flush(1); expect(fetch).toHaveBeenCalledTimes(2);
  hidden = true; document.dispatchEvent(new Event('visibilitychange'));
  await flush(15000); expect(fetch).toHaveBeenCalledTimes(2);
  expect(update.mock.lastCall![0].status).toContain('Paused');
  hidden = false; document.dispatchEvent(new Event('visibilitychange')); await flush();
  expect(fetch).toHaveBeenCalledTimes(3);
  dispose(); await flush(15000); expect(fetch).toHaveBeenCalledTimes(3);
  expect(vi.getTimerCount()).toBe(0);
});

test('an in-flight poll never overlaps and cancellation aborts it', async () => {
  vi.mocked(fetch).mockImplementation(() => new Promise(() => {}));
  dispose = watchAvailability(3, from, to, 'polling', 3000, vi.fn());
  await flush(20000); expect(fetch).toHaveBeenCalledTimes(1);
  const signal = vi.mocked(fetch).mock.calls[0][1]!.signal!;
  dispose(); expect(signal.aborted).toBe(true); expect(vi.getTimerCount()).toBe(0);
});

test('a transient polling error is visible and the next successful poll recovers', async () => {
  vi.mocked(fetch).mockResolvedValueOnce(new Response('{}', { status: 503 }));
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'polling', 3000, update);
  await flush(); expect(update.mock.lastCall![0].error).toContain('server error');
  await flush(3000); expect(update.mock.lastCall![0].error).toBeUndefined();
  expect(update.mock.lastCall![0].data).toEqual(snapshot);
});

test('a stalled HTTP request times out and polling can recover', async () => {
  vi.mocked(fetch).mockImplementationOnce((_url, options) => new Promise((_resolve, reject) => {
    options!.signal!.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')));
  }));
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'polling', 3000, update);
  await flush(15000); expect(update.mock.lastCall![0].error).toContain('timed out');
  await flush(3000); expect(update.mock.lastCall![0].data).toEqual(snapshot);
  expect(update.mock.lastCall![0].error).toBeUndefined();
});

test('WebSocket invalidations refetch authoritative occupancy and filter unrelated messages', async () => {
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'websocket', 5000, update);
  await flush(); const socket = FakeSocket.instances[0];
  expect(socket.url).toContain('ws://localhost');
  socket.onopen?.(); await flush();
  expect(update.mock.lastCall![0].status).toBe('WebSocket connected');
  socket.message('availability.changed', 9); await flush(); expect(fetch).toHaveBeenCalledTimes(2);
  vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({ ...snapshot, occupied: [{ start: from, end: to }] })));
  socket.message(); await flush();
  const state: AvailabilityState = update.mock.lastCall![0];
  expect(state.data?.occupied).toHaveLength(1); expect(state.wsMessages).toBe(1);
  expect(state.notificationRefreshMs).toBeGreaterThanOrEqual(0);
  dispose(); expect(socket.close).toHaveBeenCalledOnce(); expect(vi.getTimerCount()).toBe(0);
});

test('a change during an in-flight response discards stale data and schedules one follow-up', async () => {
  let finish: ((response: Response) => void) | undefined;
  vi.mocked(fetch).mockImplementationOnce(() => new Promise(resolve => { finish = resolve; }));
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'websocket', 5000, update);
  FakeSocket.instances[0].message(); FakeSocket.instances[0].message();
  expect(fetch).toHaveBeenCalledTimes(1);
  finish!(new Response(JSON.stringify({ ...snapshot, occupied: [{ start: from, end: to }] })));
  await flush(); expect(fetch).toHaveBeenCalledTimes(2);
  expect(update.mock.calls.some(([state]) => state.data?.occupied.length === 1)).toBe(false);
  expect(update.mock.lastCall![0].data?.occupied).toEqual([]);
});

test('reconnect uses bounded exponential backoff and refreshes missed changes', async () => {
  const update = vi.fn(); dispose = watchAvailability(3, from, to, 'websocket', 5000, update);
  await flush(); FakeSocket.instances[0].onclose?.();
  await flush(999); expect(FakeSocket.instances).toHaveLength(1);
  await flush(1); expect(FakeSocket.instances).toHaveLength(2);
  FakeSocket.instances[1].onopen?.(); await flush(); expect(fetch).toHaveBeenCalledTimes(2);
  for (const delay of [2000, 4000, 8000, 16000, 30000]) {
    FakeSocket.instances.at(-1)!.onclose?.(); await flush(delay);
  }
  FakeSocket.instances.at(-1)!.onclose?.(); await flush(120000);
  expect(FakeSocket.instances).toHaveLength(7);
  expect(update.mock.lastCall![0].status).toContain('retry manually');
});

test('simple monitoring announces availability, restarts for new times and stops cleanly', async () => {
  vi.mocked(fetch).mockResolvedValueOnce(new Response(JSON.stringify({ ...snapshot, occupied: [{ start: from, end: to }] })));
  const view = render(<AvailabilityPanel roomId={3}/>);
  expect(screen.queryByLabelText('Update mode')).not.toBeInTheDocument();
  expect(screen.queryByLabelText('Polling interval')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText('Availability from'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Availability to'), { target: { value: to } });
  fireEvent.click(screen.getByRole('button', { name: 'Notify me when available' })); await flush();
  expect(screen.getByRole('status')).toHaveTextContent('currently booked');
  await flush(5000);
  expect(screen.getByRole('status')).toHaveTextContent('Available for your selected time');
  expect(screen.queryByText(/HTTP|WebSocket/)).not.toBeInTheDocument();
  expect(FakeSocket.instances).toHaveLength(0);
  fireEvent.change(screen.getByLabelText('Availability to'), { target: { value: '2026-12-01T13:00' } });
  fireEvent.click(screen.getByRole('button', { name: 'Update times' })); await flush();
  expect(String(vi.mocked(fetch).mock.lastCall![0])).toContain(encodeURIComponent('2026-12-01T13:00'));
  const calls = vi.mocked(fetch).mock.calls.length;
  await flush(5000); expect(fetch).toHaveBeenCalledTimes(calls + 1);
  fireEvent.click(screen.getByRole('button', { name: 'Stop updates' })); await flush();
  const stopped = vi.mocked(fetch).mock.calls.length;
  await flush(60000); expect(fetch).toHaveBeenCalledTimes(stopped); expect(vi.getTimerCount()).toBe(0);
  expect(screen.queryByRole('status')).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole('button', { name: 'Notify me when available' })); await flush();
  view.unmount();
  const last = vi.mocked(fetch).mock.calls.length;
  await flush(60000); expect(fetch).toHaveBeenCalledTimes(last); expect(vi.getTimerCount()).toBe(0);
});

test('panel validates ranges and explains delayed updates without transport details or booking writes', async () => {
  vi.mocked(fetch).mockResolvedValue(new Response('{}', { status: 503 }));
  render(<AvailabilityPanel roomId={3}/>);
  fireEvent.change(screen.getByLabelText('Availability from'), { target: { value: to } });
  fireEvent.change(screen.getByLabelText('Availability to'), { target: { value: from } });
  fireEvent.click(screen.getByRole('button', { name: 'Notify me when available' }));
  expect(screen.getByRole('alert')).toHaveTextContent('end time after'); expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Availability from'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('Availability to'), { target: { value: '2026-12-01T22:01' } });
  fireEvent.click(screen.getByRole('button', { name: 'Notify me when available' }));
  expect(screen.getByRole('alert')).toHaveTextContent('up to 12 hours'); expect(fetch).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText('Availability to'), { target: { value: to } });
  fireEvent.click(screen.getByRole('button', { name: 'Notify me when available' })); await flush();
  expect(screen.getByRole('alert')).toHaveTextContent('try again automatically');
  expect(screen.queryByText(/HTTP|WebSocket/)).not.toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.every(([, options]) => !options?.method || options.method === 'GET')).toBe(true);
});

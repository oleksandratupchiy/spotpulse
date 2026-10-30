import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { TelegramAccount } from '../Telegram';

test('connect opens the actual bot link and disconnect uses session CSRF', async () => {
  let connected = false;
  const open = vi.spyOn(window, 'open').mockImplementation(() => null);
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    if (String(input) === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'masked', headerName: 'X-CSRF-TOKEN' }));
    if (options?.method === 'POST') return new Response(JSON.stringify({ url: 'https://t.me/example_bot?start=random-test-code', expiresAt: new Date(Date.now() + 300000).toISOString() }));
    if (options?.method === 'DELETE') { connected = false; return new Response(null, { status: 204 }); }
    return new Response(JSON.stringify({ configured: true, available: true, connected, expiresAt: null, bookingNotificationsEnabled: true }));
  });
  render(<TelegramAccount/>);
  await userEvent.click(await screen.findByRole('button', { name: 'Connect Telegram' }));
  expect(await screen.findByRole('link', { name: 'Open Telegram to finish connecting' })).toHaveAttribute('href', 'https://t.me/example_bot?start=random-test-code');
  expect(open).toHaveBeenCalledWith('https://t.me/example_bot?start=random-test-code', '_blank', 'noopener,noreferrer');
  await userEvent.click(screen.getByRole('button', { name: 'Cancel connection' }));
  await waitFor(() => expect(fetch).toHaveBeenCalledWith('/api/account/telegram', expect.objectContaining({ method: 'DELETE', headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'masked' }) })));
  expect(screen.queryByRole('link', { name: 'Open Telegram to finish connecting' })).not.toBeInTheDocument();
});

test('unconfigured server disables connection and connected users can disconnect', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ configured: false, available: false, connected: true, expiresAt: null, bookingNotificationsEnabled: true })));
  render(<TelegramAccount/>);
  expect(await screen.findByText('Telegram connected.')).toBeVisible();
  expect(screen.getByText('Telegram integration is unavailable on this server.')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Disconnect Telegram' })).toBeEnabled();
  expect(screen.queryByRole('button', { name: 'Connect Telegram' })).not.toBeInTheDocument();
});

test('profile exposes default notification preference and saves opt-out and opt-in with CSRF', async () => {
  let enabled = true;
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    if (String(input) === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'masked', headerName: 'X-CSRF-TOKEN' }));
    if (options?.method === 'PATCH') enabled = JSON.parse(String(options.body)).bookingNotificationsEnabled;
    return new Response(JSON.stringify({ configured: true, available: true, connected: true, expiresAt: null, bookingNotificationsEnabled: enabled }));
  });
  render(<TelegramAccount/>);
  const checkbox = await screen.findByRole('checkbox', { name: 'Booking confirmation and cancellation notifications' });
  expect(checkbox).toBeChecked();
  await userEvent.click(checkbox);
  await waitFor(() => expect(checkbox).not.toBeChecked());
  expect(screen.getByText('Booking notifications are disabled.')).toBeVisible();
  expect(fetch).toHaveBeenCalledWith('/api/account/telegram/preferences', expect.objectContaining({
    method: 'PATCH', body: JSON.stringify({ bookingNotificationsEnabled: false }),
    headers: expect.objectContaining({ 'X-CSRF-TOKEN': 'masked' })
  }));
  await userEvent.click(checkbox);
  await waitFor(() => expect(checkbox).toBeChecked());
  expect(screen.getByText('Booking notifications are enabled.')).toBeVisible();
});

test('failed preference save keeps persisted state visible and shows an error', async () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (input, options) => {
    if (String(input) === '/api/auth/csrf') return new Response(JSON.stringify({ token: 'masked', headerName: 'X-CSRF-TOKEN' }));
    if (options?.method === 'PATCH') return new Response('{}', { status: 503 });
    return new Response(JSON.stringify({ configured: true, available: true, connected: true, expiresAt: null, bookingNotificationsEnabled: false }));
  });
  render(<TelegramAccount/>);
  const checkbox = await screen.findByRole('checkbox');
  expect(checkbox).not.toBeChecked();
  await userEvent.click(checkbox);
  expect(await screen.findByRole('alert')).toHaveTextContent('SpotPulse encountered a server error. Please try again later.');
  expect(checkbox).not.toBeChecked();
  expect(checkbox).toBeEnabled();
});

import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import { availabilitySocketUrl } from '../availability';

test('the deployed basename keeps navigation under /app and API reads at /api', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async input => new Response(JSON.stringify(
    String(input) === '/api/auth/me' ? { authenticated: false, user: null, loginEnabled: true }
      : { items: [{ id: 3, name: 'Deployed Hub', city: 'Kyiv', address: 'Street', imageUrl: null }], skip: 0, limit: 6, total: 1, nextLink: null }
  )));
  render(<MemoryRouter basename="/app" initialEntries={['/app/spaces']}><App/></MemoryRouter>);
  expect(await screen.findByRole('heading', { name: 'Deployed Hub' })).toBeVisible();
  expect(screen.getByRole('link', { name: 'Explore spaces' })).toHaveAttribute('href', '/app/spaces');
  expect(screen.getByRole('link', { name: 'View space' })).toHaveAttribute('href', '/app/spaces/3');
  expect(fetch.mock.calls.every(([url]) => String(url).startsWith('/api/'))).toBe(true);
});

test('a relative API WebSocket URL stays on the current origin', () => {
  const url = new URL(availabilitySocketUrl(3));
  expect(url.host).toBe(window.location.host);
  expect(url.protocol).toBe(window.location.protocol === 'https:' ? 'wss:' : 'ws:');
  expect(url.pathname).toBe('/api/rooms/availability/ws');
  expect(url.searchParams.get('roomId')).toBe('3');
});

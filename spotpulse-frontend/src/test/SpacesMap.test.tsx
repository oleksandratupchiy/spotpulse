import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import SpacesMap, { MapCanvas } from '../SpacesMap';
import { MapBoundary } from '../components';

// The first lazy provider import can take longer on Windows CI workers.
configure({ asyncUtilTimeout: 10_000 });

const provider = vi.hoisted(() => ({ positions: [] as number[][], tileError: undefined as undefined | (() => void), fail: false }));
vi.mock('leaflet', () => ({
  map: (container: HTMLElement) => {
    if (provider.fail) throw new Error('Provider unavailable');
    return { container, fitBounds: vi.fn(), remove: () => container.replaceChildren() };
  },
  tileLayer: () => ({ on: (_: string, callback: () => void) => {
    provider.tileError = callback;
    return { addTo: vi.fn() };
  } }),
  divIcon: vi.fn(),
  featureGroup: () => ({ getBounds: vi.fn() }),
  marker: (position: number[], options: { title: string }) => {
    provider.positions.push(position);
    return { bindPopup: (popup: HTMLElement) => ({ addTo: (map: { container: HTMLElement }) => {
      const button = document.createElement('button'); button.textContent = options.title;
      button.onclick = () => map.container.append(popup); map.container.append(button);
      return {};
    } }) };
  },
}));

const spaces = [
  { id: 3, name: 'River Hub', city: 'Kyiv', address: 'River Street 7', latitude: 50.45, longitude: 30.52 },
  { id: 4, name: 'Garden Hub', city: 'Lviv', address: 'Garden Street 1', latitude: 49.84, longitude: 24.03 },
];
const mapData = { items: spaces, total: 3, located: 2, missingCoordinates: 1, limit: 200, truncated: false };
const listData = { items: spaces, skip: 0, limit: 6, total: 3, nextLink: null };
function api() {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async input => {
    const url = String(input);
    return new Response(JSON.stringify(url.includes('/map?') ? mapData : url === '/api/spaces/3' ? { ...spaces[0], description: 'Quiet work', rooms: [], imageUrl: null } : url.startsWith('/api/rooms?') ? { ...listData, items: [] } : listData));
  });
}
beforeEach(() => { provider.positions = []; provider.fail = false; provider.tileError = undefined; });

test('renders multiple dynamic markers and popup links navigate in the SPA', async () => {
  api(); render(<MemoryRouter><App/></MemoryRouter>);
  await userEvent.click(screen.getByRole('button', { name: 'Show map' }));
  await userEvent.click(await screen.findByRole('button', { name: 'River Hub, Kyiv' }));
  expect(provider.positions).toEqual([[50.45, 30.52], [49.84, 24.03]]);
  expect(screen.getByText('Kyiv · River Street 7')).toBeVisible();
  expect(screen.getByRole('button', { name: 'Garden Hub, Lviv' })).toBeVisible();
  const link = screen.getAllByRole('link', { name: 'View space' }).find(link => link.closest('.spaces-map'))!;
  expect(link).toHaveAttribute('href', '/spaces/3');
  await userEvent.click(link);
  expect(await screen.findByText('Quiet work')).toBeVisible();
});

test('map is opt-in, shares immediate city and search filtering with list, and is independent of pagination', async () => {
  const fetch = api(); render(<MemoryRouter><App/></MemoryRouter>);
  await screen.findByRole('heading', { name: 'River Hub' });
  expect(fetch.mock.calls.some(([url]) => String(url).includes('/map?'))).toBe(false);
  await userEvent.click(screen.getByRole('button', { name: 'Show map' }));
  await screen.findByRole('button', { name: 'River Hub, Kyiv' });
  expect(screen.getByRole('status')).toHaveTextContent('3 matching spaces. 2 markers shown; 1 without coordinates.');
  await userEvent.selectOptions(screen.getByLabelText('Search by'), 'city');
  fireEvent.change(screen.getByLabelText('Search spaces'), { target: { value: 'Kyiv' } });
  await waitFor(() => {
    expect(fetch).toHaveBeenCalledWith('/api/spaces/map?limit=200&city=Kyiv', expect.anything());
    expect(fetch).toHaveBeenCalledWith('/api/spaces?skip=0&limit=6&city=Kyiv', expect.anything());
  });
  await userEvent.selectOptions(screen.getByLabelText('Search by'), 'search');
  fireEvent.change(screen.getByLabelText('Search spaces'), { target: { value: 'River' } });
  await waitFor(() => {
    expect(fetch).toHaveBeenCalledWith('/api/spaces/map?limit=200&search=River', expect.anything());
    expect(fetch).toHaveBeenCalledWith('/api/spaces?skip=0&limit=6&search=River', expect.anything());
  });
});

test.each([
  [{ ...mapData, items: [], total: 0, located: 0, missingCoordinates: 0 }, 'No spaces match these filters.'],
  [{ ...mapData, items: [], located: 0, missingCoordinates: 3 }, 'Map locations are not available'],
  [{ ...mapData, truncated: true, located: 250 }, 'Showing the first 200 of 250'],
])('explains empty, missing and limited results', async (data, message) => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify(data)));
  render(<MemoryRouter><SpacesMap filter=""/></MemoryRouter>);
  expect(await screen.findByText(new RegExp(message))).toBeVisible();
  if (!data.items.length) expect(provider.positions).toHaveLength(0);
});

test('handles API failure and retries', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 503 }));
  render(<MemoryRouter><SpacesMap filter=""/></MemoryRouter>);
  expect(await screen.findByRole('alert')).toHaveTextContent('server error');
  fetch.mockResolvedValue(new Response(JSON.stringify(mapData)));
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByRole('button', { name: 'Garden Hub, Lviv' })).toBeVisible();
});

test('replaces markers when data changes instead of retaining stale results', async () => {
  const view = render(<MemoryRouter><MapCanvas spaces={spaces}/></MemoryRouter>);
  await screen.findByRole('button', { name: 'Garden Hub, Lviv' });
  view.rerender(<MemoryRouter><MapCanvas spaces={[spaces[0]]}/></MemoryRouter>);
  await waitFor(() => expect(screen.queryByRole('button', { name: 'Garden Hub, Lviv' })).not.toBeInTheDocument());
});

test('reports tile and provider initialization failures without hiding list navigation', async () => {
  const view = render(<MemoryRouter><MapCanvas spaces={spaces}/></MemoryRouter>);
  await screen.findByRole('button', { name: 'Garden Hub, Lviv' });
  fireEvent.click(screen.getByRole('button', { name: 'Garden Hub, Lviv' }));
  const { act } = await import('@testing-library/react');
  act(() => provider.tileError?.());
  expect(screen.getByRole('alert')).toHaveTextContent('Map tiles could not be loaded');
  expect(screen.getByRole('link', { name: 'View space' })).toHaveAttribute('href', '/spaces/4');
  view.unmount(); provider.fail = true;
  render(<MemoryRouter><MapCanvas spaces={spaces}/></MemoryRouter>);
  expect(await screen.findByRole('alert')).toHaveTextContent('The map could not be loaded');
});

test('a failed map chunk leaves surrounding discovery content available', () => {
  vi.spyOn(console, 'error').mockImplementation(() => {});
  function FailedChunk(): never { throw new Error('Chunk download failed'); }
  render(<><MapBoundary><FailedChunk/></MapBoundary><p>Space list remains available</p></>);
  expect(screen.getByRole('alert')).toHaveTextContent('Refresh the page');
  expect(screen.getByText('Space list remains available')).toBeVisible();
});

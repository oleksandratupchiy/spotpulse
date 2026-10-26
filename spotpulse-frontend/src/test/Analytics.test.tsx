import { configure, fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import App from '../App';
import Analytics from '../Analytics';
import { type BookingAnalytics } from '../api';

configure({ asyncUtilTimeout: 10_000 });
vi.mock('react-chartjs-2', () => {
  function chart(kind: string) {
    return (props: { data: unknown; 'aria-label': string }) => <div role="img" aria-label={props['aria-label']} data-testid={kind} data-chart={JSON.stringify(props.data)}/>;
  }
  return { Line: chart('line'), Bar: chart('bar'), Doughnut: chart('doughnut') };
});

const data: BookingAnalytics = {
  from: '2026-10-01', to: '2026-10-03', grouping: 'BOOKING_START_DATE', total: 8,
  statuses: { confirmed: 6, cancelled: 2 }, popularRoomsLimit: 10,
  daily: [{ date: '2026-10-01', confirmed: 2, cancelled: 0 }, { date: '2026-10-02', confirmed: 1, cancelled: 2 }, { date: '2026-10-03', confirmed: 3, cancelled: 0 }],
  popularRooms: [
    { roomId: 8, roomName: 'Meeting room', spaceId: 3, spaceName: 'River Hub', count: 4 },
    { roomId: 9, roomName: 'Meeting room', spaceId: 4, spaceName: 'Garden Hub', count: 2 },
  ],
};
function api(body = data, status = 200) {
  return vi.spyOn(globalThis, 'fetch').mockImplementation(async input => String(input) === '/api/auth/me' ? new Response(JSON.stringify({ authenticated: true, loginEnabled: true, user: { id: 1, name: 'Admin', email: 'admin@example.test', role: 'ADMIN' } })) : new Response(JSON.stringify(body), { status }));
}
function open() { return render(<MemoryRouter><Analytics/></MemoryRouter>); }
function chart(kind: string) { return JSON.parse(screen.getByTestId(kind).getAttribute('data-chart')!); }

test('analytics route renders the management disclosure and all three charts with API values', async () => {
  const fetch = api(); render(<MemoryRouter initialEntries={['/analytics']}><App/></MemoryRouter>);
  expect(await screen.findByRole('heading', { name: 'Booking analytics' })).toBeVisible();
  expect(screen.getByText(/Management analytics/)).toBeVisible();
  expect(screen.getByRole('link', { name: 'Analytics' })).toHaveAttribute('href', '/analytics');
  expect(await screen.findByTestId('line')).toBeVisible();
  expect(chart('line').datasets.map((set: { data: number[] }) => set.data)).toEqual([[2, 1, 3], [0, 2, 0]]);
  expect(chart('bar').labels).toEqual(['Meeting room — River Hub (#8)', 'Meeting room — Garden Hub (#9)']);
  expect(chart('bar').datasets[0].data).toEqual([4, 2]);
  expect(chart('doughnut').datasets[0].data).toEqual([6, 2]);
  expect(screen.getByRole('status')).toHaveTextContent('8 scheduled bookings; 6 confirmed, 2 cancelled');
  expect(screen.getByText(/Each booking is counted once/)).toBeVisible();
  expect(fetch.mock.calls.every(([url]) => (String(url).startsWith('/api/analytics/bookings?') || String(url) === '/api/auth/me'))).toBe(true);
  await userEvent.click(screen.getByText('View daily counts as a table'));
  expect(screen.getByRole('table')).toBeVisible();
  await userEvent.click(screen.getByText('View room ranking as text'));
  expect(screen.getByRole('link', { name: 'Meeting room — River Hub (#8)' })).toHaveAttribute('href', '/rooms/8');
});

test('shows loading while the aggregate request is pending', () => {
  vi.spyOn(globalThis, 'fetch').mockImplementation(() => new Promise(() => {})); open();
  expect(screen.getByRole('status')).toHaveTextContent('Loading');
  expect(screen.queryByTestId('line')).not.toBeInTheDocument();
});

test('shows API errors and retries without a page reload', async () => {
  const fetch = api(data, 500); open();
  expect(await screen.findByRole('alert')).toHaveTextContent('server error');
  fetch.mockImplementation(async () => new Response(JSON.stringify(data)));
  await userEvent.click(screen.getByRole('button', { name: 'Try again' }));
  expect(await screen.findByTestId('doughnut')).toBeVisible();
});

test('date-range presets refetch and update charts', async () => {
  const fetch = api(); open(); await screen.findByTestId('line');
  fetch.mockImplementation(async () => new Response(JSON.stringify({ ...data, total: 1, statuses: { confirmed: 1, cancelled: 0 }, daily: [{ date: '2026-10-01', confirmed: 1, cancelled: 0 }] })));
  await userEvent.selectOptions(screen.getByLabelText('Date range'), 'next7');
  const from = (screen.getByLabelText('Start date') as HTMLInputElement).value;
  const to = (screen.getByLabelText('End date (inclusive)') as HTMLInputElement).value;
  expect((Date.parse(to) - Date.parse(from)) / 86400000).toBe(6);
  await waitFor(() => expect(fetch).toHaveBeenLastCalledWith(`/api/analytics/bookings?from=${from}&to=${to}`, expect.anything()));
  await waitFor(() => expect(chart('line').datasets[0].data).toEqual([1]));
  expect(chart('doughnut').datasets[0].data).toEqual([1, 0]);
});

test('applies custom inclusive dates only after submission', async () => {
  const fetch = api(); open(); await screen.findByTestId('line'); const before = fetch.mock.calls.length;
  fireEvent.change(screen.getByLabelText('Start date'), { target: { value: '2026-10-01' } });
  fireEvent.change(screen.getByLabelText('End date (inclusive)'), { target: { value: '2026-10-03' } });
  expect(fetch).toHaveBeenCalledTimes(before);
  await userEvent.click(screen.getByRole('button', { name: 'Apply dates' }));
  await waitFor(() => expect(fetch).toHaveBeenLastCalledWith('/api/analytics/bookings?from=2026-10-01&to=2026-10-03', expect.anything()));
});

test.each([['2026-10-03', '2026-10-01'], ['2024-01-01', '2025-01-01']])('rejects invalid custom ranges locally (%s, %s)', async (from, to) => {
  const fetch = api(); open(); await screen.findByTestId('line'); const before = fetch.mock.calls.length;
  fireEvent.change(screen.getByLabelText('Start date'), { target: { value: from } });
  fireEvent.change(screen.getByLabelText('End date (inclusive)'), { target: { value: to } });
  await userEvent.click(screen.getByRole('button', { name: 'Apply dates' }));
  expect(screen.getByRole('alert')).toHaveTextContent('1 to 366 days');
  expect(fetch).toHaveBeenCalledTimes(before);
});

test('refreshes data for the applied range and updates all charts', async () => {
  const fetch = api(); open(); await screen.findByTestId('line');
  const path = fetch.mock.calls[0][0];
  fetch.mockImplementation(async () => new Response(JSON.stringify({ ...data, total: 9, statuses: { confirmed: 7, cancelled: 2 }, popularRooms: [{ ...data.popularRooms[0], count: 7 }], daily: [{ date: data.from, confirmed: 7, cancelled: 2 }] })));
  await userEvent.click(screen.getByRole('button', { name: 'Refresh data' }));
  await waitFor(() => expect(chart('doughnut').datasets[0].data).toEqual([7, 2]));
  expect(chart('line').datasets[0].data).toEqual([7]);
  expect(chart('bar').datasets[0].data).toEqual([7]);
  expect(fetch).toHaveBeenLastCalledWith(path, expect.anything());
});

test('empty data shows a meaningful message instead of an empty doughnut or fabricated values', async () => {
  api({ ...data, total: 0, statuses: { confirmed: 0, cancelled: 0 }, popularRooms: [], daily: data.daily.map(day => ({ ...day, confirmed: 0, cancelled: 0 })) }); open();
  expect(await screen.findByRole('heading', { name: 'No bookings in this date range' })).toBeVisible();
  expect(screen.getByRole('status')).toHaveTextContent('0 scheduled bookings');
  expect(screen.queryByTestId('doughnut')).not.toBeInTheDocument();
  expect(screen.queryByTestId('bar')).not.toBeInTheDocument();
});

test('cancelled-only data is displayed without treating it as room popularity', async () => {
  api({ ...data, total: 2, statuses: { confirmed: 0, cancelled: 2 }, popularRooms: [], daily: data.daily.map(day => ({ ...day, confirmed: 0 })) }); open();
  expect(await screen.findByText('No confirmed bookings in this range.')).toBeVisible();
  expect(chart('doughnut').datasets[0].data).toEqual([0, 2]);
  expect(screen.queryByTestId('bar')).not.toBeInTheDocument();
  expect(chart('line').datasets[0].data).toEqual([0, 0, 0]);
});

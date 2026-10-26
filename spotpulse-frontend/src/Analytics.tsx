import { useState, type FormEvent } from 'react';
import { Link } from 'react-router-dom';
import {
  Chart as ChartJS, CategoryScale, LinearScale, PointElement, LineElement,
  BarElement, ArcElement, Tooltip, Legend, type ChartOptions,
} from 'chart.js';
import { Line, Bar, Doughnut } from 'react-chartjs-2';
import { type BookingAnalytics } from './api';
import { Failure, Loading, useResource } from './components';

// Module-level registration; react-chartjs-2 updates and destroys chart instances.
ChartJS.register(CategoryScale, LinearScale, PointElement, LineElement, BarElement, ArcElement, Tooltip, Legend);

const number = new Intl.NumberFormat('en-GB');
const dateFormat = new Intl.DateTimeFormat('en-GB', { day: '2-digit', month: 'short', year: 'numeric', timeZone: 'UTC' });
const formatDate = (date: string) => dateFormat.format(new Date(date + 'T00:00:00Z'));
const green = '#24685c';
const amber = '#99731b';
const countAxis = { beginAtZero: true, ticks: { precision: 0 }, title: { display: true, text: 'Bookings' } };
const lineOptions: ChartOptions<'line'> = {
  responsive: true, maintainAspectRatio: false,
  interaction: { mode: 'index', intersect: false },
  scales: { y: countAxis, x: { title: { display: true, text: 'Scheduled start date' } } },
  plugins: { tooltip: { callbacks: { label: item => `${item.dataset.label}: ${number.format(Number(item.raw))} bookings` } } },
};
const barOptions: ChartOptions<'bar'> = {
  responsive: true, maintainAspectRatio: false, indexAxis: 'y',
  scales: { x: countAxis, y: { ticks: { autoSkip: false } } },
  plugins: { legend: { display: false }, tooltip: { callbacks: { label: item => `${number.format(Number(item.raw))} confirmed bookings` } } },
};
const doughnutOptions: ChartOptions<'doughnut'> = {
  responsive: true, maintainAspectRatio: false,
  plugins: { legend: { position: 'bottom' }, tooltip: { callbacks: { label: item => `${item.label}: ${number.format(Number(item.raw))} bookings` } } },
};

function iso(date: Date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}
function presetDates(preset: string) {
  const today = new Date();
  const start = new Date(today); const end = new Date(today);
  const days = preset.endsWith('7') ? 7 : 30;
  if (preset.startsWith('next')) end.setDate(end.getDate() + days - 1);
  else start.setDate(start.getDate() - days + 1);
  return { from: iso(start), to: iso(end) };
}

export default function Analytics() {
  const [range, setRange] = useState(() => presetDates('last30'));
  const [draft, setDraft] = useState(range);
  const [preset, setPreset] = useState('last30');
  const [validation, setValidation] = useState('');
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<BookingAnalytics>(`/api/analytics/bookings?from=${range.from}&to=${range.to}`, refresh);
  function apply(event: FormEvent) {
    event.preventDefault();
    const days = (Date.parse(draft.to) - Date.parse(draft.from)) / 86400000 + 1;
    if (!draft.from || !draft.to || !Number.isFinite(days) || days < 1 || days > 366) {
      setValidation('Choose a start and end date covering 1 to 366 days.'); return;
    }
    setValidation(''); setRange(draft); setRefresh(value => value + 1);
  }
  return <>
    <section className="hero mb-4"><p className="eyebrow">BOOKING INSIGHTS</p><h1>Booking analytics</h1>
      <p className="lead">See when meetings are scheduled and which rooms are booked most often.</p>
      <p className="alert alert-info">Public demonstration analytics. Aggregate data only; this view has no role-based access restrictions.</p>
      <p>Dates refer to scheduled booking starts, using the backend’s stored calendar times. Each booking is counted once on its start date, including meetings spanning several days. These counts do not measure attendance, utilization hours or revenue.</p>
    </section>
    <form className="search-panel row g-3 align-items-end mb-4" onSubmit={apply}>
      <div className="col-sm-6 col-lg-3"><label htmlFor="analytics-preset" className="form-label">Date range</label>
        <select id="analytics-preset" className="form-select" value={preset} onChange={event => {
          const value = event.target.value; setPreset(value); setValidation('');
          if (value !== 'custom') { const dates = presetDates(value); setDraft(dates); setRange(dates); }
        }}><option value="last7">Last 7 days</option><option value="last30">Last 30 days</option><option value="next7">Next 7 days</option><option value="next30">Next 30 days</option><option value="custom">Custom dates</option></select>
      </div>
      <div className="col-sm-6 col-lg-3"><label htmlFor="analytics-from" className="form-label">Start date</label>
        <input id="analytics-from" className="form-control" type="date" min="0001-01-01" max="9998-12-31" required value={draft.from} onChange={event => { setDraft({ ...draft, from: event.target.value }); setPreset('custom'); }}/></div>
      <div className="col-sm-6 col-lg-3"><label htmlFor="analytics-to" className="form-label">End date (inclusive)</label>
        <input id="analytics-to" className="form-control" type="date" min="0001-01-01" max="9998-12-31" required value={draft.to} onChange={event => { setDraft({ ...draft, to: event.target.value }); setPreset('custom'); }}/></div>
      <div className="col-sm-6 col-lg-3 d-flex gap-2"><button className="btn btn-primary">Apply dates</button>
        <button type="button" className="btn btn-outline-primary" onClick={() => setRefresh(value => value + 1)}>Refresh data</button></div>
      <p className="small text-secondary mb-0">Up to 366 days. Presets use today’s date on this device. Edited dates apply when you select Apply dates.</p>
    </form>
    {validation && <p role="alert" className="alert alert-danger">{validation}</p>}
    {error ? <Failure error={error} retry={() => setRefresh(value => value + 1)}/> : !data ? <Loading/> : <AnalyticsResults data={data}/>}
  </>;
}

function AnalyticsResults({ data }: { data: BookingAnalytics }) {
  const { confirmed, cancelled } = data.statuses;
  return <>
    <p role="status">{formatDate(data.from)} – {formatDate(data.to)}: {number.format(data.total)} scheduled bookings; {number.format(confirmed)} confirmed, {number.format(cancelled)} cancelled.</p>
    {!data.total ? <section className="card card-body"><h2 className="h4">No bookings in this date range</h2><p className="mb-0">Try another range, including upcoming dates, or <Link to="/spaces">explore spaces</Link> to schedule a meeting.</p></section> : <div className="row g-4">
      <section className="col-12" aria-labelledby="booking-trend-title"><div className="card card-body p-4">
        <h2 id="booking-trend-title" className="h4">Bookings over time</h2><p>Confirmed and cancelled bookings by scheduled start date. Dates without bookings have zero counts.</p>
        <div className="analytics-chart"><Line role="img" aria-label="Bookings over time by scheduled start date" data={{
          labels: data.daily.map(day => formatDate(day.date)), datasets: [
            { label: 'Confirmed', data: data.daily.map(day => day.confirmed), borderColor: green, backgroundColor: green, tension: 0, pointRadius: 3 },
            { label: 'Cancelled', data: data.daily.map(day => day.cancelled), borderColor: amber, backgroundColor: amber, borderDash: [6, 4], tension: 0, pointRadius: 3 },
          ],
        }} options={lineOptions}/></div>
        <details className="mt-3"><summary>View daily counts as a table</summary><div className="table-responsive analytics-table"><table className="table"><caption>Scheduled booking starts, including dates with zero bookings</caption><thead><tr><th scope="col">Date</th><th scope="col">Confirmed</th><th scope="col">Cancelled</th></tr></thead><tbody>{data.daily.map(day => <tr key={day.date}><th scope="row">{formatDate(day.date)}</th><td>{number.format(day.confirmed)}</td><td>{number.format(day.cancelled)}</td></tr>)}</tbody></table></div></details>
      </div></section>
      <section className="col-lg-8" aria-labelledby="popular-rooms-title"><div className="card card-body p-4 h-100">
        <h2 id="popular-rooms-title" className="h4">Most popular meeting rooms</h2><p>Top {data.popularRoomsLimit} rooms by confirmed booking count in this range. Ties use room ID. Cancelled bookings are excluded.</p>
        {!data.popularRooms.length ? <p>No confirmed bookings in this range.</p> : <>
          <div className="analytics-chart" style={{ height: Math.max(300, data.popularRooms.length * 48) }}><Bar role="img" aria-label="Most popular meeting rooms by confirmed bookings" options={barOptions} data={{
            labels: data.popularRooms.map(room => `${room.roomName} — ${room.spaceName} (#${room.roomId})`),
            datasets: [{ label: 'Confirmed bookings', data: data.popularRooms.map(room => room.count), backgroundColor: green }],
          }}/></div>
          <details className="mt-3"><summary>View room ranking as text</summary><ol>{data.popularRooms.map(room => <li key={room.roomId}><Link to={`/rooms/${room.roomId}`}>{room.roomName} — {room.spaceName} (#{room.roomId})</Link>: {number.format(room.count)} confirmed bookings</li>)}</ol></details>
        </>}
      </div></section>
      <section className="col-lg-4" aria-labelledby="booking-status-title"><div className="card card-body p-4 h-100">
        <h2 id="booking-status-title" className="h4">Booking status distribution</h2><p>{number.format(confirmed)} confirmed and {number.format(cancelled)} cancelled bookings.</p>
        <div className="analytics-chart"><Doughnut role="img" aria-label={`Booking status: ${number.format(confirmed)} confirmed and ${number.format(cancelled)} cancelled`} options={doughnutOptions} data={{ labels: ['Confirmed', 'Cancelled'], datasets: [{ data: [confirmed, cancelled], backgroundColor: [green, amber] }] }}/></div>
      </div></section>
    </div>}
  </>;
}

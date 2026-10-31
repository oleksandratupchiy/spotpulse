export const MAX_BOOKING_HOURS = 12;
export const MAX_ROOM_CAPACITY = 15;
export const BOOKING_DURATION_MESSAGE = 'Bookings can last up to 12 hours.';

// API timestamps are local calendar values. Use UTC only for arithmetic so browser
// timezone/DST changes do not alter the duration accepted by the backend.
export function calendarTime(value: string) { return Date.parse(value + 'Z'); }
export function bookingEndLimit(start: string) {
  const time = calendarTime(start);
  return Number.isFinite(time) ? new Date(time + MAX_BOOKING_HOURS * 3600000).toISOString().slice(0, 16) : undefined;
}
export function exceedsBookingDuration(start: string, end: string) {
  return calendarTime(end) - calendarTime(start) > MAX_BOOKING_HOURS * 3600000;
}

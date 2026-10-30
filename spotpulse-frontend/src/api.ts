export interface Room { id: number; name: string; capacity: number; hourlyRate: number; spaceId: number; spaceName: string }
export interface Space { id: number; name: string; description: string | null; city: string; address: string; imageUrl: string | null; contactEmail: string | null; rooms: Room[]; latitude?: number | null; longitude?: number | null; premium?: boolean; featured?: boolean; premiumBadge?: boolean }
export interface FeatureFlag { key: string; description: string; enabled: boolean }
export interface PremiumState { premium: boolean; featured: boolean; analytics: boolean; badges: boolean }
export interface RoomPerformance { spaceId: number; from: string; to: string; grouping: string; rooms: { roomId: number; roomName: string; confirmed: number; cancelled: number; cancellationRate: number }[] }
export interface MapSpace { id: number; name: string; city: string; address: string; latitude: number; longitude: number }
export interface MapData { items: MapSpace[]; total: number; located: number; missingCoordinates: number; limit: number; truncated: boolean }
export interface User { id: number; name: string; email: string; role: string }
export interface Booking { id: number; roomId: number; roomName: string; userId: number; userName: string; startTime: string; endTime: string; status: string }
export interface OffsetPage<T> { items: T[]; skip: number; limit: number; total: number; nextLink: string | null }
export interface BookingInput { roomId: number; startTime: string; endTime: string }
export interface BookingAnalytics {
  from: string; to: string; grouping: 'BOOKING_START_DATE'; total: number;
  statuses: { confirmed: number; cancelled: number };
  daily: { date: string; confirmed: number; cancelled: number }[];
  popularRoomsLimit: number;
  popularRooms: { roomId: number; roomName: string; spaceId: number; spaceName: string; count: number }[];
}
const base = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '');

// B1 returns backend-relative /api links. Keep the configured origin/base for every page.
export function apiUrl(path: string): string {
  if (!path.startsWith('/api/')) throw new Error('Invalid API path');
  return base + path.slice(4);
}
export class ApiError extends Error {
  constructor(public status: number, message: string, public errors: { field: string; message: string }[] = []) { super(message); }
}
export async function request<T>(path: string, options: RequestInit = {}): Promise<T> {
  let response: Response;
  try {
    const headers: Record<string, string> = { Accept: 'application/json', ...(options.body && !(options.body instanceof FormData) ? { 'Content-Type': 'application/json' } : {}), ...options.headers as Record<string, string> };
    if (!['GET', 'HEAD', 'OPTIONS'].includes((options.method || 'GET').toUpperCase())) {
      // Obtain a fresh masked session token for each mutation, including after login/logout.
      const csrfResponse = await fetch(apiUrl('/api/auth/csrf'), { credentials: 'same-origin', headers: { Accept: 'application/json' } });
      if (!csrfResponse.ok) throw new ApiError(csrfResponse.status, 'Cannot obtain a CSRF token. Refresh and try again.');
      const csrf = await csrfResponse.json();
      if (typeof csrf.token !== 'string' || typeof csrf.headerName !== 'string') throw new ApiError(403, 'Invalid CSRF response. Refresh and try again.');
      headers[csrf.headerName] = csrf.token;
    }
    response = await fetch(apiUrl(path), { ...options, credentials: 'same-origin', headers });
  } catch (error) {
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError') throw error;
    if (error instanceof ApiError) throw error;
    throw new ApiError(0, 'Cannot reach SpotPulse. Check your connection and try again.');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    const fallback: Record<number, string> = { 400: 'Please check your input.', 401: 'Sign in to continue.', 403: 'You do not have permission for this action. Refresh if your session changed.', 404: 'This resource could not be found.', 409: 'This request conflicts with existing data. Refresh and try again.' };
    throw new ApiError(response.status, response.status >= 500 ? 'SpotPulse encountered a server error. Please try again later.' : problem.detail || fallback[response.status] || 'The request failed.', problem.errors || []);
  }
  return response.status === 204 ? undefined as T : response.json() as Promise<T>;
}

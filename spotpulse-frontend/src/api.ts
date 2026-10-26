export interface Room { id: number; name: string; capacity: number; hourlyRate: number; spaceId: number; spaceName: string }
export interface Space { id: number; name: string; description: string | null; city: string; address: string; imageUrl: string | null; contactEmail: string | null; rooms: Room[]; latitude?: number | null; longitude?: number | null }
export interface MapSpace { id: number; name: string; city: string; address: string; latitude: number; longitude: number }
export interface MapData { items: MapSpace[]; total: number; located: number; missingCoordinates: number; limit: number; truncated: boolean }
export interface User { id: number; name: string; email: string; role: string }
export interface Booking { id: number; roomId: number; roomName: string; userId: number; userName: string; startTime: string; endTime: string; status: string }
export interface OffsetPage<T> { items: T[]; skip: number; limit: number; total: number; nextLink: string | null }
export interface BookingInput { roomId: number; userId: number; startTime: string; endTime: string }
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
    response = await fetch(apiUrl(path), { ...options, headers: { Accept: 'application/json', ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers } });
  } catch (error) {
    if (error && typeof error === 'object' && 'name' in error && error.name === 'AbortError') throw error;
    throw new ApiError(0, 'Cannot reach SpotPulse. Check your connection and try again.');
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    const fallback: Record<number, string> = { 400: 'Please check your input.', 404: 'This resource could not be found.', 409: 'This request conflicts with an existing booking. Choose another time.' };
    throw new ApiError(response.status, response.status >= 500 ? 'SpotPulse encountered a server error. Please try again later.' : problem.detail || fallback[response.status] || 'The request failed.', problem.errors || []);
  }
  return response.json() as Promise<T>;
}

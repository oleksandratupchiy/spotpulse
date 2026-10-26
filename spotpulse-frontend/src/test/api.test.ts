import { ApiError, apiUrl, request } from '../api';

test('maps backend pagination links to the configured API base', () => {
  expect(apiUrl('/api/rooms?spaceId=3&skip=6&limit=6')).toBe('/api/rooms?spaceId=3&skip=6&limit=6');
  expect(() => apiUrl('https://unrelated.test/api/users')).toThrow('Invalid API path');
});
test('provides useful network failure feedback', async () => {
  vi.spyOn(globalThis, 'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
  await expect(request('/api/spaces')).rejects.toMatchObject({ status: 0, message: expect.stringContaining('Cannot reach') });
});
test('preserves request cancellation', async () => {
  vi.spyOn(globalThis, 'fetch').mockRejectedValue(new DOMException('Aborted', 'AbortError'));
  await expect(request('/api/spaces')).rejects.toMatchObject({ name: 'AbortError' });
});
test('handles non-JSON HTTP errors', async () => {
  vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('proxy error', { status: 500 }));
  await expect(request('/api/spaces')).rejects.toBeInstanceOf(ApiError);
});

test('does not submit mutations when a CSRF token cannot be obtained', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('{}', { status: 403 }));
  await expect(request('/api/spaces', { method: 'POST', body: '{}' })).rejects.toMatchObject({ status: 403 });
  expect(fetch).toHaveBeenCalledTimes(1);
  expect(fetch.mock.calls[0][0]).toBe('/api/auth/csrf');
});

test('uses fresh CSRF tokens and supports empty logout/delete responses', async () => {
  const fetch = vi.spyOn(globalThis, 'fetch')
    .mockResolvedValueOnce(new Response(JSON.stringify({ token: 'masked-token', headerName: 'X-CSRF-TOKEN' })))
    .mockResolvedValueOnce(new Response(null, { status: 204 }));
  await expect(request('/api/auth/logout', { method: 'POST' })).resolves.toBeUndefined();
  expect(fetch.mock.calls[1][1]).toMatchObject({ credentials: 'same-origin', headers: { 'X-CSRF-TOKEN': 'masked-token' } });
});

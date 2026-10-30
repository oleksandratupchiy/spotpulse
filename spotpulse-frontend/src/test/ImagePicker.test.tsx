import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { ImagePicker } from '../ImagePicker';
import { SpaceForm } from '../Management';
import { inspectImage } from '../images';
import { SpaceImage } from '../components';

function png(width = 3, height = 2) {
  const bytes = new Uint8Array(32);
  bytes.set([137, 80, 78, 71, 13, 10, 26, 10], 0);
  bytes.set([73, 72, 68, 82], 12);
  new DataView(bytes.buffer).setUint32(16, width); new DataView(bytes.buffer).setUint32(20, height);
  return bytes;
}
const file = (name = 'office.png') => new File([png()], name, { type: 'image/png' });
beforeEach(() => {
  let id = 0;
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: vi.fn(() => `blob:preview-${++id}`) });
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: vi.fn() });
  vi.stubGlobal('Image', class {
    naturalWidth = 3; naturalHeight = 2;
    onload: (() => void) | null = null; onerror: (() => void) | null = null;
    set src(value: string) { if (value) queueMicrotask(() => this.onload?.()); }
  });
});
afterEach(() => vi.unstubAllGlobals());
function choose(selected: File) { fireEvent.change(screen.getByLabelText('Select coworking image'), { target: { files: [selected] } }); }

test('reads File metadata, previews, replaces, clears and releases object URLs', async () => {
  const changed = vi.fn();
  const view = render(<ImagePicker onChange={changed} onValidating={vi.fn()}/>);
  choose(file());
  expect(await screen.findByRole('img', { name: /preview/ })).toHaveAttribute('src', 'blob:preview-1');
  expect(screen.getByText(/office.png.*32 bytes.*image\/png.*3 × 2/)).toBeVisible();
  expect(changed).toHaveBeenLastCalledWith(expect.any(File));
  choose(file('replacement.png'));
  expect(await screen.findByText(/replacement.png.*32 bytes/)).toBeVisible();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1');
  await userEvent.click(screen.getByRole('button', { name: 'Remove selection' }));
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(changed).toHaveBeenLastCalledWith(undefined);
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-2');
  choose(file()); await screen.findByRole('img'); view.unmount();
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-3');
});

test.each([
  [new File([], 'empty.png', { type: 'image/png' }), /non-empty/],
  [new File([new Uint8Array(8 * 1024 * 1024 + 1)], 'large.png', { type: 'image/png' }), /8 MiB/],
  [new File(['<svg/>'], 'fake.png', { type: 'image/png' }), /content must match/],
  [new File([png()], 'mime.png', { type: 'image/jpeg' }), /content must match/],
  [new File([png(100000, 2)], 'wide.png', { type: 'image/png' }), /8192/],
  [new File([png(4000, 4000)], 'pixels.png', { type: 'image/png' }), /12 megapixels/],
])('rejects invalid selection %s without allocating a preview', async (invalid, message) => {
  render(<ImagePicker onChange={vi.fn()} onValidating={vi.fn()}/>);
  choose(invalid as File);
  expect(await screen.findByRole('alert')).toHaveTextContent(message as RegExp);
  expect(URL.createObjectURL).not.toHaveBeenCalled();
});

test('corrupt decoding releases its URL and gives feedback', async () => {
  vi.stubGlobal('Image', class {
    onerror: (() => void) | null = null;
    set src(value: string) { if (value) queueMicrotask(() => this.onerror?.()); }
  });
  render(<ImagePicker onChange={vi.fn()} onValidating={vi.fn()}/>); choose(file());
  expect(await screen.findByRole('alert')).toHaveTextContent(/corrupt/);
  expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:preview-1');
});

test('a cleared pending read cannot publish a stale preview', async () => {
  render(<ImagePicker onChange={vi.fn()} onValidating={vi.fn()}/>);
  choose(file()); fireEvent.click(screen.getByRole('button', { name: 'Remove selection' }));
  await new Promise(resolve => setTimeout(resolve, 30));
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
  expect(URL.createObjectURL).not.toHaveBeenCalled();
});

test('inspects JPEG and WebP dimensions from content', () => {
  const jpeg = new Uint8Array([255,216,255,192,0,8,8,0,2,0,3,0]);
  expect(inspectImage(jpeg.buffer, 'image/jpeg')).toEqual({ width: 3, height: 2, mime: 'image/jpeg' });
  const webp = new Uint8Array(30);
  webp.set([82,73,70,70], 0); webp.set([87,69,66,80,86,80,56,88], 8); webp[24] = 2; webp[27] = 1;
  expect(inspectImage(webp.buffer, 'image/webp')).toEqual({ width: 3, height: 2, mime: 'image/webp' });
});

test('failed upload retains selection; retry attaches to the created space with CSRF and multipart', async () => {
  let fail = true;
  const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(async (url, options) => {
    if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'image-csrf', headerName: 'X-CSRF-TOKEN' }));
    if (String(url).endsWith('/image') && fail) return new Response(JSON.stringify({ detail: 'Storage unavailable' }), { status: 503 });
    return new Response(JSON.stringify({ id: 42, imageUrl: '/api/spaces/images/saved.png' }));
  });
  render(<MemoryRouter><SpaceForm/></MemoryRouter>);
  fireEvent.change(screen.getByLabelText('Space name'), { target: { value: 'New Hub' } });
  fireEvent.change(screen.getByLabelText('City'), { target: { value: 'Kyiv' } });
  fireEvent.change(screen.getByLabelText('Address'), { target: { value: 'Street' } });
  choose(file()); await screen.findByRole('img', { name: /preview/ });
  await userEvent.click(screen.getByRole('button', { name: 'Save space' }));
  expect(await screen.findByRole('alert')).toHaveTextContent(/Space #42 was saved, but the image was not attached/);
  expect(screen.getByRole('img', { name: /preview/ })).toBeVisible();
  fail = false;
  await userEvent.click(screen.getByRole('button', { name: 'Save space' }));
  await waitFor(() => expect(fetch.mock.calls.filter(([url]) => String(url).endsWith('/image'))).toHaveLength(2));
  expect(fetch.mock.calls.filter(([url, options]) => url === '/api/spaces' && options?.method === 'POST')).toHaveLength(1);
  const [, upload] = fetch.mock.calls.find(([url]) => String(url).endsWith('/image'))!;
  expect(upload?.body).toBeInstanceOf(FormData);
  expect((upload?.body as FormData).get('file')).toBeInstanceOf(File);
  expect(upload?.headers).toMatchObject({ 'X-CSRF-TOKEN': 'image-csrf' });
  expect(upload?.headers).not.toHaveProperty('Content-Type');
  await waitFor(() => expect(screen.queryByRole('alert')).not.toBeInTheDocument());
});

test('successful attachment refreshes the editor only after the upload completes', async () => {
  const changed = vi.fn();
  let complete: (() => void) | undefined;
  vi.spyOn(globalThis, 'fetch').mockImplementation(async (url) => {
    if (String(url).endsWith('/csrf')) return new Response(JSON.stringify({ token: 'csrf', headerName: 'X-CSRF-TOKEN' }));
    if (String(url).endsWith('/image')) await new Promise<void>(resolve => { complete = resolve; });
    return new Response(JSON.stringify({ id: 42, imageUrl: '/api/spaces/images/saved.png' }));
  });
  render(<MemoryRouter><SpaceForm space={{ id: 42, name: 'Hub', city: 'Kyiv', address: 'Street', description: null, contactEmail: null, imageUrl: null, rooms: [] }} changed={changed}/></MemoryRouter>);
  choose(file()); await screen.findByRole('img', { name: /preview/ });
  await userEvent.click(screen.getByRole('button', { name: 'Save space' }));
  await waitFor(() => expect(complete).toBeTypeOf('function'));
  expect(changed).not.toHaveBeenCalled();
  complete!();
  await waitFor(() => expect(changed).toHaveBeenCalledOnce());
});

test('public images reserve layout, fall back on error and recover when the URL changes', () => {
  const view = render(<SpaceImage url="/api/spaces/images/first.png" name="Hub"/>);
  expect(screen.getByRole('img')).toHaveClass('space-image');
  fireEvent.error(screen.getByRole('img'));
  expect(screen.getByText(/SpotPulse/)).toHaveClass('image-placeholder');
  view.rerender(<SpaceImage url="/api/spaces/images/second.png" name="Hub"/>);
  expect(screen.getByRole('img')).toHaveAttribute('src', '/api/spaces/images/second.png');
  view.rerender(<SpaceImage url="javascript:alert(1)" name="Hub"/>);
  expect(screen.queryByRole('img')).not.toBeInTheDocument();
});

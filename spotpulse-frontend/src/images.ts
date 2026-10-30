import { request, type Space } from './api';

export const MAX_IMAGE_BYTES = 8 * 1024 * 1024;
export const MAX_IMAGE_SIDE = 8192;
export const MAX_IMAGE_PIXELS = 12_000_000;
export interface ImageMetadata { width: number; height: number; mime: string }
export interface ImageSelection extends ImageMetadata { file: File; url: string }

// Read only a bounded Blob slice with FileReader. No full-file/base64 copies for a preview.
export function readImageHeader(file: File, signal: AbortSignal): Promise<ArrayBuffer> {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    const abort = () => { reader.abort(); reject(new DOMException('Cancelled', 'AbortError')); };
    if (signal.aborted) { abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    reader.onload = () => resolve(reader.result as ArrayBuffer);
    reader.onerror = () => reject(new Error('Unable to read this image. Please select it again.'));
    reader.onloadend = () => signal.removeEventListener('abort', abort);
    reader.readAsArrayBuffer(file.slice(0, 256 * 1024));
  });
}

export function inspectImage(bytes: ArrayBuffer, declaredType: string): ImageMetadata {
  const b = new Uint8Array(bytes); const v = new DataView(bytes);
  const text = (start: number, end: number) => String.fromCharCode(...b.slice(start, end));
  const u24 = (at: number) => b[at] + (b[at + 1] << 8) + (b[at + 2] << 16);
  let width = 0, height = 0, mime = '';
  if (b.length >= 24 && [137, 80, 78, 71, 13, 10, 26, 10].every((n, i) => b[i] === n) && text(12, 16) === 'IHDR') {
    mime = 'image/png'; width = v.getUint32(16); height = v.getUint32(20);
  } else if (b.length >= 4 && b[0] === 255 && b[1] === 216 && b[2] === 255) {
    mime = 'image/jpeg'; let at = 2;
    while (at + 4 <= b.length) {
      if (b[at++] !== 255) break;
      while (at < b.length && b[at] === 255) at++;
      const marker = b[at++];
      if (marker === 0xd9 || marker === 0xda) break;
      if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd8)) continue;
      if (at + 2 > b.length) break;
      const size = v.getUint16(at);
      if (size < 2 || at + size > b.length) break;
      if ([0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker) && size >= 7) {
        height = v.getUint16(at + 3); width = v.getUint16(at + 5); break;
      }
      at += size;
    }
  } else if (b.length >= 30 && text(0, 4) === 'RIFF' && text(8, 12) === 'WEBP') {
    mime = 'image/webp'; const chunk = text(12, 16);
    if (chunk === 'VP8X') {
      if (b[20] & 2) throw new Error('Animated images are not supported. Select a still image.');
      width = 1 + u24(24); height = 1 + u24(27);
    } else if (chunk === 'VP8L' && b[20] === 0x2f) {
      const dimensions = v.getUint32(21, true); width = (dimensions & 0x3fff) + 1; height = ((dimensions >>> 14) & 0x3fff) + 1;
    } else if (chunk === 'VP8 ' && b[23] === 0x9d && b[24] === 1 && b[25] === 0x2a) {
      width = v.getUint16(26, true) & 0x3fff; height = v.getUint16(28, true) & 0x3fff;
    }
  }
  if (!mime || (declaredType && declaredType !== mime)) throw new Error('Image content must match JPEG, PNG or WebP. SVG and other file types are not supported.');
  if (!width || !height) throw new Error('Unable to inspect image dimensions within the first 256 KiB. Select another image.');
  validateDimensions(width, height);
  return { width, height, mime };
}

export function validateDimensions(width: number, height: number) {
  if (width < 1 || height < 1 || width > MAX_IMAGE_SIDE || height > MAX_IMAGE_SIDE || width * height > MAX_IMAGE_PIXELS)
    throw new Error('Image must be at most 8192 pixels per side and 12 megapixels.');
}

export function decodePreview(url: string, signal: AbortSignal): Promise<{ width: number; height: number }> {
  return new Promise((resolve, reject) => {
    const image = new Image();
    const cleanup = () => { image.onload = null; image.onerror = null; signal.removeEventListener('abort', abort); };
    const abort = () => { cleanup(); image.src = ''; reject(new DOMException('Cancelled', 'AbortError')); };
    if (signal.aborted) { abort(); return; }
    signal.addEventListener('abort', abort, { once: true });
    image.onload = () => { cleanup(); resolve({ width: image.naturalWidth, height: image.naturalHeight }); };
    image.onerror = () => { cleanup(); reject(new Error('This image is corrupt or cannot be decoded by your browser.')); };
    image.src = url;
  });
}

/** UI-facing storage boundary. C19 can retain this multipart contract with a different server adapter. */
export async function attachSpaceImage(spaceId: number, file: File): Promise<Space> {
  const form = new FormData();
  // The picker supplies a File with the signature-validated MIME type.
  form.append('file', file);
  return request<Space>(`/api/spaces/${spaceId}/image`, { method: 'POST', body: form });
}

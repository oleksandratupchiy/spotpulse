import { ImagePlus, X } from 'lucide-react';
import { useEffect, useRef, useState } from 'react';
import { decodePreview, inspectImage, readImageHeader, MAX_IMAGE_BYTES, type ImageMetadata } from './images';

export function ImagePicker({ onChange, onValidating }: { onChange: (file: File | undefined) => void; onValidating: (busy: boolean) => void }) {
  const [selection, setSelection] = useState<{ file: File; url: string; metadata: ImageMetadata }>();
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const generation = useRef(0), activeUrl = useRef<string | undefined>(undefined);
  const controller = useRef<AbortController | undefined>(undefined);
  const input = useRef<HTMLInputElement>(null);
  const callbacks = useRef({ onChange, onValidating });
  callbacks.current = { onChange, onValidating };
  function release() {
    controller.current?.abort(); controller.current = undefined;
    if (activeUrl.current) URL.revokeObjectURL(activeUrl.current);
    activeUrl.current = undefined;
  }
  useEffect(() => () => { generation.current++; release(); }, []);
  async function select(file?: File) {
    const current = ++generation.current;
    release(); setSelection(undefined); setError(''); onChange(undefined);
    setBusy(!!file); onValidating(!!file);
    if (!file) return;
    let url: string | undefined;
    try {
      if (!file.size) throw new Error('Select a non-empty image.');
      if (file.size > MAX_IMAGE_BYTES) throw new Error('Image must be at most 8 MiB.');
      const reading = new AbortController(); controller.current = reading;
      const metadata = inspectImage(await readImageHeader(file, reading.signal), file.type);
      if (current !== generation.current) return;
      url = URL.createObjectURL(file); activeUrl.current = url;
      const dimensions = await decodePreview(url, reading.signal);
      const matches = (dimensions.width === metadata.width && dimensions.height === metadata.height)
        || (metadata.mime === 'image/jpeg' && dimensions.width === metadata.height && dimensions.height === metadata.width);
      if (!matches) throw new Error('Image dimensions do not match its content.');
      if (current !== generation.current) return;
      setSelection({ file, url, metadata }); onChange(new File([file], file.name, { type: metadata.mime }));
    } catch (failure) {
      if (current === generation.current) { release(); setError((failure as Error).message); }
    } finally {
      if (current === generation.current) { setBusy(false); callbacks.current.onValidating(false); }
    }
  }
  return <div className="col-12"><div className="image-upload-panel">
    <label htmlFor="space-image-file" className="form-label"><ImagePlus aria-hidden="true" size={18} className="me-2"/>Select coworking image</label>
    <input ref={input} id="space-image-file" type="file" accept="image/jpeg,image/png,image/webp" className="form-control" aria-describedby="image-file-help" aria-invalid={!!error} onChange={e => { void select(e.target.files?.[0]); e.target.value = ''; }}/>
    <p id="image-file-help" className="small">JPEG, PNG or WebP; up to 8 MiB, 8192 pixels per side and 12 megapixels. Selected images attach when you save the space. A local selection replaces the Image URL.</p>
    {busy && <p role="status">Inspecting image…</p>}
    {error && <p role="alert" className="text-danger">{error}</p>}
    {selection && <><img src={selection.url} alt="Selected coworking image preview" className="space-image"/><p role="status">{selection.file.name} · {selection.file.size.toLocaleString()} bytes · {selection.metadata.mime} · {selection.metadata.width} × {selection.metadata.height} pixels</p><p className="small">Preview only until Save space succeeds.</p></>}
    {(selection || busy || error) && <button type="button" className="sp-button sp-button--ghost" onClick={() => { void select(); input.current?.focus(); }}><X aria-hidden="true"/> Remove selection</button>}
  </div></div>;
}

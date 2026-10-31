import { useEffect, useRef, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { type Map as LeafletMap } from 'leaflet';
import { type MapData } from './api';
import { Failure, Loading, useResource } from './components';
import 'leaflet/dist/leaflet.css';

export default function SpacesMap({ filter }: { filter: string }) {
  const [refresh, setRefresh] = useState(0);
  const { data, error } = useResource<MapData>('/api/spaces/map?limit=200' + filter, refresh);
  return <section className="map-panel mb-4" aria-label="Coworking map">
    <h2 className="h4">Explore on the map</h2>
    {error ? <Failure error={error} retry={() => setRefresh(n => n + 1)}/> : !data ? <Loading/> : <>
      <p role="status">{data.total} matching spaces. {data.items.length} markers shown; {data.missingCoordinates} without coordinates.</p>
      {data.truncated && <p className="alert alert-info">Showing the first {data.limit} of {data.located} located spaces. Narrow your search to see the others. The list below includes every match across its pages.</p>}
      {!data.total ? <p>No spaces match these filters.</p> : !data.items.length ? <p>Map locations are not available for these spaces yet. Explore them in the list below.</p> : <MapCanvas spaces={data.items}/>}
      {data.missingCoordinates > 0 && <p className="small text-secondary">Spaces without confirmed locations remain available in the list below.</p>}
    </>}
  </section>;
}

export function MapCanvas({ spaces }: { spaces: MapData['items'] }) {
  const element = useRef<HTMLDivElement>(null);
  const navigate = useNavigate();
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => {
    let cancelled = false;
    let map: LeafletMap | undefined;
    setError(''); setLoading(true);
    import('leaflet').then(L => {
      if (cancelled || !element.current) return;
      map = L.map(element.current, { scrollWheelZoom: false });
      L.tileLayer(import.meta.env.VITE_MAP_TILE_URL || 'https://tile.openstreetmap.org/{z}/{x}/{y}.png', {
        maxZoom: 19,
        attribution: import.meta.env.VITE_MAP_ATTRIBUTION || '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors',
      }).on('tileerror', () => { if (!cancelled) setError('Map tiles could not be loaded. Space links remain available below.'); })
        .addTo(map);
      const markers = spaces.map((space, index) => {
        const popup = document.createElement('div');
        const title = document.createElement('strong'); title.textContent = space.name;
        const address = document.createElement('p'); address.textContent = `${space.city} · ${space.address}`;
        const link = document.createElement('a'); link.href = `/spaces/${space.id}`; link.textContent = 'View space';
        link.addEventListener('click', event => {
          if (!event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey && event.button === 0) {
            event.preventDefault(); navigate(`/spaces/${space.id}`);
          }
        });
        popup.append(title, address, link);
        return L.marker([space.latitude, space.longitude], {
          keyboard: true, title: `${space.name}, ${space.city}`, alt: `${space.name}, ${space.city}`,
          icon: L.divIcon({ className: 'space-marker', html: `<span>${index + 1}</span>`, iconSize: [32, 32], iconAnchor: [16, 32] }),
          riseOnHover: true,
        }).bindPopup(popup).addTo(map!);
      });
      map.fitBounds(L.featureGroup(markers).getBounds(), { padding: [35, 35], maxZoom: 14 });
      setLoading(false);
    }).catch(() => { if (!cancelled) { setError('The map could not be loaded. Please try again or use the space list.'); setLoading(false); } });
    return () => { cancelled = true; map?.remove(); };
  }, [spaces, navigate, attempt]);
  return <>
    {loading && <Loading/>}
    {error && <div role="alert" className="alert alert-warning">{error} <button className="sp-button sp-button--secondary" onClick={() => setAttempt(n => n + 1)}>Retry map</button></div>}
    <div ref={element} className="spaces-map" role="region" aria-label="Interactive coworking map. Use arrow keys to pan and plus or minus to zoom."/>
    <p className="small mt-2">Markers are numbered. Tab to a marker and press Enter to open its details. Zoom to separate nearby markers. <a href="https://www.openstreetmap.org/fixthemap" target="_blank" rel="noreferrer">Report a map issue</a></p>
  </>;
}

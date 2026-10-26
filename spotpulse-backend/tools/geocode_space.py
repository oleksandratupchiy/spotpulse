"""Operator-only, one-space geocoding. No public proxy or automatic persistence.

Nominatim policy: https://operations.osmfoundation.org/policies/nominatim/
Run on one designated machine; do not schedule or use for bulk imports.
"""
import argparse
from contextlib import closing
import json
import math
import os
from pathlib import Path
import sqlite3
import tempfile
import time
from urllib.parse import urlencode, urlparse
from urllib.request import Request, urlopen


def request_json(url, *, headers=None, method='GET', body=None):
    request = Request(url, headers=headers or {}, method=method,
                      data=None if body is None else json.dumps(body).encode())
    with urlopen(request, timeout=15) as response:
        raw = response.read(1_000_001)
        if len(raw) > 1_000_000:
            raise ValueError('Provider response is too large')
        return json.loads(raw)


def candidates(query, provider, user_agent, cache_path):
    if urlparse(provider).scheme != 'https':
        raise ValueError('Geocoder URL must use HTTPS')
    if not user_agent or len(user_agent) > 255:
        raise ValueError('Set SPOTPULSE_GEOCODING_USER_AGENT to SpotPulse/1.0 plus your real contact email or URL')
    if not query.strip() or len(query) > 520:
        raise ValueError('Address query must be 1–520 characters')
    # Shared SQLite write lock serializes requests, including across local processes.
    # Cache and throttle are shared by all spaces on the designated operator machine.
    with closing(sqlite3.connect(cache_path, timeout=30)) as db:
        db.execute('create table if not exists cache (key text primary key, value text)')
        db.execute('create table if not exists throttle (id integer primary key, requested real)')
        db.commit()
        db.execute('begin immediate')
        key = provider + '?' + urlencode({'q': query, 'format': 'jsonv2', 'limit': 5, 'addressdetails': 1})
        cached = db.execute('select value from cache where key=?', (key,)).fetchone()
        if cached:
            return json.loads(cached[0])
        last = db.execute('select requested from throttle where id=1').fetchone()
        if last:
            time.sleep(max(0, 1.1 - (time.time() - last[0])))
        # Persist throttling even when the upstream request fails.
        try:
            result = request_json(key, headers={'User-Agent': user_agent, 'Accept': 'application/json'})
            if not isinstance(result, list) or len(result) > 5:
                raise ValueError('Unexpected geocoding response')
            for item in result:
                lat, lon = float(item['lat']), float(item['lon'])
                if not math.isfinite(lat) or not math.isfinite(lon) or not -90 <= lat <= 90 or not -180 <= lon <= 180:
                    raise ValueError('Provider returned invalid coordinates')
            db.execute('insert or replace into cache values (?, ?)', (key, json.dumps(result)))
        finally:
            db.execute('insert or replace into throttle values (1, ?)', (time.time(),))
            db.commit()
        return result


def geocode(space_id, api, provider, user_agent, cache_path, choose=input):
    if space_id < 1:
        raise ValueError('Space ID must be positive')
    endpoint = f'{api.rstrip("/")}/spaces/{space_id}'
    headers = {'Accept': 'application/json', 'Content-Type': 'application/json'}
    session_cookie = os.getenv('SPOTPULSE_SESSION_COOKIE')
    if session_cookie:
        headers['Cookie'] = session_cookie
    token = os.getenv('SPOTPULSE_API_TOKEN')
    if token:
        headers['Authorization'] = f'Bearer {token}'
    space = request_json(endpoint, headers=headers)
    query = f'{space["address"]}, {space["city"]}'
    print(f'{space["name"]}: {query}')
    print('Geocoding attribution: © OpenStreetMap contributors, ODbL. https://www.openstreetmap.org/copyright')
    results = candidates(query, provider, user_agent, cache_path)
    if not results:
        print('No results. No coordinates saved. Verify the address or use surveyed coordinates.')
        return False
    for index, item in enumerate(results, 1):
        print(f'{index}: {item["display_name"]} ({item["lat"]}, {item["lon"]}); type={item.get("type", "unknown")}')
    print('Results may be approximate or ambiguous. Check the full address and location independently.')
    selected = choose('Candidate number to explicitly confirm and save (Enter cancels): ').strip()
    if not selected:
        return False
    if not selected.isdigit() or not 1 <= int(selected) <= len(results):
        raise ValueError('Invalid candidate; no coordinates saved')
    # Re-read before PUT so unrelated recent edits are preserved.
    current = request_json(endpoint, headers=headers)
    if any(current.get(field) != space.get(field) for field in ('address', 'city', 'latitude', 'longitude')):
        raise ValueError('Address or coordinates changed during review. Restart before saving.')
    candidate = results[int(selected) - 1]
    body = {field: current.get(field) for field in ('name', 'description', 'address', 'city', 'imageUrl', 'contactEmail')}
    body.update(latitude=float(candidate['lat']), longitude=float(candidate['lon']))
    csrf = request_json(f'{api.rstrip("/")}/auth/csrf', headers=headers)
    if not isinstance(csrf.get('token'), str) or not isinstance(csrf.get('headerName'), str):
        raise ValueError('Invalid CSRF response; no coordinates saved')
    headers = {**headers, csrf['headerName']: csrf['token']}
    request_json(endpoint, headers=headers, method='PUT', body=body)
    print('Confirmed coordinates saved.')
    return True


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--space-id', required=True, type=int)
    parser.add_argument('--api', default='http://localhost:8080/api')
    args = parser.parse_args()
    try:
        geocode(args.space_id, args.api,
                os.getenv('SPOTPULSE_GEOCODING_URL', 'https://nominatim.openstreetmap.org/search'),
                os.getenv('SPOTPULSE_GEOCODING_USER_AGENT', ''),
                Path(os.getenv('SPOTPULSE_GEOCODING_CACHE', str(Path(tempfile.gettempdir()) / 'spotpulse-geocoding.sqlite3'))))
    except (ValueError, KeyError, OSError, sqlite3.Error) as error:
        parser.exit(1, f'Geocoding failed; verify saved state before retrying: {error}\n')


if __name__ == '__main__':
    main()

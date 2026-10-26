# C8 — interactive coworking discovery

Security update: public discovery/maps remain public. Space coordinate writes now
require an authorized OWNER/ADMIN session and CSRF. The operator tool accepts the
operator's `SPOTPULSE_SESSION_COOKIE` and obtains CSRF before saving; no token is sent
to the geocoder. See [C16/QA3](C16-QA3-oauth2-rbac.md) for the session workflow. The
authenticated space editor also accepts independently confirmed coordinates.

## Provider and installation

The existing React `/spaces` discovery page offers **Show map / Hide map** alongside
the paginated cards. Leaflet 1.9.4 is bundled locally and loaded only when requested.
OpenStreetMap raster tiles provide the third-party map API without an API key.
Numbered, keyboard-focusable markers use coordinates returned by SpotPulse, with
name, city, address and React `/spaces/{id}` navigation in each popup. The map fits
the matching locations, supports pan/zoom and touch, and never requests geolocation.
No sample locations are added to application data.

Run `npm ci` in `spotpulse-frontend` to install the lockfile dependencies.
Java remains 25 / Spring Boot 4.1.1; no new Java dependencies are required.
Python 3.12+ (standard library only) is used for the optional operator geocoding tool.

OSM services have limited capacity and no availability guarantee. Read the
[tile policy](https://operations.osmfoundation.org/policies/tiles/) and
[Nominatim usage policy](https://operations.osmfoundation.org/policies/nominatim/)
before making live requests. Public Nominatim permits at most one request per second
for the entire application, requires application identification, attribution and
caching, forbids autocomplete, and discourages bulk/periodic geocoding. Use this tool
manually on one designated machine, with one shared cache. Do not schedule it or
distribute it across machines. Choose a hosted or self-hosted provider for larger use.

The browser requests only visible tiles, with normal browser caching and Referer;
there is no prefetch or offline tile download. Visible attribution links to OSM.
Do not deploy with a Referrer-Policy that suppresses the tile request Referer.

## Configuration

No secrets or paid keys are required by the defaults.

| Variable | Purpose / default |
| --- | --- |
| `VITE_MAP_TILE_URL` | Optional replacement tile URL template. Default `https://tile.openstreetmap.org/{z}/{x}/{y}.png`. |
| `VITE_MAP_ATTRIBUTION` | Attribution HTML for your tile provider; default OSM contributors link. Trusted deployment configuration only. |
| `VITE_API_BASE_URL` | Existing SPA API base; defaults to `/api`. |
| `API_PROXY_TARGET` | Existing Vite development proxy; defaults to `http://localhost:8080`. |
| `SPOTPULSE_GEOCODING_URL` | Nominatim-compatible HTTPS search endpoint; default `https://nominatim.openstreetmap.org/search`. |
| `SPOTPULSE_GEOCODING_USER_AGENT` | Required for geocoding: identify SpotPulse and include your real contact URL/email. |
| `SPOTPULSE_GEOCODING_CACHE` | Shared SQLite file path; default `spotpulse-geocoding.sqlite3` in the OS temporary directory. Use a durable path on the operator machine. |
| `SPOTPULSE_API_TOKEN` | Optional Bearer token for a deployment that protects the existing space update API. Never sent to the geocoder. |

Vite variables are build-time settings: rebuild after changing tiles or attribution.
Geocoding variables are read at invocation, so the provider can be replaced without
changing code. Alternative geocoders must support the Nominatim search format; for
a provider with a different contract, adapt the small `candidates` function.
Geocoding queries disclose only the public space address and city to the provider.

## Coordinates and compatibility

`Space.latitude` and `Space.longitude` are nullable `Double` fields, persisted by JPA
as ordinary floating-point columns supported by H2 and PostgreSQL. No PostGIS is
needed. The existing `ddl-auto=update` adds the columns without removing old records.
For deployments using managed SQL migrations instead of Hibernate schema updates:

```sql
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS latitude DOUBLE PRECISION;
ALTER TABLE spaces ADD COLUMN IF NOT EXISTS longitude DOUBLE PRECISION;
```

Keep your deployment's PostgreSQL datasource and dialect settings. This feature does
not change Docker or the existing H2 development datasource. PostgreSQL execution
requires a running PostgreSQL instance and was not verified in this task.

Space create/update DTOs accept optional coordinates; latitude must be finite and
within -90..90, longitude finite and within -180..180, and both must be supplied
together or both absent. Existing records and requests without coordinates remain
valid. Ordinary space detail/list responses include the additional nullable fields.
Existing Java DTO constructors remain available.

On update, omitted/null coordinates preserve a confirmed location when address and
city are unchanged, including saves from the existing Thymeleaf form. Changing the
address or city without supplying a new coordinate pair clears the previous pair to
avoid stale markers. Both null on an unchanged address do not explicitly clear the
location; use a data maintenance operation if that exceptional action is necessary.
Bookings, routes, B1 response shapes, F2 autocomplete and C6/C7 pages are unchanged.

## Explicit geocoding workflow

There is no public geocoding endpoint, browser geocoding, background backfill or
lookup on page load. An operator invokes one space at a time against the existing
REST API. From `spotpulse-backend` in PowerShell:

```powershell
$env:SPOTPULSE_GEOCODING_USER_AGENT = 'SpotPulse/1.0 (contact: YOUR_REAL_EMAIL_OR_URL)'
$env:SPOTPULSE_GEOCODING_CACHE = Join-Path $env:LOCALAPPDATA 'spotpulse-geocoding.sqlite3'
python tools/geocode_space.py --space-id 1 --api http://localhost:8080/api
```

Replace `1` with the ID of an actual space. The tool uses its persisted address/city,
requests at most five candidates, prints full result labels and attribution, then
requires a candidate number before PUT. Review every result independently, including
single results: a result may be a street/city centroid rather than the building.
Enter cancels. No results, unavailable providers, invalid coordinates and invalid
selections cause no automatic coordinate writes. Correct unclear addresses instead
of guessing or accepting approximate results. Manually verified coordinates can
also be supplied in the existing POST/PUT API with all normal required fields.

The SQLite cache stores successful queries including empty results. A shared write
lock serializes local processes; uncached requests wait at least 1.1 seconds after
the previous completed attempt. Provider requests time out after 15 seconds and
responses are capped at 1 MB. Failed provider responses are not cached or retried
automatically. Avoid repeatedly rerunning failures or rate-limit responses; wait and
follow provider guidance. Keep the same cache path for all invocations.

Before persisting, the tool re-reads the space and refuses if its address or location
changed during review. It preserves the current other fields. The existing PUT API
has no ETag/version, so concurrent edits between that final GET and PUT can still
race; run this maintenance workflow when the space is not being edited.

## Filtering and B1 pagination

The map uses `GET /api/spaces/map?limit=200`, optionally with `city` or `search`.
It returns only public id/name/city/address/coordinate data, excluding rooms,
contacts, bookings and users. A database projection avoids loading room collections.
The service restricts limit to 1..200 and filters to at most 255 characters with no
control characters. Results are ordered by unique ID for stable selection.

The response contains:

- `items`: up to `limit` matching spaces with coordinates;
- `total`: all matches, including spaces without coordinates;
- `located`: all matches with coordinates;
- `missingCoordinates`: `total - located`;
- `limit` and `truncated`: explicit cap and whether more located matches exist.

Map data is independent of the B1 list's current six-item page. The UI reports
counts and tells users when to narrow filters because of the 200-space cap. Unlocated
spaces stay available in the paginated cards. No coordinates means a meaningful
message, with no fabricated marker or implied user position.

The existing accessible Search by selector supports exact city or a case-insensitive
name/city/address query. Both list and map filters update immediately as the input
or mode changes. Pagination resets and in-flight requests are aborted; stale data is
hidden. The Search button still trims and submits the query. As in the established
REST contract, a nonblank search takes precedence over city if both are supplied.
SQL LIKE wildcard behavior is retained for compatibility with list search.

Map initialization/API/tile failures show a retry control; card navigation remains
available. Tile failures do not hide markers or popup links. The initial map extent
fits the supplied spaces, with a maximum initial zoom of 14. Scroll-wheel zoom is
disabled to avoid trapping page scrolling; map controls, keyboard and touch work.

## Demonstration

1. Start the backend from `spotpulse-backend`: `.\mvnw.cmd spring-boot:run`.
2. In another terminal, run `npm ci` then `npm run dev` in `spotpulse-frontend`.
3. Use real coworking records with accurate public addresses (create through the
   existing API or Thymeleaf form if needed). Geocode and explicitly confirm at
   least two records, ideally at different locations and cities. Never copy test
   fixture coordinates into real application data.
4. Open `http://localhost:5173/spaces` and select **Show map**. Confirm multiple
   markers, zoom/pan, popup fields and detail links. Use the exact-city mode and a
   name search to demonstrate that markers and cards change together.
5. With more than six located matches, change list pages: map markers should still
   represent all located matches up to the declared cap. Test a space without
   coordinates and an unmatched query. Inspect `/api/spaces/map?limit=1` to see
   explicit truncation metadata.
6. Test narrow mobile widths and keyboard Tab/Enter. Disable the network to inspect
   API/tile error behavior. No user-location permission should appear.

The development H2 database is in memory: existing application behavior loses data
on server restart. Keep the backend running between entering real coordinates and
the presentation, or use your existing persistent database configuration.

## Verification commands

From `spotpulse-backend`:

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd clean test
node --test src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s tools -p 'test_*.py'
```

The sandbox's Maven wrapper could not resolve its home. The installed Maven and
existing cache were used instead (these are the exact local commands):

```powershell
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o -DskipTests compile
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o clean test
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o -Dtest=SpaceMapTests test
```

From `spotpulse-frontend`:

```powershell
npm run build
npm test -- --testTimeout=20000 --maxWorkers=1
```

The build runs `tsc --noEmit` before Vite production bundling. The frontend suite
mocks Leaflet and HTTP, testing dynamic markers, popup fields and SPA navigation,
immediate filters, counts/limits, missing coordinates, empty/error/retry states and
marker replacement. Slow Windows execution required longer test timeouts and one
worker; the initial default-timeout run had two timing failures.

Backend `SpaceMapTests` exercises full controller/service/repository HTTP behavior
with MockMvc and H2: ranges/pairs/non-finite values, persistence, serialization,
multiple markers, matching filters, missing coordinates, bounds and legacy API
compatibility. Existing pagination, booking/concurrency, autocomplete, landing and
SEO tests remain in the full suite. Geocoding tests mock provider/API calls; no test
requires live external services.

Verification results on 2026-10-26:

| Check | Result |
| --- | --- |
| Backend compilation, including clean rebuild | Passed |
| Full backend suite | 90 passed; 0 failures/errors/skipped |
| Final map HTTP/integration rerun after whitespace-filter alignment | 5 passed |
| Frontend TypeScript and production build | Passed |
| Final full frontend suite | 28 passed across 3 files |
| Existing F2 JavaScript suite | 8 passed |
| Mocked operator geocoding suite | 5 passed |
| Git whitespace check | Passed |

The full backend suite preceded the final whitespace-only city-filter alignment;
the focused map rerun compiled and verified that final adjustment. The final frontend
run includes the map chunk error boundary. Live tiles, live geocoding and a running
PostgreSQL database were not tested. HTTP integration was exercised through MockMvc
with real services/repositories and H2, without a separate live HTTP server.

## Changed files

Backend paths below are relative to `spotpulse-backend`:

- Modified `src/main/java/com/spotpulse/model/Space.java`.
- Modified `src/main/java/com/spotpulse/dto/SpaceCreateDto.java` and `SpaceDto.java`.
- Modified `src/main/java/com/spotpulse/mapper/EntityMapper.java`.
- Modified `src/main/java/com/spotpulse/controller/SpaceController.java`.
- Modified `src/main/java/com/spotpulse/service/SpaceService.java`.
- Modified `src/main/java/com/spotpulse/repository/SpaceRepository.java`.
- Created `src/main/java/com/spotpulse/dto/SpaceMapDto.java` and `SpaceMapResponse.java`.
- Created `src/test/java/com/spotpulse/SpaceMapTests.java`.
- Created `tools/geocode_space.py`, `tools/test_geocode_space.py`, `tools/.gitignore`.
- Created `docs/C8-maps.md`.

Frontend paths below are relative to `spotpulse-frontend`:

- Modified `package.json`, `package-lock.json`.
- Modified `src/App.tsx`, `src/api.ts`, `src/components.tsx`, `src/style.css`.
- Created `src/SpacesMap.tsx` and `src/test/SpacesMap.test.tsx`.

Pre-existing `bin` working-tree changes and the untracked backend `README.md` were
left untouched. No commit, push, data deletion or deployment changes were made.

## Limitations

- Public OSM tiles are best effort; use a suitable provider for higher traffic.
- The cap is 200 located spaces. This is a bounded discovery view, without viewport
  queries or clustering. Very close/coincident coordinates may overlap; the complete
  list remains accessible, and keyboard focus can reach individual markers.
- City filtering retains exact spelling/case behavior from the existing API. City
  and free-text are alternative modes, preserving the established search contract.
- Geocoding is an operator CLI workflow, without an administrative geocoding screen.
  Existing API authorization behavior is preserved; no open proxy was introduced.
- PostgreSQL uses portable JPA types but was not run against a live database here.
- Live tiles and geocoding have not been visually verified. Automated provider
  behavior is mocked. A presentation requires real confirmed locations and a browser
  check of live map availability and responsive layout before class.

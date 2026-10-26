# B9 — server-side API caching

Security update: [C16/QA3](C16-QA3-oauth2-rbac.md) adds role/ownership authorization
before cache lookup. Analytics keys now also include `platform` or `owner:<id>`.
Public caches and after-commit invalidation remain. Auth/session/role decisions are
not cached; authenticated HTTP requests refresh roles from the database independently
of cached aggregate reads. The original measurements below remain historical
service-level measurements, and protected endpoints now require a valid session.

## Provider and configuration

SpotPulse uses Spring Cache annotations with Caffeine 3.2.4 (version managed by
Spring Boot 4.1.1). No external cache service or new infrastructure is required.
The existing REST/Thymeleaf controllers still call the same service methods and
return the same DTO/JSON formats.

| Property | Environment override | Default |
| --- | --- | --- |
| `spotpulse.cache.enabled` | `SPOTPULSE_CACHE_ENABLED` | `true` |
| `spotpulse.cache.ttl` | `SPOTPULSE_CACHE_TTL` | `120s` |
| `spotpulse.cache.maximum-size` | `SPOTPULSE_CACHE_MAXIMUM_SIZE` | `500` entries **per cache** |

TTL uses expiration after write, not after access: frequently accessed values still
expire. A positive duration up to one day is accepted. Maximum size must be 1–100000.
Invalid configuration fails startup. Restart to change these settings. For example,
in PowerShell before starting the backend:

```powershell
$env:SPOTPULSE_CACHE_TTL = '60s'
$env:SPOTPULSE_CACHE_MAXIMUM_SIZE = '250'
.\mvnw.cmd spring-boot:run
```

Set `SPOTPULSE_CACHE_ENABLED=false` to bypass caching globally. Caffeine statistics
are enabled internally for tests/diagnostics; no public statistics or cache-clearing
endpoint was added. There are five fixed cache regions, without dynamically created
regions. Caffeine eviction/expiration maintenance may occur asynchronously; expired
entries cannot be served even before physical cleanup.

## Cached operations and keys

| Region | Service operation | Parameters included in key |
| --- | --- | --- |
| `spaceDetails` | `SpaceService.getById` | Space ID |
| `roomDetails` | `RoomService.getById` | Room ID |
| `autocomplete` | `SpaceService.autocomplete` | Raw query, including null/blank/case |
| `spaceMap` | `SpaceService.mapData` | City, search, result limit |
| `bookingAnalytics` | `BookingAnalyticsService.getBookings` | Inclusive from/to dates |

Every key also includes its region's current invalidation generation. Parameter
lists are structured immutable values, not concatenated strings, so delimiters and
null values cannot cause collisions. Raw filter values are deliberately retained:
different raw queries may occupy separate entries even if existing normalization or
search precedence produces equal results. Invalid arguments still raise the existing
errors and are not stored. Not-found exceptions are not negatively cached. Valid
empty suggestions/map results/analytics are cached, which also avoids repeatedly
querying the database for common empty searches.

The existing B1 space/room listings remain uncached. Their exact offset, page size,
filters, stable sorting, totals and request-specific next links continue to be
computed normally; there is no partial pagination key or cached link from another
request. Sitemap lists/counts, individual bookings, user reads and all write responses
are also uncached. Booking availability and row locks always use the database.

Only detached public response values are stored. The Caffeine cache adapter copies
Space/Room DTOs on write and read, including each nested room. Record-based map and
analytics DTOs use immutable list snapshots; autocomplete uses immutable records and
lists. A caller cannot mutate a cached object through a returned DTO. JPA entities,
lazy collections, user DTOs, booking DTOs and authorization decisions are excluded.
Space contact information remains the already-public field in the existing API;
no private user information is added or cached.

## Transactions and invalidation

Cache advice explicitly surrounds transaction advice. For a normal HTTP cache miss,
the read transaction completes before Spring Cache stores the detached result.
Errors and failed read transactions do not populate the cache.

If a service read is invoked inside an **already active transaction**, caching is
bypassed completely, including inside read-only transactions. That transaction may
have uncommitted changes or an older repeatable-read snapshot. It must read its own
database view, and must not publish that view to other requests. Normal controller
calls can use caching because they reach the cache advice before starting their
service transaction. Existing transactional tests consequently retain their original
rollback/snapshot behavior.

Successful write methods publish a small dependency event **inside** their write
transaction. A synchronous `@TransactionalEventListener` handles it only after
successful commit, before the ordinary service call returns:

| Committed mutation | Invalidated regions |
| --- | --- |
| Space create/update/delete | All five regions |
| Room create/update/delete or move to another space | Space details, room details, analytics |
| Booking create/update/cancel/delete or reschedule | Analytics |
| User deletion, which cascades to bookings | Analytics |

Room changes invalidate nested rooms inside space details. Space changes invalidate
room/analytics space labels as well as map markers and autocomplete. Cascading
space/room deletions can remove bookings, so they invalidate analytics too. Thymeleaf
landing details and SEO rendering call the same invalidated space detail service.
User create/update does not change cached public aggregates and needs no eviction.

Invalidation is deliberately coarse within each affected region. This trades some
hit rate after writes for clear dependency rules. Failed validation, booking
conflicts, explicit rollbacks and commit-time database failures do not advance the
generation or evict valid entries.

Before clearing a region, its generation increases. A read already in progress uses
the generation captured when its key was first computed. If it finishes after the
clear, it can only populate an old-generation key, which later requests cannot
address. This prevents stale refill races that simple clear-after-write would leave.
Unreachable old entries remain bounded and expire normally. Requests already in
flight around a commit may complete with their preceding snapshot; synchronous
invalidation is complete when the write service returns.

## Reproducible performance measurement

`CachePerformanceTests` creates an isolated in-memory H2 database with **1200 test
bookings over 30 days**, one room and one user. It does not connect to application or
production data. Ten uncached calls warm up the JIT, query plans and database buffers.
The harness then measures sequential calls to the real aggregate service:

1. **Uncached:** 50 calls inside explicit read-only repeatable-read transactions,
   invoking the production cache-bypass rule. Same service logic, SQL and isolation;
   the transaction starts in the harness rather than inside the service advice.
2. **Cold:** one ordinary service call immediately after clearing its cache.
3. **Warm:** 200 ordinary repeated calls for the same range.
4. **After invalidation:** create one real test booking through `BookingService`,
   then measure the next aggregate read and verify that the total changed to 1201.

Each phase records mean, median, p95 and Hibernate prepared-statement counts.
Caffeine hit/miss totals are included. The benchmark asserts query counts and data
correctness, **not latency thresholds**. It writes current measurements to
`target/B9-cache-benchmark.json` and prints `B9_CACHE_BENCHMARK` in the test output.
The benchmark is included in the full backend suite, or can run independently:

```powershell
.\mvnw.cmd -Dtest=CachePerformanceTests test
Get-Content target/B9-cache-benchmark.json
```

These are service-boundary measurements on Java 25 / Windows / H2, excluding HTTP,
network and JSON serialization. Uncached database buffers are warmed, while cold
cache measurements may include first-use Spring expression/DTO-copy overhead. One
cold/post-invalidation sample is a demonstration, not a robust latency distribution.
GC, JIT, scheduling and other machine workloads affect results. No end-to-end API
latency or throughput gain is inferred from this microbenchmark. Warm reads still
have key generation, lookup and safe DTO-copy costs.

Actual measurements from the clean full-suite run on **2026-10-26, 16:20–16:21 +02:00**:

| Phase | Requests | Mean ms/read | Median ms/read | p95 ms/read | Total SQL statements |
| --- | ---: | ---: | ---: | ---: | ---: |
| Uncached | 50 | 24.141 | 18.184 | 38.426 | 100 |
| Cold cache | 1 | 52.448 | 52.448 | 52.448 | 2 |
| Warm cache | 200 | 1.605 | 0.631 | 4.032 | 0 |
| After committed booking invalidation | 1 | 24.846 | 24.846 | 24.846 | 2 |

The region recorded **202 hits and 2 misses**, including two extra correctness
reads. The cold request was slower than the warmed uncached baseline: it includes
first cache key/expression/snapshot setup. The measurable repeated-read benefit is
the reported warm-call latency and zero SQL; no performance gain is claimed for a
cold request. The post-invalidation total was 1201, proving the changed result was
loaded rather than reusing the earlier 1200-booking aggregate.

## Verification and local demonstration

From `spotpulse-backend`:

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd clean test
node --test src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s tools -p 'test_*.py'
```

The exact installed Maven/cache paths used in this workspace are:

```powershell
$mvn = 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -DskipTests compile
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o '-Dtest=ReadCacheTests,CachePerformanceTests' test
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o clean test
```

Initial dependency resolution requires network access. The sandbox denied Maven
cache writes and Vite's parent-directory access; those verification commands were
rerun with the required access.

From `spotpulse-frontend`:

```powershell
npm run build
npm test -- --testTimeout=20000 --maxWorkers=1
```

The build includes TypeScript checking. The complete frontend suite includes booking,
maps and Chart.js dashboard tests. F2 and geocoding tests continue mocking external
behavior. Backend tests use real service/repository transactions and isolated H2:

- identical reads hit all five regions without additional SQL;
- IDs, city/search/limit and date ranges have distinct keys;
- a manual Caffeine ticker advances TTL deterministically, without sleeping;
- cache cleanup verifies the size bound in every region;
- mutable DTO/nested-room changes cannot corrupt subsequent reads;
- successful space/room/booking changes and user cascades refresh dependent data;
- uncommitted reads, explicit rollbacks, conflicts and commit-time failures remain correct;
- deterministic late old-generation population cannot make current reads stale;
- JSON responses, landing details, B1 pages and sorting retain their contracts.

Run the backend normally with `.\mvnw.cmd spring-boot:run`, and React with
`npm run dev`. Existing local URLs remain `/spaces` and `/analytics` on
`http://localhost:5173`; the API is on `http://localhost:8080/api`.
For a presentation, show the benchmark output/query assertions, repeat an existing
space/map/autocomplete/analytics GET, then edit a real space or booking through the
existing application/API and fetch again. No browser-local cache behavior is needed.
Use **Refresh data** on analytics after a booking change. No fake records were added
to normal application data, and no visual browser verification is claimed.

An additional **13 live HTTP GET checks passed** with caching enabled on an isolated
`jdbc:h2:mem:b9-http` database at port 18081. No data writes were made. Repeated map,
autocomplete and analytics requests returned identical 200 JSON responses; B1 space
and room lists and the Thymeleaf spaces page returned 200; invalid map/date ranges
returned 400; missing space/room details returned 404. The smoke server was stopped
after verification. This verifies HTTP compatibility; the service SQL/hit assertions
above establish cache use rather than inferring hits from equal empty responses.

Start the isolated server from `spotpulse-backend` to reproduce:

```powershell
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081 --spring.datasource.url=jdbc:h2:mem:b9-http --spring.jpa.show-sql=false --spring.h2.console.enabled=false'
```

Using any HTTP client, issue the first three paths twice and the others once against
`http://localhost:18081`, and compare their responses/status codes:

```text
/api/spaces/map?limit=200                                       200 (twice)
/api/spaces/autocomplete?q=Alpha                               200 (twice)
/api/analytics/bookings?from=2026-10-01&to=2026-10-03             200 (twice)
/api/spaces?skip=0&limit=6                                      200
/api/rooms?skip=0&limit=6                                       200
/api/spaces/map?limit=0                                         400
/api/analytics/bookings?from=2026-10-03&to=2026-10-01             400
/api/spaces/999999                                             404
/api/rooms/999999                                              404
/spaces                                                       200
```

## Files changed

Verification on 2026-10-26: backend compilation and all **106 backend tests passed**,
with no failures/errors/skips. This includes ten cache correctness tests and the
benchmark. Frontend TypeScript/production build and all **38 React tests** passed.
The existing **8 F2 JavaScript tests** and **5 C8 geocoding tests** passed. The Git
whitespace check passed. Existing C8 maps, F1 analytics, booking concurrency, B1
pagination, autocomplete, landing and SEO tests are included in these complete suites.

All paths are relative to `spotpulse-backend`.

Created:

- `src/main/java/com/spotpulse/cache/ReadCacheConfiguration.java`
- `src/main/java/com/spotpulse/cache/ReadCacheProperties.java`
- `src/main/java/com/spotpulse/cache/ReadCaches.java`
- `src/main/java/com/spotpulse/cache/ReadDataChanged.java`
- `src/main/java/com/spotpulse/cache/SnapshotCaffeineCache.java`
- `src/test/java/com/spotpulse/ReadCacheTests.java`
- `src/test/java/com/spotpulse/CachePerformanceTests.java`
- `docs/B9-api-caching.md`

Modified: `pom.xml`, `src/main/resources/application.properties`, and
`SpaceService.java`, `RoomService.java`, `BookingService.java`,
`BookingAnalyticsService.java`, `UserService.java` under `src/main/java/com/spotpulse/service`.
React, REST controllers, existing DTO formats, deployment files, README and bin files
were not modified. The pre-existing untracked backend README is untouched. No commit
or push was made.

## Limitations and deployment considerations

- This is a single-instance in-memory cache. Each process has its own entries and
  invalidation generations; restart loses them. TTL bounds staleness from external
  SQL writers or another process, but does not provide cross-instance consistency.
  Before scaling to multiple instances, disable these caches or implement shared
  invalidation/distributed caching with transaction-aware event delivery.
- Changes made directly through repositories, SQL consoles, imports or another
  application bypass service events. Use the service write paths, or explicitly
  integrate invalidation; otherwise TTL is the fallback.
- Conservative region-wide eviction can reduce hit rate during heavy write traffic.
  Concurrent misses may execute duplicate reads; no single-flight/stampede protection
  is claimed. Generation keys protect freshness rather than eliminate duplicate work.
- Entry count bounds memory growth, not exact bytes. A space detail includes all its
  existing nested rooms. Tune limits for actual data sizes and traffic; listings
  themselves are intentionally not cached.
- Public analytics remain a demonstration view without RBAC. No authorization or
  user/booking detail results are cached. Future authorization/scoping must be
  evaluated before reusing cache keys for restricted data.
- Measurements and persistence tests use H2. No live PostgreSQL or multi-instance
  deployment verification was performed.

References: [Spring cache annotations](https://docs.spring.io/spring-framework/reference/integration/cache/annotations.html),
[transaction-bound events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html),
[Caffeine eviction](https://github.com/ben-manes/caffeine/wiki/Eviction).

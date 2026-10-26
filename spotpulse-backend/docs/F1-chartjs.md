# F1 — Chart.js booking analytics

Security update: the earlier public-demo access described below is superseded by
[C16/QA3](C16-QA3-oauth2-rbac.md). Analytics now requires OWNER or ADMIN. Owners see
only their own spaces; administrators see platform-wide aggregates. The API itself
enforces these scopes, and the public-demo warning has been removed from React.

## Purpose and access

The React SPA adds `/analytics` and an **Analytics** navigation link. This is a
public demonstration of aggregate booking data. There is no role-based access
control, owner scoping or authentication claim. No user names, emails, user IDs,
booking IDs or individual booking times are returned by the analytics API.

The dashboard uses Chart.js 4 through `react-chartjs-2` 5. It follows the existing
SpotPulse styling and loads its chart code only when the analytics route is opened.
Chart modules register once at module scope. The React wrappers update their chart
instances when data changes and destroy them when unmounted. A route error boundary
handles chart/rendering or lazy-module failures without breaking other routes.

## Charts and meaning

1. **Bookings over time**: line chart with separate confirmed and cancelled series,
   grouped by scheduled booking start date. The numerical axis starts at zero and
   uses integer ticks. Missing days have explicit zero counts. Lines are straight,
   without interpolation that could suggest negative or fractional bookings.
2. **Most popular meeting rooms**: horizontal bar chart of the top ten rooms by
   confirmed booking count in the selected period. Cancelled bookings are excluded.
   Labels contain room name, space name and room ID, so identical room names remain
   distinct. Ordering is count descending, then unique room ID ascending for ties.
   A room may be booked less often but for longer; this is not a utilization-hours
   measure. The top-ten cap is stated beside the chart and in the API metadata.
3. **Booking status distribution**: doughnut chart of confirmed and cancelled
   counts for the same start-date range. Zero datasets show a useful empty message
   instead of a fake segment. Cancelled-only data still shows a status/trend chart,
   with an explicit empty state for confirmed room popularity.

Bookings have `startTime` and `endTime`, with no creation timestamp. Each booking is
counted once on its start date, even when it spans midnight or several days. A
booking starting before the range is excluded even if it ends inside the range.
Counts describe currently stored booking statuses, not status history, attendance,
revenue or the date when reservations were created. No currency is inferred.

The model uses `LocalDateTime`, without an offset or timezone. Date boundaries use
those stored calendar values without timezone conversion. The browser formats
date-only labels without shifting the day. Presets use today's calendar date on the
viewer’s device; select custom dates if that differs from the backend’s calendar.
Dates use `en-GB` formatting, and all counts use the same `Intl.NumberFormat`.

Tooltips describe booking counts; Chart.js legends can toggle the line/doughnut
series. Canvas elements have accessible names. An ordinary text summary, daily
count table and room ranking links provide alternatives to interacting with canvas.
Charts resize with their parent cards, with a single-column mobile layout.
See the [Chart.js accessibility guidance](https://www.chartjs.org/docs/latest/general/accessibility.html).

## API and aggregation

```http
GET /api/analytics/bookings?from=2026-10-01&to=2026-10-03
```

Both ISO date parameters are required. Dates are inclusive, in years 0001–9998,
and the range must cover 1–366 days. Reversed, malformed, missing or excessive
ranges return HTTP 400 with the existing ProblemDetail error format.

Example response shape (illustrative values, not inserted into application data):

```json
{
  "from": "2026-10-01",
  "to": "2026-10-03",
  "grouping": "BOOKING_START_DATE",
  "total": 3,
  "statuses": { "confirmed": 2, "cancelled": 1 },
  "daily": [
    { "date": "2026-10-01", "confirmed": 1, "cancelled": 0 },
    { "date": "2026-10-02", "confirmed": 0, "cancelled": 0 },
    { "date": "2026-10-03", "confirmed": 1, "cancelled": 1 }
  ],
  "popularRoomsLimit": 10,
  "popularRooms": [
    { "roomId": 8, "roomName": "Meeting room", "spaceId": 3, "spaceName": "River Hub", "count": 2 }
  ]
}
```

An empty dataset returns `total: 0`, both status counts as zero, every selected date
with zero counts, and `popularRooms: []`.

The dedicated controller calls a read-only service and repository. Two database-side
scalar aggregation queries run per request:

- counts grouped by `cast(startTime as LocalDate)` and status;
- confirmed counts grouped by room ID/name and space ID/name, limited to ten rows.

Both use `startTime >= from.atStartOfDay()` and
`startTime < to.plusDays(1).atStartOfDay()`. Filtering the timestamp column preserves
the opportunity for date-range indexes in deployments that need them. No entities
or user associations are loaded. There are at most 732 day/status aggregate rows
and ten room rows. The service fills missing days and derives overall status totals
from the day aggregates. A read-only repeatable-read transaction keeps the two
aggregations on a consistent snapshot without changing booking write locks.

The queries use Hibernate's portable LocalDate cast and ordinary joins/grouping,
without database-specific date formatting or PostGIS. Automated integration tests
use H2. PostgreSQL was not run in this task.

Existing booking/list endpoints, B1 response formats, booking concurrency controls,
map endpoints, geocoding, F2 autocomplete and Thymeleaf/SEO pages are unchanged.
There is no API caching or optional coworking-space filter in this scoped task.

## Filters, refresh and errors

The initial range is **Last 30 days**, including today. Options also include
**Last 7 days**, **Next 7 days** and **Next 30 days**, each including today. Selecting
a preset updates all charts together through one aggregate request. Custom date
fields apply together using **Apply dates**; edited-but-unapplied dates do not change
the displayed range. The displayed summary always identifies the applied range.

**Refresh data** fetches the currently applied range without a page reload. Pending
requests show loading feedback. Changing ranges aborts obsolete requests and hides
old-range data. API failures use the existing retry component. A failed lazy chart
download/render shows a separate refresh-the-page message.

## Run and demonstrate

Prerequisites remain Java 25, Maven and Node.js. No API key or new environment
variables are needed. `VITE_API_BASE_URL` and Vite's existing `API_PROXY_TARGET`
configuration still apply.

From `spotpulse-backend`:

```powershell
.\mvnw.cmd spring-boot:run
```

From a second terminal in `spotpulse-frontend`:

```powershell
npm ci
npm run dev
```

- Dashboard: `http://localhost:5173/analytics`.
- Discovery/map: `http://localhost:5173/spaces`.
- API: `http://localhost:8080/api/analytics/bookings?from=2026-10-01&to=2026-10-03`
  (choose dates containing your actual data).

Demonstration:

1. Open the dashboard and point out the public demonstration notice and start-date
   definition. With no historical data, the Last 30 days empty state is expected.
2. Use the existing booking flow to schedule real meetings in available rooms, or
   use existing booking data. Do not insert test fixtures into the application.
3. Select Next 7/30 days or a custom range containing those starts. Confirm all three
   charts use the same range. Hover for counts and toggle a legend series.
4. Include bookings for distinct rooms (duplicate names are supported). Change an
   appropriate booking's status through the existing REST API, then select
   Refresh data. Confirm that cancellation moves the status counts and excludes
   that booking from room popularity. The SPA itself does not add a cancellation UI.
5. Open the daily count table and room ranking text, try keyboard navigation, and
   inspect the layout on a narrow screen. Test a range without bookings and an
   invalid/reversed range. Simulate an API failure to demonstrate retry.
6. Return to Explore spaces and confirm list pagination, C8 maps and booking work.

The default development H2 database is in memory. Keep the server running during
demonstration or use the project's existing persistent database configuration.

## Verification commands

Run from `spotpulse-backend`:

```powershell
.\mvnw.cmd -DskipTests compile
.\mvnw.cmd clean test
node --test src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s tools -p 'test_*.py'
```

This environment uses the installed Maven with the existing dependency cache. These
are the exact local Maven commands used; the clean test command also recompiles:

```powershell
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o -DskipTests compile
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o clean test
```

Run from `spotpulse-frontend`:

```powershell
npm run build
npm test -- --testTimeout=20000 --maxWorkers=1
```

The build includes `tsc --noEmit` and Vite production bundling. Longer test timeouts
and one worker accommodate this Windows environment. Sandbox access failures for
Maven's dependency JARs and Vite's parent-directory access require running those
verification commands with the appropriate filesystem access.

`BookingAnalyticsTests` uses MockMvc with real services/repositories and an isolated
H2 database. It verifies exact date grouping and boundaries, multi-day behavior,
status totals, duplicate room names, stable ties, top-ten limits, zero datasets,
366-day bounds, invalid input, absence of personal data, and exactly two SQL
statements. Historical booking fixtures exist only inside rollback-isolated tests.

`Analytics.test.tsx` mocks chart rendering and HTTP, testing the route, response-to-
chart mapping, accessible summaries, presets, custom dates, validation, refresh,
loading/errors/retry, empty data and cancelled-only data. Existing C8 frontend tests,
booking tests and backend regressions remain in their complete suites. Automated
tests do not require live external services.

## Files changed

Verification on 2026-10-26: clean Java compilation and all **95 backend tests**
passed (including five new analytics integration tests). TypeScript/production
build and all **38 frontend tests** passed. The existing F2 JavaScript suite passed
**8 tests**, and C8's mocked geocoding suite passed **5 tests**. Git whitespace
validation passed. Chart code is emitted as a separate lazy-loaded production chunk.
The initial sandboxed compiler/build attempts failed on filesystem access; the
same checks passed with the required access. No browser visual check was performed.

Live HTTP smoke verification also passed against a separately started application
on port 18081 using the isolated `jdbc:h2:mem:f1-http` database. Six GET checks made
no data writes: valid empty analytics returned 200 with three explicit zero dates;
reversed dates and missing parameters returned 400; the C8 map endpoint, B1 spaces
endpoint and Thymeleaf `/spaces` page returned 200. The smoke server was then stopped.
To reproduce, start the backend with:

```powershell
& 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd' '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081 --spring.datasource.url=jdbc:h2:mem:f1-http --spring.jpa.show-sql=false --spring.h2.console.enabled=false'
```

GET the following URLs (PowerShell `Invoke-WebRequest -UseBasicParsing` or another
HTTP client) and compare status codes; stop that isolated server after verification:

```text
http://localhost:18081/api/analytics/bookings?from=2026-10-01&to=2026-10-03  -> 200
http://localhost:18081/api/analytics/bookings?from=2026-10-03&to=2026-10-01  -> 400
http://localhost:18081/api/analytics/bookings                             -> 400
http://localhost:18081/api/spaces/map?limit=200                           -> 200
http://localhost:18081/api/spaces?skip=0&limit=6                          -> 200
http://localhost:18081/spaces                                            -> 200
```

Created under `spotpulse-backend`:

- `src/main/java/com/spotpulse/controller/BookingAnalyticsController.java`
- `src/main/java/com/spotpulse/service/BookingAnalyticsService.java`
- `src/main/java/com/spotpulse/repository/BookingAnalyticsRepository.java`
- `src/main/java/com/spotpulse/dto/BookingAnalyticsDto.java`
- `src/test/java/com/spotpulse/BookingAnalyticsTests.java`
- `docs/F1-chartjs.md`

Created under `spotpulse-frontend`: `src/Analytics.tsx`, `src/test/Analytics.test.tsx`.
Modified: `src/App.tsx`, `src/api.ts`, `src/components.tsx`, `src/style.css`,
`package.json`, `package-lock.json`.

README, Docker/deployment files, generated bin files and application data are
untouched. No commit or push is performed.

## Limitations

- This is aggregate public demonstration analytics, without role-based access.
- Counts reflect current statuses of scheduled starts, without creation timestamps,
  status history, attendance tracking, revenue or duration-based utilization.
- Popular rooms are capped at ten; the date range is capped at 366 days. There is no
  space filter, caching, export or real-time subscription.
- Presets use the device calendar; stored booking times have no timezone metadata.
- PostgreSQL and live browser appearance/interactivity were not verified here.

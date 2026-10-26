# F5 — Separate React SPA

The independent `spotpulse-frontend` application uses React, TypeScript, Vite, React Router and Bootstrap 5. It consumes the existing JSON controllers without changing MVC routes, Thymeleaf, F2 autocomplete, C6/C7, booking locking or API response contracts. No backend additions were needed.

## Run locally

Use Java 25 and Node.js 22.12+ (verified with Node 24). In two PowerShell terminals, starting at the repository root:

```powershell
cd spotpulse-backend
.\mvnw.cmd -B spring-boot:run
```

```powershell
cd spotpulse-frontend
npm.cmd ci
npm.cmd run dev
```

Open http://localhost:5173. The existing MVC interface remains at http://localhost:8080/spaces. Vite proxies `/api` to http://localhost:8080, so development needs no CORS changes. Port 5173 is strict to avoid silently selecting a different URL.

Copy `.env.example` to `.env.local` to override `API_PROXY_TARGET` for development, or `VITE_API_BASE_URL` for a deployment API base (including `/api`). Vite embeds the latter at build time. B1 links retain this configured base. In production, serve `dist` with SPA fallback to `index.html` and reverse proxy the API on the same origin. A different API origin would require an explicit deployment CORS policy; none is added here. `vite preview` previews the bundle; it is not a production server and does not configure the development proxy.

## Screens and contracts

- `/` redirects to `/spaces`. Discovery uses `GET /api/spaces?skip=0&limit=6`, responsive real-data cards, optional images, loading/retry/error/empty states and server pagination. A submitted search adds either `search` (name/city/address substring) or `city` (exact city), respecting controller precedence. Search filters live in the URL.
- `/spaces/:id` uses `GET /api/spaces/{id}` for description, address, image and email. It queries `GET /api/rooms?spaceId={id}&skip=0&limit=6` for associated rooms and rates.
- `/rooms/:id` uses `GET /api/rooms/{id}` and paginated `GET /api/users?skip=0&limit=20`. Select an actual existing user, including users on later pages; the selected identity remains visible when paging. This is explicit identification, not authentication.
- The booking form sends `POST /api/bookings` with `roomId`, `userId`, `startTime`, `endTime`. Status is omitted to retain the server's CONFIRMED default. The UI validates required values and future/ordered times, then displays the saved booking's ID, room, user, times and status. Server validation remains authoritative. Submission disables duplicate actions, and a saved booking must be explicitly reset before another submission.
- Unknown client routes have a recovery page. Resource 404s, request 400s and field errors, booking 409 conflicts, network failures and server 500s have visible feedback. Safe text rendering avoids HTML injection. Forms have labels, keyboard controls, focus feedback and live status messages.

`api.ts` owns DTO types, fetch, ProblemDetail handling and configured-base translation. Shared components own cancellable GET requests, B1 navigation, images and feedback. `App.tsx` owns routes and screens. All list requests use B1's `items/skip/limit/total/nextLink`; Next follows the returned link, Previous remembers visited links. Requests are cancelled on navigation; no background polling or full-catalog prefetch is used.

## Verification commands

```powershell
cd spotpulse-frontend
npm.cmd ci
npm.cmd run build
npm.cmd test
cd ../spotpulse-backend
.\mvnw.cmd -B -DskipTests compile
.\mvnw.cmd -B test
node --test src/test/js/space-autocomplete.test.cjs
```

Frontend tests mock fetch only in the test environment and cover discovery, API success/loading/retry/error/empty states, B1 nextLink/Previous, filters, dynamic details, rooms, navigation/404, local booking validation, confirmed responses, backend field validation and HTTP 409. Production fetch behavior is unchanged.

Verified on 2026-10-26 with Java 25, Maven 3.9.16 and Node 24.21.0:

- Dependency installation succeeded; the final dependency audit reported zero vulnerabilities after updating Vitest to 5.0.5. `package-lock.json` records the resolved dependency versions.
- Production build: TypeScript check and Vite build passed.
- Frontend: 19 tests passed across 2 files, zero failures.
- Backend compilation: BUILD SUCCESS. Full backend suite: 85 tests, zero failures, errors or skips (18 stabilization, 26 pagination, 27 autocomplete, 8 landing pages, 5 SEO, 1 application context).
- Existing F2 JavaScript suite: 8 tests passed, zero failures or skips.
- Live HTTP checks: Vite on 5173 proxied to the existing backend JAR on 18080 using a separate `jdbc:h2:mem:f5smoke` database. SPA route fallback, discovery, B1 nextLink traversal, dynamic space/room/user reads, booking 201, overlap 409, validation 400, resource 404 and the existing MVC detail page all passed. Test records were confined to that temporary database. No visual browser verification was performed.

The automated Windows sandbox resolved Maven home as `C:\` and blocked esbuild's parent-directory reads. Verification therefore ran outside the sandbox with the cached Maven executable and an explicit repository:

```powershell
& 'C:\Users\Alexandra T\.m2\wrapper\dists\apache-maven-3.9.16-bin\5grr65jo27hi51sujmtcldfovl\apache-maven-3.9.16\bin\mvn.cmd' '-Dmaven.repo.local=C:\Users\Alexandra T\.m2\repository' -B -DskipTests compile
# Same executable/repository with -B test for the full suite.
```

This is an environment workaround; the project wrapper and configuration were preserved. On an ordinary local terminal use the wrapper commands above.

## Demonstration limitations

- Default H2 is in-memory and no production records are fabricated or seeded by the SPA. Create real spaces/rooms/users through existing MVC/REST tools before demonstrating a booking. An empty installation shows explicit empty states.
- There is no availability endpoint. The UI makes no advance availability claim; POST checks conflicts using existing backend transaction locking. Another booking may win the race; HTTP 409 is shown and the form can be corrected.
- DTO times are timezone-free `LocalDateTime`. Enter the backend's local wall-clock time; for local demonstration keep the browser and server in the same timezone. No UTC conversion is performed.
- The room API exposes no currency. Rates are displayed numerically without inventing one.
- No OAuth, payment, maps, charts or booking-management screens are included. Browser visual and assistive-technology verification must be performed separately; automated DOM tests do not constitute visual verification.

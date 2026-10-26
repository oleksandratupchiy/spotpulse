# C16 + QA3 — Google login, ownership and authorization

## Configuration and local startup

SpotPulse uses Spring Security 7.1.1, managed by Spring Boot 4.1.1, and the OAuth2
Client starter. Google OpenID Connect is the only login method. Password/basic
login and public local-account registration are not enabled. No custom JWT is used.

The existing Google OAuth client must be a **Web application**. Its authorized
redirect URI must exactly include:

```text
http://localhost:8080/login/oauth2/code/google
```

Only `openid`, `email`, `profile` scopes are requested. Configure the Google consent
screen/audience and permit the demonstration accounts if the client is in testing.
There is no browser Google SDK or browser token exchange; no downloaded client-secret
JSON file is needed. This implementation did not read or copy such files.

Required backend environment variable **names**, never their values:

- `GOOGLE_CLIENT_ID`
- `GOOGLE_CLIENT_SECRET`

Both are read from the launching process environment into the backend registration.
Keep the already configured PowerShell window open and launch Maven **from that same
window**, in `spotpulse-backend`:

```powershell
.\mvnw.cmd spring-boot:run
```

Do not echo/list the environment or paste credentials into application files, shell
history commands, frontend variables, screenshots or logs. An IDE or another terminal
does not automatically inherit variables set in an existing PowerShell process.

From a second terminal in `spotpulse-frontend`:

```powershell
npm ci
npm run dev
```

Open `http://localhost:5173/spaces`, then **Sign in with Google**. The actual login
entry is `http://localhost:8080/oauth2/authorization/google`. Use **localhost** for
both applications, not a mixture of localhost and 127.0.0.1.

If either Google variable is absent, the backend still starts securely: public
discovery works, protected routes stay protected, `/api/auth/me` reports
`loginEnabled: false`, and the login entry returns 503. No fallback credentials,
password account, mock production login or public role bootstrap is created.

| Setting | Default / purpose |
| --- | --- |
| `SPOTPULSE_FRONTEND_ORIGIN` | `http://localhost:5173`; fixed trusted origin for login success/failure redirects. No path/query/fragment or userinfo permitted. |
| `SPOTPULSE_OAUTH_REDIRECT_URI` | `http://localhost:8080/login/oauth2/code/google`; must match Google configuration. |
| `SPOTPULSE_SESSION_SECURE` | `false` for local HTTP; must be `true` for production HTTPS. HTTPS frontend configuration with insecure cookies fails startup. |
| `SPOTPULSE_BOOTSTRAP_ADMIN_ID` | Empty by default. One-time operator-controlled promotion of an existing verified Google account; see below. |
| `VITE_AUTH_BASE_URL` | Browser login-entry base: localhost:8080 in Vite development, same origin in production. Contains no credentials. |
| `VITE_API_BASE_URL`, `API_PROXY_TARGET` | Existing API base/proxy configuration. Use same-origin `/api` via Vite's proxy in development. |

## Session and redirect flow

1. React navigates the browser to the backend Google authorization entry. Spring
   stores the authorization request/state in the server session and sends the browser
   to Google.
2. Google returns to the backend callback. Spring Security performs the standard
   authorization-code/OIDC flow, including state, ID-token signature/issuer/audience
   and nonce validation. The normal OIDC user service runs before local identity
   resolution. Google is not called by automated tests.
3. The application resolves `(oauthProvider = google, oauthSubject = sub)`, never
   the display name. A new verified-email identity receives **USER**, regardless of
   OAuth claims named role/roles/authorities. The identity tuple has a database unique
   constraint. Concurrent conflicting first registrations fail safely rather than
   merging accounts; retry login after a concurrent successful registration.
4. Spring's session-fixation protection changes the session ID. A server-side
   session is established. The success handler always redirects to the configured
   frontend origin plus `/spaces`; failure redirects to `/login` with one of two
   fixed error codes. Request-supplied callback/return URLs are not used. Saved-request
   redirects are disabled, preventing untrusted open redirects.
5. React calls `GET /api/auth/me` via the development proxy. Browser cookies are
   host-based, not port-based, so localhost's backend session cookie also accompanies
   same-origin proxy requests from the SPA.

Session cookies are HttpOnly, SameSite=Lax, host-only, and Secure when configured for
HTTPS. Server-side idle timeout is 30 minutes. The SPA stores only its safe profile
in React state, not access/refresh/ID tokens in localStorage or sessionStorage. OIDC
principal/provider client credentials and tokens remain server-side runtime objects;
they are not returned in DTOs or written to project configuration files.

A filter checks the stored user/provider subject and refreshes the role from the
database on **every HTTP request**. Deleted/mismatched identities invalidate the
session, and demotions apply to the next request, even if aggregate data is cached.
Already authorized operations in progress are not retroactively revoked.

`POST /api/auth/logout` requires CSRF and invalidates the server session, clears
authentication and expires JSESSIONID, returning 204. This signs out of SpotPulse;
it does not sign the browser out of Google or revoke Google consent. Spring's
authorized-client infrastructure is server-local; multi-instance sessions/provider
client persistence and provider-wide revocation are deployment work, not implemented
here.

## Authentication and CSRF API

```http
GET /api/auth/me
```

Authenticated shape:

```json
{"authenticated":true,"user":{"id":42,"name":"Member","email":"member@example.test","role":"USER"},"loginEnabled":true}
```

Anonymous shape: `{"authenticated":false,"user":null,"loginEnabled":true}`.
Only the authenticated user's id/name/email/role is returned. Provider subject,
tokens, client credentials, authorities internals and entity associations are absent.

```http
GET /api/auth/csrf
```

Returns `{"token":"<masked session token>","headerName":"X-CSRF-TOKEN"}` and can
establish an anonymous session before login. Default session-based CSRF and BREACH
masking remain enabled. Before every mutation, the SPA obtains a fresh token and
submits it in the specified header with same-origin credentials. It does not retry
the mutation if token acquisition fails. Fresh retrieval also handles token rotation
after authentication/logout. HTTP 204 responses are supported by the API client.
Thymeleaf POST forms with `th:action` receive hidden CSRF fields from Spring's request
data processor. `_csrf` is excluded from pagination links to avoid token URLs.

Protected API reads return 401 for anonymous requests; role/ownership denials return
403. Missing/invalid CSRF on unsafe requests returns 403, including an anonymous
request, before an authorization decision may be reached. The SPA displays these
failures without pretending that hidden buttons authorize an operation. Default
security cache-control headers prevent browser caching of session/CSRF data.

## Permission matrix

| Operation | Visitor | USER | OWNER | ADMIN |
| --- | --- | --- | --- | --- |
| Public discovery/details, rooms, maps, autocomplete, SEO GET/HEAD | Yes | Yes | Yes | Yes |
| Profile/logout | Anonymous state / CSRF logout | Own | Own | Own |
| Create confirmed booking | No | Own identity | Own identity | Own by default; explicit on-behalf operation supported by API |
| List `/api/bookings` | No | Own only | Own only | Platform; established administrative filters |
| Read booking detail | No | Own only | Own or owned-space booking | Any |
| Cancel confirmed future booking | No | Own only | Own or owned-space booking | Any |
| Full booking PUT / DELETE | No | No | No | Yes |
| Create space | No | No | Owned by session user | Owned by session user; can reassign explicitly |
| Edit/delete space or rooms | No | No | Own spaces only | Any |
| Managed-space/booking lists | No | No | Owned spaces only | Platform |
| Analytics | No | No | Owned spaces only | Platform |
| User listing/create/update/delete and role assignment | No | No | No | Yes |
| Assign legacy/existing space owner | No | No | No | Yes |

There is no role hierarchy assumption: OWNER and ADMIN explicitly receive ordinary
authenticated booking capabilities. Owner booking management is intentionally scoped
to viewing and cancelling future meetings, without rewriting a customer's user,
room or dates. Started bookings cannot be cancelled. Cancellation is idempotent,
locks the booking and room rows, and publishes the existing after-commit analytics
invalidation event. Existing capacity, time validation and overlap protection remain.
Space/room deletion retains existing cascade semantics and the SPA asks for explicit
confirmation before deleting their dependent records.

Authorization is enforced by the security filter chain **and** service method guards,
with explicit ownership guards for legacy MVC edit routes. Public entity DTO reads
remain public; protected management reads use separate routes. An OWNER must be
authorized for both the current room and target space before moving a room. Client
owner IDs are not accepted by ordinary space create/edit DTOs.

The last ADMIN cannot be demoted or deleted. Administrative user mutations lock
administrator rows before checking that invariant. OAuth provider/subject fields
are not writable through user DTOs, and linked-account emails cannot be changed via
the ordinary admin user-edit API. Reassign spaces before deleting an account that
owns spaces. User deletion can still cascade its bookings after ownership is cleared.

## Ownership and safe legacy migration

`Space.owner` is a nullable many-to-one User relationship stored in `spaces.owner_id`.
Existing rows stay null/unassigned. New OWNER spaces are assigned from the session,
not request content. Existing data is not assigned to the first Google login.

`User.oauthProvider` and `oauthSubject` are nullable for legacy records. Existing role
and booking records remain valid. Hibernate's existing `ddl-auto=update` adds these
columns, their uniqueness constraint and the owner foreign key. For a managed
migration deployment, apply equivalent additive columns/constraints after a backup;
do not recreate or delete existing tables.

**No automatic linking policy:** if a new Google subject's email matches an existing
local account, including a local ADMIN, login is rejected with
`account_migration_required`. A matching email, even a verified one, is not treated
as consent to merge account histories or inherit privileged roles. A returning
provider subject keeps its existing account even if Google later changes the email
or display name; Google claims do not change the stored role.

This task deliberately has no online account-linking endpoint. An administrator or
database operator must review legacy migration separately, verify the real person's
control and historical-account entitlement out of band, back up the data, and record
the approved changes. One supported migration policy is to retain/archive the legacy
record under a reviewed distinct email, allow a **separate USER** Google record, then
explicitly assign reviewed space ownership and role through administration. Legacy
bookings remain attached to their original account unless an operator separately
authorizes their migration. Merely changing a legacy email does not transfer its
bookings, role or spaces to the new Google identity. Never set provider subject fields
based solely on an email match or an unverified client-supplied subject.

## First ADMIN and demonstration OWNER

Use a **persistent database before the first login** if you need bootstrap promotion
across a restart. The existing development default is in-memory H2 and loses records
on restart. Do not expect an account ID in that database to survive. Use an existing
PostgreSQL deployment, or opt into an H2 file database outside the Git tree through
`SPRING_DATASOURCE_URL`; do not replace/delete an existing database. For example,
choose a local filesystem path and set a `jdbc:h2:file:` URL before starting the
server. Keep the same URL for all subsequent demonstration runs.

For a **fresh persistent demonstration database**, the following uses a directory
outside the repository. Keep your current database URL instead if it contains the
legacy records you intend to migrate:

```powershell
$spotpulseDataDir = Join-Path $env:LOCALAPPDATA 'SpotPulse'
New-Item -ItemType Directory -Force -Path $spotpulseDataDir | Out-Null
$spotpulseDatabase = (Join-Path $spotpulseDataDir 'spotpulse').Replace('\', '/')
$env:SPRING_DATASOURCE_URL = "jdbc:h2:file:$spotpulseDatabase"
.\mvnw.cmd spring-boot:run
```

1. With bootstrap unset, sign in once with the intended administrator's Google
   account. Confirm its profile shows USER and note its own account ID on `/account`.
2. Stop that backend. In its trusted PowerShell environment, set
   `SPOTPULSE_BOOTSTRAP_ADMIN_ID` to that reviewed existing account's numeric ID and
   restart against the **same persistent database**. This is a machine-operator
   action, not an HTTP request. It requires that no ADMIN already exists and that
   the target is an existing Google-linked user. It does not create an account,
   guess an email, accept a Google role claim, or assign any legacy spaces.
3. Remove `SPOTPULSE_BOOTSTRAP_ADMIN_ID` from the environment after this one-time run
   (stop that backend, run `Remove-Item Env:SPOTPULSE_BOOTSTRAP_ADMIN_ID`, then restart
   against the same persistent database). Leave it unset on future starts;
   a populated database with an ADMIN causes bootstrap to refuse startup if the flag
   is left enabled. Run this operator step on one backend instance only.
4. Sign in again and open `/admin`. Other accounts cannot call user/role management
   until an ADMIN exists. If legacy local ADMIN rows already exist, bootstrap refuses
   to override them: a reviewed offline operator migration is required instead.
5. To demonstrate OWNER, sign in with a second Google account first (it becomes USER).
   Use the ADMIN dashboard to find that verified account ID and explicitly change its
   role to OWNER. The next HTTP request refreshes its role; reload that browser to
   refresh navigation. There is no public signup/role selector or user self-promotion.
6. The OWNER can create its own spaces/rooms at `/owner`. Assign legacy spaces using
   the ADMIN **Assign space ownership** action, after reviewing entitlement. Try a
   second owner's ID/resource in a direct API request: the backend must return 403.

No ADMIN/OWNER production record was created or promoted during implementation.
Bootstrap and privileged fixtures are exercised only in isolated tests. Remove
bootstrap flags before running automated tests.

## Routes, API changes and interfaces

SPA routes added: `/login`, `/account`, `/bookings`, `/owner`,
`/owner/spaces/new`, `/owner/spaces/:id`, `/admin`. `/analytics` now requires
OWNER/ADMIN and no longer claims to be a public demonstration. The public discovery,
space/room details, booking confirmation, C8 map and Chart.js displays are retained.

The owner workspace provides space create/edit/delete, room create/edit/delete,
owned booking review/cancellation and scoped analytics. Administration provides user
role management, explicit owner assignment and links to platform-wide space/booking
management and analytics. Full administrative booking reassignment/deletion and local
user create/delete remain authorized REST operations; additional dedicated SPA
edit/delete-account or booking-reassignment wizards are not implemented.

New endpoints:

- `GET /api/auth/me`, `GET /api/auth/csrf`, `POST /api/auth/logout`.
- `GET /api/management/spaces`, `GET /api/management/spaces/{id}`.
- `GET /api/management/bookings`.
- `PUT /api/admin/spaces/{id}/owner` with `{ "ownerId": <reviewed account ID> }`.
- `POST /api/bookings/{id}/cancel`.

Intentional security contract changes:

- Booking create `userId` is optional. Non-admin callers use their session identity;
  an explicitly different ID is rejected with 403. Non-admin callers may create only
  confirmed bookings, and use the cancellation endpoint later. React sends no userId.
- Ordinary booking lists are now scoped to the current user; arbitrary user-directory
  selection and public booking/user reads are removed. Owner space bookings use the
  management list. Admin booking lists retain existing filter precedence and B1 shapes.
- Full booking update/delete is admin-only. An omitted user ID on an admin update
  preserves the existing booker. Time/availability validation still applies.
- All space/room REST and MVC mutations require an authorized OWNER/ADMIN and CSRF.
  User endpoints require ADMIN and CSRF on mutations. Unknown routes default-deny.
- Analytics is private management data, scoped server-side by the authenticated role
  and owner ID, never a client-supplied owner filter.

Existing public DTO shapes and B1 pagination remain unchanged. Public GET/HEAD routes
include SEO landing pages, sitemap/robots, maps and autocomplete. H2's web console is
disabled and denied; use a trusted local/database administration tool instead.

## Cache integration

Public space/room details, autocomplete and maps retain their B9 boundaries. Analytics
keys now contain the invalidation generation **and** `platform` or `owner:<id>` plus
the date range. Method authorization executes **before cache lookup**; cache advice
still surrounds the read transaction. Authorization decisions, user/session records,
bookings and management lists are not cached. An authentication filter database lookup
is expected on authenticated HTTP requests even when the aggregate itself is cached.

Ownership changes publish the same space-change event, invalidating public dependent
labels and all analytics scopes after commit. Cancellations use booking-change events.
Rollback, commit failure, DTO-copy and late stale-refill protections remain in the
complete B9 regression suite. A non-transactional security cache test actually warms
platform/owner entries, verifies distinct results and hits, reassigns ownership, then
checks immediate denial after owner demotion. This complements transactional security
tests that deliberately bypass shared caches.

## Geocoding compatibility

The C8 operator tool still uses cached/throttled provider requests and requires
explicit candidate confirmation. Saving coordinates now also requires a legitimate
OWNER/ADMIN server session. The optional `SPOTPULSE_SESSION_COOKIE` environment value
can carry that operator's own session cookie to the trusted SpotPulse API. Treat it
as a secret: never print, commit or share it, remove it after use, and use HTTPS
outside localhost. It is never sent to the external geocoder. The tool obtains a fresh
masked CSRF token from `/api/auth/csrf` before PUT. Unauthorized saves fail instead of
bypassing authorization. Alternatively, enter independently confirmed coordinates in
the authenticated space editor. Google client credentials are not used by the tool.
The older optional bearer-token hook is not an authentication mechanism provided by
this backend; it does not bypass the new session/RBAC policy.

## QA3 and regression verification

Tests use Spring Security's mock OIDC/authentication and CSRF support. The production
filter chain and method authorization stay enabled. No tests require real Google
credentials, live Google HTTP calls or external geocoding. A dummy client registration
exists only inside `SecurityTests` configuration, never as a production fallback.

Security coverage includes public/protected anonymous reads; OAuth entry state/scopes
and fixed callback; forged callback denial; new USER provisioning; stable subject
mapping; malicious role-claim rejection; legacy email conflicts; safe profile fields;
CSRF failures and successful unsafe requests; explicit logout/session invalidation;
deleted identities; role refresh/demotion; direct REST IDOR attempts; MVC IDOR denial;
owner/admin permissions; booking impersonation, cancellation and started-booking rules;
scoped analytics and cache isolation; and operator bootstrap constraints.

Regression tests now provide explicit ADMIN mock authentication and header CSRF tokens
for their intended fixture operations. Mixed MVC/service tests restore the test
security context after requests. Concurrent booking tests propagate that context
through `DelegatingSecurityContextExecutorService`, without changing production
thread-local behavior or disabling security.

From `spotpulse-backend`:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd -Dtest=SecurityTests,SecurityCacheTests,AdminBootstrapTests test
node --test src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s tools -p 'test_*.py'
```

The exact installed Maven/cache invocation used in this workspace:

```powershell
$mvn = 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o clean test
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o '-Dtest=SecurityTests,SecurityCacheTests,AdminBootstrapTests' test
```

From `spotpulse-frontend`:

```powershell
npm run build
npm test -- --testTimeout=20000 --maxWorkers=1
```

The build runs TypeScript checking before Vite. New React tests cover login/profile,
logout/CSRF, guarded routes, owner space creation, backend denial feedback, role and
ownership actions, own booking cancellation and absence of user-directory booking
selection. Provider rendering remains mocked in map/chart tests. Sandbox Maven-cache
and Vite parent-directory access failures required the same explicit access reruns as
the earlier assignments; these were not fixed by weakening security.

## Files created or modified

Verification on 2026-10-26:

| Check | Result |
| --- | --- |
| Clean Java 25 compilation and complete backend suite | 126 passed; 0 failures/errors/skips |
| Dedicated QA3 (`SecurityTests`, `SecurityCacheTests`, `AdminBootstrapTests`) | 20 passed |
| TypeScript and production build | Passed |
| Complete React suite | 52 passed across 5 files |
| Existing F2 JavaScript suite | 8 passed |
| C8 mocked geocoding suite, including CSRF saving | 5 passed |
| Live HTTP security smoke checks | 19 passed |
| Git whitespace validation | Passed |

The final dedicated QA3 run recompiled and verified the cancellation authorization
recheck after locks are acquired. All remaining regression suites had passed in the
clean full run. Initial test integration failures (an ambiguous test import, CSRF
parameters appearing in pagination links, and mixed MVC/service test context cleanup)
were fixed; the real filter chain and method guards stayed enabled.

The HTTP smoke instance used port 18081 and isolated `jdbc:h2:mem:c16-http`, with no
Google requests or application-data writes. Public discovery/map/autocomplete/room/
Thymeleaf/robots reads and public HEAD returned 200; user, booking, management and
analytics reads returned 401 anonymously. Anonymous `/api/auth/me` returned a safe
200 response with `authenticated: false`. It checked `Cache-Control: no-store`, a
fresh session cookie's HttpOnly and SameSite=Lax attributes, unsafe requests rejected
without CSRF (403), real masked CSRF accepted before authorization rejected an
anonymous mutation (401), logout (204), and reuse of the old token rejected after
session invalidation (403). No cookie or token values were printed. Authenticated
logout/ownership/roles are covered by the OIDC MockMvc suite. The smoke server was
stopped after checking, so it does not occupy a local port.

To reproduce its isolated launch, from the backend directory:

```powershell
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o spring-boot:run '-Dspring-boot.run.arguments=--server.port=18081 --spring.datasource.url=jdbc:h2:mem:c16-http --spring.jpa.show-sql=false --spring.h2.console.enabled=false'
```

Use an HTTP client with a cookie jar. Read `/api/auth/csrf` in a fresh jar before
inspecting the cookie (an earlier rejected unsafe request may already have created
an anonymous session). Submit the returned masked token in its named header. Never
print cookie, CSRF, OAuth credential or provider-token values while recording a demo.

Created under `spotpulse-backend/src/main/java/com/spotpulse`:

- `security/SecurityConfiguration.java`, `AppOidcUser.java`, `GoogleIdentityService.java`,
  `FreshIdentityFilter.java`, `AccessPolicy.java`, `AdminBootstrap.java`.
- `controller/AuthController.java`, `controller/ManagementController.java`.

Modified backend application files:

- `pom.xml`, `src/main/resources/application.properties`.
- `model/User.java`, `model/Space.java`, `dto/BookingCreateDto.java`.
- `service/SpaceService.java`, `RoomService.java`, `UserService.java`,
  `BookingService.java`, `BookingAnalyticsService.java`.
- `repository/UserRepository.java`, `SpaceRepository.java`, `RoomRepository.java`,
  `BookingRepository.java`, `BookingAnalyticsRepository.java`.
- `controller/ApiExceptionHandler.java`, `BookingController.java`, `SpaceMvcController.java`.
- `cache/ReadCacheConfiguration.java`, `pagination/PaginationSupport.java`.
- `tools/geocode_space.py`, `tools/test_geocode_space.py`.

Created backend tests: `SecurityTests.java`, `SecurityCacheTests.java`,
`AdminBootstrapTests.java`. Updated authenticated/CSRF fixture setup in
`BackendStabilizationTests.java`, `PaginationTests.java`, `SpaceAutocompleteTests.java`,
`SpaceLandingTests.java`, `SpaceMapTests.java`, `BookingAnalyticsTests.java`,
`ReadCacheTests.java`, `CachePerformanceTests.java`.

Created frontend files: `src/Auth.tsx`, `src/Management.tsx`,
`src/test/Auth.test.tsx`. Modified `src/App.tsx`, `src/api.ts`, `src/Analytics.tsx`,
`src/test/App.test.tsx`, `src/test/Analytics.test.tsx`, `src/test/api.test.ts`.

Created this document and added superseding security notes to the C8, F1 and B9 docs.
README, Docker/deployment files, generated bin files and the application entry point
were not modified. The pre-existing untracked backend README is untouched. No commit,
push, real account promotion or application-data deletion was performed.

## Production and remaining limitations

- Serve SPA/API under the same HTTPS origin (SPA at the origin root), with trusted
  reverse-proxy routing to the backend. Configure real HTTPS callback/frontend URLs
  and `SPOTPULSE_SESSION_SECURE=true`. Only trust forwarded headers from the actual
  proxy. Cross-origin credentialed API/CORS deployment is intentionally not enabled.
- Use persistent H2/PostgreSQL with reviewed additive migrations/backups. Default
  in-memory H2 is for local use and cannot retain login/bootstrap records on restart.
- In-memory sessions/authorized clients and B9 caches are single-instance. Multi-node
  deployment needs shared/sticky sessions and distributed cache invalidation, or
  disabling local caches. No new infrastructure was introduced here.
- Keep secrets in backend environment/secret management, never in VITE variables.
  Do not enable environment dumps or OAuth HTTP TRACE/body logging in production.
- Account linking is intentionally unavailable online. Legacy collisions require an
  explicit reviewed migration; they do not inherit roles automatically.
- Local logout does not revoke Google consent or instantly invalidate other Google
  sessions. Session expiration and the per-request local identity/role checks apply.
- Administrative UI covers core role/ownership workflows; some privileged CRUD
  operations are REST-only as listed above. No impersonation UI or public role signup
  was added.
- Live Google consent/code exchange and browser visual behavior were **not** verified
  during implementation. The real client configuration and manual sign-in must be
  checked from the user's credential-bearing PowerShell session. Automated tests do
  verify application identity mapping, mock authenticated sessions and authorization.
- No live PostgreSQL, external Google availability or multi-instance deployment was
  tested. This is not a production penetration-test certification or a claim of zero
  residual risk; operational hardening such as login throttling/audit operations is
  future deployment work.

References: [Google server OAuth flow](https://developers.google.com/identity/protocols/oauth2/web-server),
[Google OIDC](https://developers.google.com/identity/openid-connect/openid-connect),
[Spring OAuth2 login](https://docs.spring.io/spring-security/reference/servlet/oauth2/login/advanced.html),
[Spring CSRF](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html),
[Spring OIDC testing](https://docs.spring.io/spring-security/reference/servlet/test/mockmvc/oauth2.html).

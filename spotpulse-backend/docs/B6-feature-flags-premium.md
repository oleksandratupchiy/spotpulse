# B6: Feature flags and premium functionality

Premium is an **administrator-managed demonstration tier**. There are no purchases, payments, subscriptions, financial transactions, or simulated checkout flows.

## Architecture and persistence

The backend owns every decision. React renders effective discovery fields supplied by the backend; it never supplies a role or user ID to obtain an entitlement or analytics access.

- `premium/FeatureKey` is the explicit four-key registry, including descriptions. Unknown keys are rejected with HTTP 400.
- JPA `FeatureFlag` stores the registry key (`feature_flags.flag_key`) and configured enabled state. `FeatureFlagDefaults` inserts missing rows as disabled at startup and preserves existing values. Missing flags fail closed even before initialization.
- `Space.premium` stores the space entitlement independently of global switches. The SQL column is `boolean default false not null`, supported by H2 and PostgreSQL. Existing rows receive false when Hibernate adds the column; newly created spaces also default to false.
- This project uses Hibernate `ddl-auto=update`, with no versioned migration framework. B6 follows that existing architecture and adds a table and column without resetting records. Google OAuth2, datasource configuration, and existing booking rules are unchanged.
- `FeatureFlags` reads the four rows directly from the database. There is no feature-state cache to make a revoked privilege stale.
- `PremiumAnalytics` checks ownership, entitlement and effective flags before invoking `RoomPerformanceReads`, the separate cached aggregate component.

The existing persistent local H2 database location and PostgreSQL datasource overrides continue to apply. Do not run `create` or `create-drop` against application data. Tests use in-memory H2 or a new JUnit temporary file database, never the developer's local data.

## Supported flags

All four configured defaults are **false**.

| Key | Exact effect when enabled |
| --- | --- |
| `PREMIUM_FEATURES_ENABLED` | Master switch. Both discovery prioritization and advanced analytics require it. Disabling it retains entitlements and subordinate configured states. |
| `FEATURED_SPACES_ENABLED` | With the master switch, sorts entitled spaces before ordinary spaces in public discovery. Public badges also require this switch. |
| `ADVANCED_ANALYTICS_ENABLED` | With the master switch, allows the entitled space's owner or an admin to request room performance analytics. |
| `PREMIUM_BADGES_ENABLED` | With the master and featured switches, displays a textual Featured badge in React discovery and space details. Turning only this switch off hides badges while retaining featured ordering. |

`SpaceDto.premium` is the persisted entitlement; `featured` and `premiumBadge` are effective public presentation fields. Administrative and owner entitlement status remains visible when premium functionality is globally disabled so that the retained grant is clear.

## Discovery behavior

When featuring is effective, the database sorts by `premium DESC`, then the requested ordinary sort, then the unique space ID as a deterministic tie-breaker. Without featuring, the ordinary sort and ID apply. A caller cannot force premium ordering using `sort=premium` while the flag is off; that sort property is removed before applying the server's ordering policy.

Sorting occurs before SQL offset/limit. Exact `skip` values, including values not divisible by the limit, are retained. Both existing page/size and skip/limit response formats and next links remain supported. The legacy unpaged MVC listing is supported too.

Search and exact city filtering retain their existing semantics, including search taking precedence when both are provided. Featuring does not remove ordinary spaces or bypass filters. Map results, coordinates, map filtering, autocomplete, room listing and booking endpoints retain their existing behavior. Map markers and autocomplete suggestions are not premium advertisements.

## Additional analytics

The premium enhancement compares **every room in one space** over an inclusive selected date range:

- Confirmed bookings and cancelled bookings per room.
- Cancellation rate: cancelled / (confirmed + cancelled) × 100.
- Rooms with no bookings are included and the UI says “No bookings” instead of implying measured demand.

A single grouped database query uses a left join restricted by booking start time. The lower bound is the first day's midnight and the exclusive upper bound is the day after the selected end date. Cross-day bookings count once on their start date, consistent with standard analytics. The permitted range is 1–366 inclusive days within years 0001–9998. Times are the application's stored calendar times; there is no timezone conversion. Only aggregate counts and room names are returned, with no booking-user details.

The existing F1 Chart.js charts at `/analytics` continue to work for authorized owners/admins with no premium entitlement and with all premium flags disabled.

## API and authorization

| Endpoint | Requirement / response |
| --- | --- |
| `GET /api/admin/features` | ADMIN. All supported keys, descriptions and configured states. |
| `PUT /api/admin/features/{key}` | ADMIN, valid session CSRF token, JSON `{"enabled":true}` or false. Unknown key: 400; missing enabled: 400. |
| `PUT /api/admin/spaces/{id}/premium` | ADMIN, valid session CSRF token, same toggle body. Grants/revokes only that space. Unknown space: 404. |
| `GET /api/management/premium-features` | OWNER or ADMIN. Effective master, featured, analytics and badge states. |
| `GET /api/management/spaces` and `/{id}` | Existing ownership-scoped APIs, now include entitlement state. |
| `GET /api/analytics/spaces/{id}/room-performance?from=2026-10-01&to=2026-10-31` | Owned space for OWNER; any space for ADMIN. Requires entitlement and both analytics switches. |
| `GET /api/spaces` and `/{id}` | Existing public APIs, with effective presentation fields and featured discovery ordering. |

Mutations have ADMIN-only HTTP rules and service method authorization. OWNER and USER cannot grant premium, even by sending extra properties to existing space create/update APIs. Existing DTO mapping never copies a premium entitlement from those inputs. Ownership comes from the server-authenticated local identity and database relationships. Google claims and caller-submitted identities never grant roles.

Anonymous protected requests return 401. Unauthorized roles, foreign-owner analytics, and absent entitlement return 403. An entitled authorized caller receives 409 with an explicit feature-disabled explanation when the master or analytics switch is disabled. Invalid dates return 400. Missing CSRF tokens return 403. Existing session role refresh continues to enforce role revocation. Ownership is checked before returning analytics data, including cache hits.

## Caffeine consistency (B9)

Flag and entitlement writes publish `ReadDataChanged(SPACES)` within the write transaction. The existing after-commit listener invalidates all B9 read regions, including public space details and analytics. Failed or rolled-back writes do not publish an effective invalidation. Revisioned keys prevent a read started before a write from refilling a cache generation addressable by subsequent requests.

Discovery pages are not cached. Flag state and entitlement checks are not cached. Premium aggregates share the bounded, TTL-controlled `bookingAnalytics` Caffeine region using a distinct `premium-rooms` key prefix, space ID and date range. `PremiumAnalytics` performs its access checks **before** calling the cache, so warming it never bypasses a revoked flag, entitlement, role or ownership. Existing booking and room events invalidate premium aggregates as well as standard charts. The snapshot cache preserves premium fields on space DTO copies and accepts immutable room-performance results.

## Demonstration and manual browser checklist

These are verification steps to perform in a browser; no manual browser verification is claimed for this implementation.

1. Sign in with an existing ADMIN account and open `/admin`. Confirm four disabled flags on a fresh B6 database and visible entitlement status for paginated spaces. Cancel a confirmation and confirm no state changes. Enable all four flags and grant premium to one space. Confirm success feedback and retained state after a reload.
2. Open `/spaces` anonymously. Confirm the entitled space is featured ahead of normal spaces and has the Featured badge. Search by name/address and filter by exact city; follow pagination forwards/backwards, including filters. Confirm ordinary spaces remain reachable. Toggle the map and open a room/booking flow.
3. Disable only `PREMIUM_BADGES_ENABLED`: badges disappear after refreshing discovery, while featured ordering remains. Disable `FEATURED_SPACES_ENABLED`: ordinary ordering returns and public premium indicators disappear. Re-enable both.
4. Sign in as the entitled space's OWNER and open `/owner` and its space editor. Confirm Active status and advanced room performance. Select dates containing existing real bookings and compare confirmed/cancelled counts and empty rooms. The owner has no grant/revoke controls.
5. Open an unentitled owned space. Confirm an explanation directing the owner to an administrator and that standard `/analytics` charts remain available.
6. Warm the premium report, then as ADMIN revoke the entitlement, disable advanced analytics, or disable the master switch. Request the report again: expect 403 for revoked entitlement or 409 for disabled functionality; stale cached results must not be returned. Reload the owner view and confirm the explanatory state. Reassign ownership and confirm the former owner cannot access the report.
7. As USER or OWNER, try direct flag/entitlement PUT requests with a valid CSRF token: expect 403. Anonymous protected GET: 401. Try another owner's space ID: 403. Omit CSRF as ADMIN: 403.
8. Use keyboard navigation through confirmation, toggle buttons, date inputs and the results table. Verify visible focus, labels, textual states and announced success/error feedback. At mobile width and 200% zoom, verify wrapping controls, stacked forms and horizontally scrollable analytics tables.

### Verify persistence across backend restart

Use the normal persistent H2 or configured PostgreSQL application database. As ADMIN, save at least one enabled flag and grant a space premium, noting its ID. Stop and restart the backend with the same datasource settings, without changing schema generation settings. Reload `/admin` and the owned space: flags and entitlement must remain. Disable the master and restart once more: the master remains disabled while the entitlement is retained. No database reset or reseeding is needed. Automated B6 persistence coverage separately reopens a temporary H2 database and verifies a legacy row plus saved flag and entitlement.

## Automated verification

Run from `spotpulse-backend`:

```powershell
./mvnw.cmd test
./mvnw.cmd '-Dtest=PremiumFeatureTests,PremiumPersistenceTests' test
./mvnw.cmd '-Dtest=PremiumFeatureTests,PremiumPersistenceTests,SpaceLandingTests' test
node --test src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s tools -p test_geocode_space.py
```

Run from `spotpulse-frontend`:

```powershell
npm test
npm run build
```

The build command includes `tsc --noEmit` before Vite production bundling. On this Windows machine the installed Maven 3.9.12 executable was used with `-o` and `-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository`; this uses the existing dependency cache. Maven/Vite needed execution outside the restricted filesystem sandbox. No dependencies or OAuth configuration were changed.

Actual results (2026-10-30):

| Check | Result |
| --- | --- |
| Dedicated B6 backend | 12 passed; 0 failures, errors or skips |
| B6 plus landing regression after query-budget fix | 20 passed; 0 failures, errors or skips |
| Full backend regression | 145 passed; 0 failures, errors or skips, including OAuth2, booking, cache and three-process restart regressions |
| Full React suite | 65 passed across 6 files; includes 13 B6 scenarios |
| TypeScript and production build | Passed |
| Existing JS autocomplete suite | 8 passed |
| Existing Python geocoding suite | 5 passed |

B6 backend tests cover default states, persistent writes, startup default preservation, ADMIN mutation, USER/OWNER denial, CSRF, unknown keys, ownership, absent entitlement, both disabled gates, inclusive real aggregates, empty rooms, deterministic filtered pagination, warm-cache invalidation, revoked access, booking/room invalidation and attempted self-grant through legacy APIs. The temporary-file persistence test also verifies additive upgrade of a pre-B6 space row and saved values after database reopen. Existing OAuth2 and booking regressions run in the full suite.

React tests cover confirmed and cancelled ADMIN changes, grants/revocations, CSRF requests, error feedback, featured/ordinary server order, badges, entitled and unentitled OWNER views, selected dates, disabled-feature behavior, and 401/403/409 handling.

The first full backend run exposed an extra flag query on ordinary space details. The implementation now skips that irrelevant lookup for non-premium spaces, preserving the existing two-query rendering budget. The targeted 20-test rerun and final 145-test full run both passed. Final execution logs are retained locally under `target/b6-verification/` (ignored build artifacts).

## Known limitations

- No manual browser, mobile-device or screen-reader run has been performed. Use the checklist above before presenting.
- PostgreSQL mappings remain supported, but this verification uses H2; no PostgreSQL server was exercised.
- Open pages refresh their data on navigation/reload, explicit retry or date submission; there is no server-push mechanism to remove already rendered data from another browser tab. Every new backend request enforces the current access state.
- Caffeine invalidation is local to the existing backend process. A multi-instance deployment would need cross-instance invalidation for public presentation caches. Flag and analytics access checks still read the database.
- Entitlements have no purchase, expiry, subscription, billing or audit-history system. This is an explicit administrator-managed demonstration tier.
- Analytics compares booking counts and cancellation rates, not occupancy hours or room opening schedules, which the existing model does not store.

B6 is implemented and ready for an administrator/owner demonstration with all automated checks passing. The browser checklist and optional PostgreSQL verification remain unperformed.

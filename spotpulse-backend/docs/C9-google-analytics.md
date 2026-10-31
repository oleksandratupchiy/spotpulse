# C9 — Google Analytics 4

## Scope and architecture

The React SPA uses Google's lightweight `gtag.js` integration through `usageAnalytics.ts`, separate from F1's existing Chart.js business statistics. No dashboard, backend analytics endpoint, OAuth change, API Secret or new npm dependency is introduced. `AnalyticsConsent` observes React Router pathname changes and owns the accessible preferences UI. Explicit action handlers report search and confirmed booking/cancellation actions.

The integration is opt-in and inactive when `VITE_GA_MEASUREMENT_ID` is absent or invalid. The accepted format is exactly `G-` followed by ten uppercase letters/digits. This is a **public web Measurement ID**, not a Google OAuth client ID or a Measurement Protocol API Secret. No real ID is committed.

Thymeleaf `/spaces` and `/spaces/{id}` SEO pages remain uninstrumented. Their metadata, canonical URLs, structured data and sitemap are unchanged. The SPA at `/app` is C9's scope; local Vite routing still works at `/`.

## Create the GA4 property and stream

1. In Google Analytics, create/select an Analytics account and a standard GA4 property for SpotPulse. Choose the appropriate reporting time zone and complete the basic business settings. No paid Analytics 360 subscription is needed.
2. In Admin → Data streams, create a **Web** stream for `https://spotpulse.onrender.com`, named for example SpotPulse Web. Copy the `G-…` Measurement ID.
3. **Turn Enhanced Measurement off for this stream**, including browser-history page views, automatic site search, form interactions, outbound clicks and other automatic events. This app manually controls page views and safe event parameters; automatic URL/form capture could duplicate views or capture private data. Keep automatic user-provided-data collection, Google Signals and advertising personalization disabled; do not connect another tag/GTM snippet to this integration.
4. Do not paste Google's normal unconditional script snippet into `index.html` or Thymeleaf. The application loads the one tag only after consent. Do not create an API Secret or configure Google OAuth credentials for C9.

Google's [property/web-stream setup guide](https://support.google.com/analytics/answer/9304153) describes account and stream creation. Google's [manual page-view guide](https://developers.google.com/analytics/devguides/collection/ga4/views) explains why automatic views must be disabled before manual SPA measurement.

## Build-time configuration: local, Docker and Render

Vite embeds `VITE_GA_MEASUREMENT_ID` into compiled JavaScript. It cannot read a newly added backend runtime environment variable from an already compiled bundle.

For ordinary local development, set the public ID in an ignored frontend `.env.local` or launching shell, then restart Vite. Leave it blank for normal local work. `.env.example` documents the optional variable. Do not put Google OAuth/Cloudinary/Telegram secrets in any `VITE_*` value.

The root production Dockerfile declares **only this public value** in the frontend stage:

```dockerfile
ARG VITE_GA_MEASUREMENT_ID=""
RUN VITE_GA_MEASUREMENT_ID="$VITE_GA_MEASUREMENT_ID" npm run build:deployment
```

For a local image build, explicitly pass the public ID:

```powershell
# Replace the placeholder with the actual public web Measurement ID; never use an API Secret.
docker build --load --build-arg VITE_GA_MEASUREMENT_ID=G-XXXXXXXXXX --file Dockerfile --tag spotpulse:analytics .
```

On the existing Git-connected Render Docker service:

1. Add `VITE_GA_MEASUREMENT_ID` with the actual public ID in the service's Environment settings.
2. Use **Save, rebuild, and deploy** when deliberately ready, after reviewing CI. Do not choose only a runtime restart/redeploy of an unchanged image. Render translates service environment variables into Docker build arguments; the explicit `ARG` above consumes this public value during the Node/Vite stage. See [Render Docker environment translation](https://render.com/docs/docker).
3. Verify that the rebuilt deployment contains the intended public ID and that `/app` displays Analytics preferences. Changing/removing the ID requires another frontend/image build. Removing it disables this integration after the rebuilt image is served.

The argument is intentionally public and may appear in build metadata/bundles. **Never pass OAuth secrets, database credentials, Cloudinary secrets or Telegram tokens through Docker build arguments.** Existing backend-only runtime credential configuration is unchanged. No service, GA property, environment value or deployment was created/changed by this task.

## Consent, privacy and lifecycle

Before affirmative consent there is no GA script, no `gtag` initialization, no analytics data layer and no analytics/cookieless-denial ping from this code. Necessary session cookies and Google Login continue independently. Choice is stored under `spotpulse.analytics-consent.v1` in localStorage, not in an account or analytics payload. A returning accepted choice permits loading; rejection prevents it. Storage failure applies the current choice in memory. Clearing site storage prompts again.

Accept and Reject are visible keyboard-accessible buttons, and Analytics preferences remains available on SPA pages to change the choice. Cross-tab storage changes synchronize the choice. GPC, Do Not Track and a pre-existing `ga-disable-<ID>` browser/extension flag override acceptance. Privacy signals are checked at initialization, dispatch and visibility resume.

Withdrawal sets Google's documented `ga-disable-<ID>` flag before further events, clears pending application events and prevents new application dispatch. It does not reload the page, lose a form, delete JSESSIONID or interfere with OAuth. A previously loaded script remains in the document, disabled, so reacceptance does not inject duplicates. Already transmitted data cannot be recalled by an opt-out; previously created optional `_ga` cookies are not deleted by this UI and can be cleared through browser site settings. Analytics cookie lifetime is configured to 30 days. See Google's [disable/privacy parameters](https://developers.google.com/tag-platform/security/guides/privacy).

Ads storage, advertising user data and ad personalization consent stay denied. Google Signals and ad-personalization signals are disabled in configuration. The loader uses `referrerPolicy=no-referrer`. Every emitted event supplies a fixed title, empty page referrer and synthetic page location, rather than reading a full URL, query or document title. Never add a second GA/GTM snippet or re-enable automatic form/search/history measurement on the property.

This is coarse, pseudonymous usage measurement, not a promise of absolute anonymity: after consent Google's SDK can create its own analytics client/session identifiers and process IP/device/network metadata. Application user/booking identifiers, session cookies, OAuth tokens, names/emails and exact reservation dates are not passed to GA. Browser privacy extensions or network policies can block the script, reducing observed usage. Script failures are contained, pending application events are bounded to 20, and there is no automatic retry loop. Reload after resolving a blocked tag if measurement is intentionally wanted; do not bypass a user's privacy choice.

## Page labels and custom events

| SPA navigation | Reported synthetic path |
| --- | --- |
| `/spaces` | `/usage/catalog` |
| `/spaces/<id>` | `/usage/space_detail` |
| `/rooms/<id>` | `/usage/room_detail` |
| `/login` | `/usage/login` |
| Account, management, bookings, analytics, unknown routes | No page_view; event context uses `/usage/private` |

The page location is the current origin plus the synthetic path. Neither numeric IDs nor queries/fragments are sent. `/app` is handled by BrowserRouter's existing basename, so local/deployed navigation uses the same labels. Pathname visits deduplicate StrictMode/rerenders; different detail visits can each count without sending their IDs. Query/typing changes do not create page views. `send_page_view=false` prevents the default initial view; the required stream settings above prevent automatic history duplicates. An acceptance counts only the current page, not earlier browsing/actions.

| Event | Trigger | Additional parameters |
| --- | --- | --- |
| `page_view` | One allowed public SPA route visit | Only fixed safe page fields |
| `space_view` | Successfully loaded space details, once per visit | Only fixed safe page fields |
| `space_search` | Explicit Search form submission, not keystrokes | `search_mode=text|city`, `query_present=true|false`; never search text/city value |
| `booking_success` | Backend returns a confirmed booking | No booking/user/room IDs, calendar times or prices |
| `booking_cancelled` | Backend confirms CANCELLED | No identifiers or schedule details |

Action events are called from successful action handlers, not render effects. HTTP errors and validation failures never emit success. Existing pending/disabled UI behavior prevents repeated submissions; route/space events also deduplicate per visit. Events are best-effort usage signals, not an authoritative booking ledger. No events are replayed for actions performed before consent.

## Automated verification

React tests mock/inspect the gtag command queue, including missing/invalid/valid IDs, consent changes, unavailable storage, GPC/DNT/opt-out flags, safe routes, private pages, StrictMode deduplication, blocked scripts, fixed event payloads and success/failure action handlers. Vitest forces the global measurement ID blank, even if the launching shell has an actual ID.

The isolated QA2 preparer builds with **synthetic** `G-TEST000000`, stripping inherited `VITE_*` values. Existing Playwright fixtures block external requests. The C9 browser tests fulfill the tag script locally with an inert mock: no Google SDK, collection request or real account receives test data. They exercise actual `/app` navigation, consent changes, search/detail actions and a real isolated booking/conflict/cancellation journey.

```powershell
# spotpulse-frontend
npm test
npm run e2e:typecheck
npm run build:deployment
npm run e2e:prepare
npm run e2e
# Repository root; synthetic public ID only, no app startup/deployment
docker build --load --build-arg VITE_GA_MEASUREMENT_ID=G-TEST000000 --file Dockerfile --tag spotpulse:c9-verify .
```

The CI frontend job retains the full React/type/build checks. Its existing E2E job runs all original and C9 scenarios with offline analytics. The Docker job now explicitly passes the synthetic public build argument. Existing gating, OAuth/security checks and deployment behavior are preserved; no CI secret is added.

Actual results on Windows / Node 24.21.0 / Java 25 / Python 3.12.0:

| Verification | Executed result |
| --- | --- |
| Full React suite | **119 passed in 13 files**, zero failures/skips; **87.02 seconds**. Includes existing OAuth/session, premium, maps, images and availability tests plus C9 coverage. |
| Application and E2E TypeScript | **Passed**. An initial missing test-only Window declaration was corrected and the E2E check rerun successfully. |
| Production Vite build | **Passed**, 65 modules, **11.74 seconds**, with the synthetic public ID in the isolated preparation workflow. An earlier ordinary production build also passed. |
| Isolated backend preparation | **Passed**, Maven **50.123 seconds**; no production database or external-account settings. |
| Full Playwright suite | **16 passed**, zero failures/skips/retries; **3.5 minutes**. Three new offline-GA4 scenarios plus all thirteen QA2 scenarios. Backend/storage cleanup completed. |
| Production Docker build | **Passed**, including frontend TypeScript/build and Java 25 compilation/package. The local docker-container builder first retained the result in cache; a cached `--load` build successfully exported the image. |
| Docker runtime/artifact | **Passed**: Temurin **25.0.5**, user **10001:10001**. The extracted JAR contains `G-TEST000000` and the consent loader in real SPA assets, with no private test fixtures or test-only login classes. The inspection container was removed; Spring Boot was not started with production credentials. |
| Workflow validation | **Actionlint 1.7.12 passed**, with optional shellcheck/pyflakes unavailable. Existing job/deployment gating remains. |
| Utilities | F2 **8 passed**, **1,416.6669 ms**; offline C8 **5 passed**, **2.538 seconds**; Python preparation helper compiled successfully. |

The first browser startup attempt timed out while the first Docker image pulls competed for resources; no browser scenarios ran in that attempt. The failed JVM was confirmed exited, its verified empty temporary directory/stale marker were removed, and the suite was rerun after Docker completed. The final sixteen-scenario run passed without changing its startup/test timeouts, disabling tests or adding retries. Initial sandbox Vite/Docker access errors were rerun with the required execution permission.

No backend application Java/security/controller code changed, so the full backend JUnit suite was not rerun for C9. The backend was compiled in Docker and in isolated preparation, and real authorization/CSRF/booking paths were exercised by Playwright. No live GA4 delivery, GA property access, public Render browser smoke or hosted GitHub run is claimed. No commit, push or deployment occurred.

Changed files: `usageAnalytics.ts`, `AnalyticsConsent.tsx`, small action hooks in `App.tsx`/`Management.tsx`, React tests, `e2e/analytics.spec.ts`, `.env.example`, Vitest's offline analytics default, the QA2 preparer's synthetic ID, the root Dockerfile/public build argument, its CI build invocation and this document. README and generated `bin/` files were not modified.

## Realtime, DebugView and instructor demonstration

After the owner completes stream configuration, reviews/pushes and deliberately deploys a rebuilt image:

1. Open `/app/spaces` in a fresh browser context. Before consent, DevTools Network must show no `gtag/js` or GA collection requests. Reject and verify the same; necessary login/session behavior must still work.
2. Open Analytics preferences and accept. Expect one tag loader. Navigate catalog → a space → a room, and explicitly submit Search. Check GA requests to `google-analytics.com/g/collect` or `analytics.google.com/g/collect`: event names and synthetic `/usage/...` locations must contain no IDs, query text, account details or reservation calendar values.
3. Open the correct property's **Realtime** report and inspect `page_view`, `space_view` and `space_search`. Counts cover consenting, unblocked clients only and are not a complete visitor census. Reporting may lag.
4. For **DebugView**, use Google's Tag Assistant for your own browser after consent. Do not add query parameters or real-user information to event payloads or enable a debug flag globally for all visitors. Inspect one device's sanitized events, then stop the debugging session. See Google's [verification/troubleshooting guide](https://developers.google.com/analytics/devguides/collection/ga4/troubleshoot) and [DebugView guide](https://support.google.com/analytics/answer/7201382).
5. Reject again and navigate: no further application analytics events should be sent. A tag already loaded can remain visible in Elements while disabled. Do not mistake retained historical Realtime data for new opt-out events.
6. Demonstrate booking success, conflict and cancellation via the **isolated Playwright scenario**, showing mocked commands and real isolated HTTP results. Do not create/cancel production records just to produce C9 evidence. If live booking-event verification is required, use a separate disposable demonstration environment/property.

For evidence, capture the build configuration **name/public ID only**, consent UI, redacted Network event parameters and the property's Realtime/DebugView result. Never include OAuth/session cookies, tokens, personal account information or backend credentials. Manual screenshots and actual GA account events remain the owner's steps.

## Limitations and readiness

SPA only; server-rendered SEO pages are excluded. Stream Enhanced Measurement/user-provided-data settings are a mandatory operator setup step. Consent preferences persist in the current browser until changed/cleared and are not synchronized between devices/accounts. Blocked analytics events are lost; the 20-command startup buffer is intentionally bounded. No automatic reload/retry, dashboard or server-side backfill is added. Live delivery cannot be claimed without a real configured property, deliberate rebuild/deployment and verification in that property.

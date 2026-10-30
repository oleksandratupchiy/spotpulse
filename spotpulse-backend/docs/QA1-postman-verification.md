# QA1 — Postman API testing and verification

## Collection and execution architecture

The existing root `postman_collection.json` remains the importable Postman v2.1 collection, retaining its name, identifier, four original CRUD sections and all 25 original request names. It now has 88 requests in nine independently seeded folders. Requests and assertions reflect current controllers, DTOs, RBAC, ownership and session CSRF. Offset queries use `skip/limit` and assert `items/skip/limit/total/nextLink`; a separate legacy `page/size` test asserts `content/totalElements`.

`tools/postman` is a separate, private Node testing package with pinned Newman and its own lockfile. Newman is not a frontend/application dependency. `prepare_api_tests.py` compiles the existing QA2 test-only backend and dependency classpath without requiring a React build. `verify-postman.cjs` starts the QA2 supervisor on fixed port **18091**, waits for UP health (bounded five-minute cold-start timeout), runs the collection twice, writes sanitized evidence and shuts down the owned backend in a `finally` block. QA1 has its own `target/qa1` logs/control files; default QA2 behavior and `target/qa2` are preserved.

## Isolation and authentication

The only allowed collection target is `http://localhost:18091`. Both runner preflight and collection pre-request scripts reject Render, developer port 8080, malformed origins and unrelated request URLs. The runner accepts no target argument, refuses an occupied port/active QA1 runtime, disables inherited HTTP proxies and redirects, and strips backend/provider configuration before spawning the supervisor. Never change this collection to point at `https://spotpulse.onrender.com`, a persistent local application or a real database.

The reused Java bootstrap is `src/test/java/com/spotpulse/e2e/E2eApplication.java`, absent from the production JAR. It binds loopback, selects `e2e`, creates a unique `jdbc:h2:mem:e2e-<UUID>` database and verifies its owned OS-temporary image/multipart directory. Telegram is disabled, image storage is local and Google settings are synthetic. Real Google, Telegram, Cloudinary and PostgreSQL credentials are unnecessary and unused. No production authentication settings/code are changed.

The existing test-only reset/login facility requires `e2e & !prod & !local`; ordinary local development and production cannot enable it. Collection scripts first obtain `/api/auth/csrf`, reset only the disposable fixture and establish the requested synthetic actor using `/__e2e/login/{user|owner|other|admin}`. The resulting session is a real `AppOidcUser`/OAuth2 authentication token saved in the real HTTP session. Application requests use unchanged Spring Security, ownership checks and CSRF. Anonymous scenarios use real logout. Fresh masked CSRF tokens are obtained after session transitions, and cookies remain in Newman's/Postman's in-memory cookie jar. Missing/invalid-token scenarios deliberately omit/corrupt only the tested request's header.

No bearer token, password, cookie, OAuth secret or CSRF token is stored in collection/environment files. Collection variables carry generated IDs, fixture dates, pagination links and scenario markers. Admin-created legacy user records are tested as supported CRUD; they do not become Google-authenticated users. Login uses only the fixed synthetic accounts.

## Scenarios and deterministic setup

| Folder | Requests | Coverage |
| --- | ---: | --- |
| Public | 14 | Health, spaces/rooms/details, search, city filter, offset next-link navigation, legacy pagination, available/occupied intervals, privacy-safe DTOs, invalid range/pagination, 404 |
| Spaces | 6 | OWNER create/read/update/delete and post-delete 404 |
| Users | 6 | ADMIN user CRUD, response fields and post-delete 404 |
| Rooms | 9 | Owned parent creation, room CRUD, space filtering, post-delete 404 and parent cleanup |
| Bookings | 12 | Owned space/room setup, ADMIN creation on behalf of a legacy record, booking CRUD and dependent cleanup |
| Anonymous boundaries | 7 | Anonymous profile, protected GET/mutation 401, missing-CSRF 403 |
| USER boundaries and booking lifecycle | 19 | USER role, management/admin/premium denial, missing/invalid CSRF, validation 400, successful own booking, overlap 409, committed occupancy, other-owner cancellation denial, admin mutation denial, own cancellation, availability release and logout |
| OWNER ownership and validation | 9 | Own-only management list, cross-owner read/update/delete and room denial, admin/premium denial and invalid forms |
| ADMIN permissions and final cleanup | 6 | Platform management visibility, another owner's space read/update, feature flags, invalid user input and final reset |

Each folder's first request resets eight fixture spaces/rooms, four synthetic accounts, feature flags and a known occupied interval seven days ahead. Mutable IDs are cleared; IDs and times are read from the returned fixture rather than assumed to begin at one. Each subsequent request explicitly establishes its actor and fresh CSRF. CRUD steps intentionally pass generated IDs between requests. Run a complete folder from its first request, or the whole collection, rather than an arbitrary dependent CRUD request. Folders do not depend on each other or on earlier runs. Running two iterations tests repeat initialization, unique-email cleanup and session transitions.

There is no bail-on-first-assertion-failure: later independent folders and final reset can still execute. Regardless of assertion failure, the automated runner stops its own Java process and checks temporary-directory cleanup. A forced OS termination/power loss can leave an owned `spotpulse-e2e-*` temporary directory; it cannot make the in-memory database persistent. Never delete arbitrary directories to clean up a failed run. Run Maven preparation/builds before tests, not concurrently against the shared `target` classpath.

## Exact commands

Prerequisites: Java 25, Node 24, Python 3.12+ and Maven-wrapper access. From the repository root:

```powershell
npm --prefix tools/postman ci --include=dev --ignore-scripts --no-audit --no-fund
npm --prefix tools/postman run test:safety
npm --prefix tools/postman run prepare:backend
npm --prefix tools/postman test
```

The automated command owns its backend; do not start a manual backend on port 18091 at the same time. It runs two iterations with finite request/script timeouts, no production environment file and no exported cookie jar. Readiness polling and process-cleanup polling are bounded condition checks, not fixed startup sleeps.

## Sanitized evidence

After an executed collection run:

- `spotpulse-backend/target/qa1/summary.json`: timestamp, collection SHA-256, iterations, primary collection request count, observed HTTP request count including scripted setup, assertion totals/passes/failures, duration, and allowlisted per-request method/status/assertion names/results.
- `spotpulse-backend/target/qa1/report.html`: standalone offline HTML with the same counts and per-request status table; no external CDN assets.
- `target/qa1/backend.log`: local diagnostic log, not an evidence upload.

Raw Newman JSON reports, collection runtime variable exports, cookies, CSRF headers, response bodies, private account data and raw exception messages are deliberately excluded. Evidence generation uses an explicit allowlist and has automated leak/HTML-escaping tests. Generated output is ignored by Git. Copy only the two sanitized reports for submission, after reviewing them. Old reports are removed at the start of a new automated run so a startup failure cannot leave a misleading old passing report.

HTTP setup requests and their assertions are real work, so the report distinguishes main collection requests from all observed HTTP requests. Newman stores helper responses in shared per-item execution objects; the exporter deduplicates those aliases and freezes the primary response before post-response scripts run. A regression test verifies that a logout-verification GET cannot overwrite the reported POST/204. Reports are written only after runtime cleanup succeeds. Report duration covers Newman execution, including setup; backend compilation/startup/shutdown are outside that duration. Exported counts come from the actual run, not hardcoded expectations.

## Manual Postman Desktop import/run

1. Prepare the backend using the commands above, but do not run the automated `npm test` supervisor concurrently.
2. In a separate terminal at repository root, start the disposable backend:

   ```powershell
   $env:SPOTPULSE_E2E_PORT='18091'
   $env:SPOTPULSE_E2E_SUITE='qa1'
   python spotpulse-backend/tools/run_e2e.py
   ```

3. Wait until `http://localhost:18091/health` returns only `{"status":"UP"}`. Consult `target/qa1/backend.log` if startup fails; do not switch to the normal application as a workaround.
4. Import root `postman_collection.json` into Postman Desktop. Use the local desktop agent, not a cloud monitor. Keep collection variable `baseUrl=http://localhost:18091`; remove any overriding environment/global/data variable. Select No environment and no collection Authorization header.
5. Enable the normal cookie jar, bypass proxies for localhost and turn redirect following **off**. Do not paste real cookies/tokens. Scripts obtain CSRF and switch synthetic sessions automatically. Use a Postman version supporting `pm.execution.skipRequest`; the collection remains v2.1 for Newman compatibility.
6. Open Collection Runner, select all folders in their saved order and run one or two iterations. A complete individual folder can also be rerun independently. Keep the original request order within a CRUD folder.
7. Review the Runner test results and response DTOs. Do not export the cookie jar, Postman Console headers or a runtime environment. To capture evidence, show the collection folders, local base URL, Runner totals, representative successful CRUD and expected 400/401/403/404/409 responses. Redact session/CSRF headers and cookie values.
8. Stop the disposable backend normally with Ctrl+C and wait for shutdown. The automated runner instead performs shutdown and checks cleanup itself. If forced termination leaves `target/qa1/active`, inspect the owned runtime before removing a stale marker; never stop unrelated developer processes.

Postman Desktop import, clicks and screenshots are manual steps and are not claimed as completed by a Newman run. See Postman's official [collection execution and skipRequest documentation](https://learning.postman.com/docs/tests-and-scripts/write-scripts/postman-sandbox-reference/pm-execution) for the sandbox safety mechanism.

## CI integration

The new check is **Postman API (QA1)**: Java 25/Node 24/Python 3.12 setup, independent test-package `npm ci`, safety tests, sanitized backend compilation and Newman verification. It requires no real secrets. Only `target/qa1/summary.json` and `report.html` are uploaded for seven days, including assertion-failure evidence when present; backend logs and raw authentication state are excluded.

All existing checks remain. **Production Docker image** also depends on Postman, so a failed API job cannot pass that downstream check. Add **Postman API (QA1)** to GitHub required branch checks alongside the five existing checks, and retain Render native **After CI Checks Pass** gating. No deploy hook, push or deployment is performed. A local passing run does not claim a hosted GitHub run or successful production deployment.

## Actual executed results

Executed on Windows with Java 25, Node 24.21.0, Python 3.12.0 and Newman 6.2.3. Final collection SHA-256: `c7e2c46a37e80b6ad61179edde36560e10a876f5bbc0b97033d516b5e615e8b0`.

| Check | Actual result |
| --- | --- |
| Final Newman run | **2 iterations; 176 primary requests; 918 observed HTTP requests including setup** |
| Assertions | **1,350 total; 1,350 passed; 0 failed; 0 run failures** |
| Newman execution duration | **53,727 ms (53.727 seconds)**, excluding build/backend lifecycle |
| Fixture/backend cleanup | **Passed**, process exited 0; exported `cleanupCompleted=true`; no active marker remained |
| Evidence verification | All **176** primary methods/statuses matched their declared tests; assertion sum **1,350**; JSON allowlist and offline HTML parsing passed |
| Safety unit tests | **9 passed**, 0 failures/skips, **13.152 seconds** |
| Full backend `mvnw -B -ntp verify` | **246 passed**, 0 failures/errors/skips; Maven **8:26 minutes**, including existing RBAC/CSRF/ownership, booking/concurrency, images, Telegram, caches, availability and three QA2 isolation tests |
| API-only preparation | **Passed**, Maven **27.267 seconds**, with sanitized provider/datasource environment |
| F2 autocomplete | **8 passed**, **742.1062 ms** |
| C8 offline geocoding | **5 passed**, **1.493 seconds** |
| CI/helper validation | Actionlint **1.7.12** passed (optional shellcheck/pyflakes unavailable); Python `py_compile` passed; QA1 changes passed whitespace checks |
| Production artifact | Rebuilt JAR inspected: **no test-only E2E/login classes** |
| QA2 browser regression | **13 Chromium scenarios passed**, no failures/skips/retries, **2.1 minutes**; supervisor cleanup completed in QA2 namespace |

One early startup attempt hit the old readiness deadline before any API assertions ran; it is not counted as a passing verification. Preparation completed and two later complete runs passed all 1,350 assertions. The prototype exporter initially included helper aliases in its primary-request count; it was corrected using item statistics, deduplication and frozen primary-response snapshots, with a dedicated regression test. Only the corrected final report is submission evidence. The safety tests also intentionally reject a miniature unsafe-target collection and assert zero HTTP sends; that expected rejection is separate from the successful 1,350-assertion API run. No API assertion was disabled or silently skipped.

Postman Desktop import/Runner clicks/screenshots have **not** been performed. Neither has a hosted GitHub Actions run. Frontend source/package files are unchanged by QA1; no claim is made that this task reran the full React component suite. No public Render, real provider or production PostgreSQL verification was performed.

Automated QA1 verification is ready for review, commit and its first hosted CI execution. The manual Postman evidence checklist above remains for the student to perform. No commit, push or deployment was triggered.

## Changed files

Root collection; `tools/postman/{package.json,package-lock.json,.gitignore,verify-postman.cjs,postman-safety.test.cjs}`; backend `tools/prepare_api_tests.py`; the shared supervisor's bounded QA1/QA2 output namespace; `.github/workflows/ci.yml`; and this document. Production authentication/controllers are unchanged. README, generated `bin/`, persistent application data and unrelated working-tree edits are left untouched. No commit, push or deployment was performed.

## Limitations

The synthetic login tests application sessions/authorization, not Google consent or token exchange. Real Render, PostgreSQL, Cloudinary, Telegram and browser rendering are deliberately outside this collection; QA2 covers browser journeys. The collection is serialized, not a load/concurrency benchmark. Local HTTP cookie behavior does not prove Render HTTPS/TLS cookie behavior. No real customer or production record is used.

Pinned Newman 6.2.3 currently reports 19 transitive npm advisories (8 moderate, 10 high, 1 critical). npm's proposed fix is an incompatible downgrade; it has not been forced. Newman is isolated in this private testing package, excluded from the frontend/application/Docker build context, and runs only reviewed scripts against disposable local data without account secrets. Do not run arbitrary third-party collections or untrusted responses/scripts with this toolchain. Review upstream updates before reuse; these advisories are not misreported as resolved.

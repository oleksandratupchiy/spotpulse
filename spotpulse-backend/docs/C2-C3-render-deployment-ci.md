# C2 + C3 — Render deployment and GitHub Actions

## Status and deployment architecture

This change prepares deployment; it does **not** create a Render service, database, paid plan, GitHub setting or Google OAuth client. No commit, push or deployment was performed. **C2 is not complete until an actual public HTTPS deployment passes the smoke checklist below.** The CI workflow is implemented and locally validated; a real GitHub run and Render integration still require the repository owner's actions.

Use one Docker-based Render Web Service and one Render PostgreSQL database. React is served by Spring Boot at `/app`, REST remains at `/api`, and existing Thymeleaf SEO pages remain at `/spaces` and `/spaces/{id}`. An explicit SPA route list avoids rewriting API, OAuth, WebSocket, sitemap or static-resource requests into HTML. This resolves the existing React/Thymeleaf `/spaces` route overlap without removing completed SEO functionality. `/` redirects to `/app/spaces`.

```mermaid
flowchart LR
    U[Browser HTTPS] --> R[Render TLS edge / reverse proxy]
    R --> W[One non-root Spring Boot web service]
    W --> A[/app React SPA and hashed assets]
    W --> API[/api REST + CSRF + OAuth2 session]
    W --> SEO[/spaces Thymeleaf + robots + sitemap]
    W <-->|same-origin WSS| WS[Read-only availability clients]
    W --> PG[(Render PostgreSQL)]
    W --> C[Cloudinary authenticated upload]
    U -->|public image URL| CDN[Cloudinary CDN]
    W -. optional one poller .-> T[Telegram Bot API]
    GH[GitHub main] --> CI[Actions: backend / React / utilities / Docker]
    CI -->|user enables native CI gating| RD[Render rebuild + health check]
```

There is no separate Static Site, Redis, background worker, deploy-hook workflow or extra paid infrastructure. Sessions and broadcasts remain process-local; choose one web instance. PostgreSQL persists application records and Telegram polling state across web-service restarts, while Cloudinary persists uploaded images. Neither H2 files nor Render's container filesystem is a durable cloud datastore.

## Exact build and runtime

The **repository-root `Dockerfile`** is the production entry point. The existing backend-only `spotpulse-backend/Dockerfile` and Docker Compose setup remain available for their previous local workflow.

1. `node:24-bookworm-slim` runs `npm ci` from the committed lockfile, then `npm run build:deployment`. That runs TypeScript checking and Vite with `base=/app/`.
2. `eclipse-temurin:25-jdk` uses the committed Maven wrapper, resolves dependencies, copies only backend production sources and the frontend build, then packages with the `spa` Maven profile. That profile copies `spotpulse-frontend/dist` into `static/app` inside the JAR. The build checks that the packaged index exists.
3. `eclipse-temurin:25-jre` receives only the runtime JAR. It runs as UID/GID **10001:10001**, with no Node/Maven/JDK build stage copied into the final image. The JVM heap uses container-relative limits, leaving memory for native/runtime work.
4. The image defaults to `SPRING_PROFILES_ACTIVE=prod`. The production server binds `0.0.0.0` on `${PORT:8080}`. Docker exposes only port 8080 as metadata; Render's runtime PORT determines the actual listener. No secrets are Docker build arguments.

The root `.dockerignore` denies the build context by default and admits only required source/build inputs. It excludes Git metadata, `.env` files, keys, databases, logs, generated output, `node_modules` and tests. Maven wrapper LF handling supports Linux builds from Windows checkouts.

Build with a working Docker daemon:

```powershell
# Repository root
docker build --file Dockerfile --tag spotpulse:render .
```

Equivalent local artifact verification, without Docker:

```powershell
# spotpulse-frontend
npm ci
npm test
npm run build:deployment
# spotpulse-backend
.\mvnw.cmd -B -ntp -Pspa clean verify
python tools/verify_spa_jar.py
```

Do not upload a locally built JAR containing test-classpath resources. The actual production JAR includes main classes/resources and compiled frontend assets, not `src/test` fixtures. `tools/verify_spa_jar.py` compares every SPA file against the JAR, rejects the test index, and starts the actual artifact outside any Git checkout on a random loopback port. It strips inherited provider/datasource settings, uses only an explicitly configured in-memory H2 database and owned temporary directories, and verifies health, forwarded HTTPS, deep links, real static assets and API/Thymeleaf routing before stopping the process. It does not use real accounts or external APIs. Packaging review also corrected the legacy local-image guard's no-checkout fallback: a runtime image without `.git` must protect its working directory, rather than treating the entire filesystem as a checkout.

Ordinary local Vite development remains `npm run dev` on port 5173; its API/WebSocket proxy is unchanged. Ordinary `npm run build` still supports a root-based Vite preview. Select `build:deployment` only for the `/app` deployment layout.

## Production profile and startup validation

`application-prod.properties` and `ProductionEnvironment` separate production from the unchanged local default. Validation happens before datasource/Hibernate initialization. Production requires Google and Cloudinary configuration, a PostgreSQL connection and a public HTTPS origin. It rejects local image storage, H2, destructive schema modes, SQL/debug/trace logging, insecure cookies, mismatched origins/callbacks and combining `local` with `prod`. Error messages name settings without echoing credential values or retaining parse exceptions containing credential URLs.

When `SPOTPULSE_PUBLIC_BASE_URL` is absent, the public origin comes from Render's actual `RENDER_EXTERNAL_HOSTNAME`; nothing invents the final service hostname. That origin sets the frontend origin, SEO base, exact WebSocket Origin and Google callback. An explicitly configured public base must be a single HTTPS origin without credentials, port, path, query or fragment. If the existing explicit origin/callback variables are set, they must exactly match the derived origin/callback.

Render documents its assigned hostname and runtime port in [default environment variables](https://render.com/docs/environment-variables). Leave those platform values to Render; copy the actual public URL from the service dashboard after creation.

### Required Render web-service environment

Configure these in the **web service's Render Environment settings**, not in GitHub Actions or frontend variables:

| Variable | Value/source |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `prod` (also the production image default) |
| `DATABASE_URL` | This database's **Internal Database URL**, copied privately from Render PostgreSQL |
| `GOOGLE_CLIENT_ID` | Existing Google Web application OAuth client ID |
| `GOOGLE_CLIENT_SECRET` | Corresponding backend-only Google client secret |
| `SPOTPULSE_IMAGE_STORAGE` | `cloudinary` |
| `CLOUDINARY_CLOUD_NAME` | Cloudinary product environment |
| `CLOUDINARY_API_KEY` | Backend-only Cloudinary API key |
| `CLOUDINARY_API_SECRET` | Backend-only Cloudinary signing secret |

Optional settings:

| Variable | Behavior |
| --- | --- |
| `TELEGRAM_BOT_TOKEN` | Optional; absent means no Telegram polling worker starts |
| `SPOTPULSE_PUBLIC_BASE_URL` | Optional canonical HTTPS origin, primarily for a configured custom domain; otherwise assigned Render hostname is used |
| `SPOTPULSE_SEO_INDEXABLE` | Set `false` for a non-indexable rehearsal/staging site |
| `SPOTPULSE_BOOTSTRAP_ADMIN_ID` | **Unset by default**. Temporary operator-only first-admin action described below |

Usually leave `SPOTPULSE_FRONTEND_ORIGIN`, `SPOTPULSE_OAUTH_REDIRECT_URI` and `SPOTPULSE_AVAILABILITY_ALLOWED_ORIGINS` unset in production: the resolver sets the correct same-origin values. If set manually, use exactly `https://<actual-hostname>`, `https://<actual-hostname>/login/oauth2/code/google`, and `https://<actual-hostname>`, respectively. No trailing UI path or wildcard origin is allowed. Never define `VITE_GOOGLE_*`, `VITE_CLOUDINARY_*` or other frontend secrets. The production build uses relative API/authentication paths.

## PostgreSQL setup, persistence and schema tradeoff

Create the database in the **same region** as the web service. Use its internal connection URL rather than an external database address when possible. The production resolver accepts Render's `postgres://` or `postgresql://` URI, decodes percent-encoded credentials correctly, and separates the username/password from the JDBC URL. The resulting URL is `jdbc:postgresql://<host>:<port>/<database>?sslmode=require`, without inline credentials.

Render internal PostgreSQL supports TLS with self-signed certificates; its documented internal mode is `sslmode=require`, not `verify-ca`/`verify-full`. See [Render PostgreSQL connection documentation](https://render.com/docs/postgresql-creating-connecting). The parser intentionally permits only the standard database path and optional `sslmode=require` query rather than arbitrary JDBC options.

As an alternative to DATABASE_URL, use `SPRING_DATASOURCE_URL` containing a PostgreSQL JDBC URL plus separate `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD`. Do not configure both modes. Credentials must not appear in a JDBC URL or be printed/copied into public logs.

The pool is deliberately small: maximum 5 connections, minimum idle 1, finite connection/validation/socket timeouts. Tomcat worker threads are bounded for a small instance. Existing queries use JPA/Hibernate, parameter binding, portable date casts, numeric types and database row locks. Existing booking overlap/concurrency logic and persistent Telegram lease/offset entities are retained. No vendor-specific schema rewrite or data import is added. CI opts the existing 18-test stabilization/concurrency suite into an ephemeral PostgreSQL 16 service; other tests retain isolated H2.

For this university demonstration, production defaults to **`ddl-auto=update`**. It can add tables/columns/index definitions but is **not a versioned migration strategy**: it does not provide reviewed migration ordering, data backfills, rollback or safe handling of every schema change. `validate`/`none` are accepted for an operator-managed schema; destructive modes are rejected. Introduce Flyway/Liquibase with a reviewed baseline before a long-lived production system. Large-data index tuning and migration rehearsals remain future work.

The cloud database starts independently from local H2. There is no automatic H2 import, reset or overwrite. Do not point tests at a real database: CI uses a fresh runner-owned `spotpulse_ci_test` database and the existing test guard requires explicit test URLs ending in `_test`. Existing local H2 and PostgreSQL records remain untouched by this task.

PostgreSQL survives web-service redeploys, but database deletion, plan expiry or account incidents can still lose data. Establish an operator-managed export/restore procedure and store backups securely outside the web container. The current free-database restrictions are summarized below; confirm available backup options in the chosen plan. Local image references are not migrated to Cloudinary: do not assume copying a database also copies local image bytes. Re-upload such images through the authorized editor when deliberately preparing cloud data.

## Google Cloud and HTTPS sessions

Keep the existing backend-driven authorization-code flow. After Render assigns the real hostname, edit the **Web application OAuth client** in Google Cloud Console → APIs & Services → Credentials:

1. Add the exact authorized redirect URI **`https://<actual-render-hostname>/login/oauth2/code/google`**. This is a template: replace it with the dashboard's actual hostname, not a guessed URL.
2. If configuring Authorized JavaScript origins, use the exact origin **`https://<actual-render-hostname>`**, without `/app`. No browser Google SDK is required by this backend flow.
3. Keep any still-needed localhost callback as a separate explicit entry for local development. Do not use wildcards or replace the flow with frontend authentication.
4. Review the OAuth consent screen's publishing/testing status and allowed test users for the demonstration audience.

The backend callback remains `/login/oauth2/code/google`; `/app` is **not** inserted into that callback. Success returns to `/app/spaces`, failure to `/app/login` on the configured HTTPS origin. These targets do not come from a user-controlled redirect parameter or forwarded Host header. Log in from the configured canonical hostname and do not switch domains mid-flow. Custom-domain adoption requires updating Google, Render and the canonical origin together.

Production cookies are **Secure, HttpOnly, SameSite=Lax**, with the existing server-side session and CSRF repository. React obtains fresh masked CSRF tokens through `/api/auth/csrf` and sends them on mutations, including multipart uploads and logout. No CORS relaxation is introduced.

`server.forward-headers-strategy=native` with `x-forwarded-proto` lets Tomcat recognize HTTPS terminated at Render's proxy. No permissive trust-all proxy regex is configured. Keep the container behind the trusted Render edge; do not expose its HTTP port directly to arbitrary clients. Tests exercise the real embedded servlet server with forwarded HTTPS and check HSTS, secure cookie flags and fixed OAuth redirect behavior. Actual Render edge behavior still needs the public smoke test.

Session state is in memory, so redeploys/restarts invalidate active login sessions and in-progress OAuth handshakes. Users may need to sign in again and fetch a new CSRF token. Do not confuse durable booking/account data with durable login sessions. Protected responses and the SPA shell use no-store cache behavior; session data and tokens must not be put into a CDN cache.

## Health, WebSockets, Telegram and Cloudinary

Set Render's Health Check Path to **`/health`**. GET/HEAD is public and returns only `{"status":"UP"}` or a generic 503 `{"status":"DOWN"}`. It performs a small database readiness check, has no-store headers and exposes no schema, credentials or actuator/environment details. H2 console stays disabled and denied. Error stack traces and verbose SQL are disabled in production. Large image decoding and cold Spring startup still consume memory/time; monitor the actual instance instead of assuming a small plan has unlimited capacity.

Availability sockets remain `/api/rooms/availability/ws?roomId=<id>`. A production page automatically constructs **`wss://<same-origin>/api/rooms/availability/ws?...`**. The server permits exactly the configured HTTPS Origin and rejects application commands. Existing after-commit notifications, authoritative REST snapshots, polling and bounded React reconnects are unchanged. Use the manual reconnect button after a cold start exceeds the automatic retry budget. Broadcasting and Caffeine state remain local to one process: multiple instances require an event backplane and a deliberate session strategy.

Telegram remains one optional background poller inside the web process, not a second Render worker. Its PostgreSQL lease and next update offset survive process restarts; rolling instances sharing that database coordinate through the lease. **Stop/unconfigure a local poller before enabling the same bot on Render**: independent local H2 and cloud PostgreSQL databases cannot coordinate their leases. Network failures remain bounded/background and do not prevent startup or roll back a committed booking.

Cloudinary is mandatory for production image uploads. C19's backend-only SDK, validation, ownership/CSRF, stable URLs and safe cleanup remain unchanged. Browser image bytes come directly from Cloudinary CDN. Existing generated Cloudinary URLs are not rewritten. Do not use an ephemeral Render image directory as durable storage.

## Render setup: manual actions only

No Blueprint is supplied because manual setup makes plan/resource choices explicit and avoids accidentally creating paid resources. The current Render Dashboard and account limits must be checked before creating anything.

1. Review these local changes. Commit/push them yourself to `oleksandratupchiy/spotpulse`, normally via a PR targeting the verified default branch `main`.
2. Enable GitHub Actions if needed and wait for a real green workflow, including the Docker job. Docker image building has not been verified locally when the daemon is unavailable.
3. In Render, create **one PostgreSQL database**. Choose the region and a currently available plan only after confirming price, storage, expiry and backup conditions. Do not assume an indefinite free database.
4. Create **one Web Service**, connect it through Render's native GitHub integration to this repository and `main`, and choose Docker. Leave Root Directory empty (repository root), Dockerfile Path `./Dockerfile`, Docker Build Context `.` and Docker Command override empty. This uses the image entrypoint; do not select the old backend-only Dockerfile.
5. Choose the same region and a currently available small compute plan after checking its memory/cost/usage limits. No persistent disk is required by this architecture. Keep one web instance.
6. Add the required web-service environment variables from the table, using the database's private Internal Database URL. Set `/health` as the health check. Do not add deployment/provider secrets to GitHub CI. The image handles PORT; do not hardcode a public hostname before Render assigns it.
7. Create/deploy the service through the Dashboard. The initial creation is itself a user-triggered deployment. Keep Auto-Deploy **Off** until CI and the initial configuration are reviewed.
8. Copy the actual HTTPS URL from the service page. Confirm the automatically derived origin and add that exact Google Cloud callback/origin as described above. Re-run login after Google settings propagate.
9. To create the first administrator, sign in to the deployed app as the intended verified Google user and read that account's ID on `/app/account`. As the service operator, temporarily set `SPOTPULSE_BOOTSTRAP_ADMIN_ID` to **that deployed account ID**, restart/redeploy once, then remove the variable immediately. This existing startup action is not an HTTP endpoint and only works while no administrator exists. Do not use a local H2 user ID. Leaving the variable set causes later startups to reject repeated bootstrap.
10. Use the authenticated ADMIN UI to grant OWNER to the intended management account. Test OWNER-only space/image operations, Cloudinary delivery and cross-owner denial.
11. Configure branch protection and Render's CI-gated native Auto-Deploy as described next; then perform and record the public smoke checklist.

### Free/small-plan caveats

As checked on 2026-10-30, Render documents idle free-web-service spin-down, ephemeral local files, possible restarts, limited included usage and expiring free PostgreSQL. Its current free PostgreSQL documentation specifies a **30-day expiry**, a **14-day upgrade grace period before deletion**, and no managed backups. These are not permanent product guarantees: verify the [current free-instance rules](https://render.com/docs/free) and your account's available plans before choosing them.

Cold starts can delay a demonstration. Telegram polling is outbound work and is not a promise of an always-running service; inbound Telegram messages do not wake a sleeping long-polling web process. Do not claim uninterrupted background notification delivery. Open/warm the site before rehearsing, and expect WebSocket reconnect and login-session loss on restarts. No artificial keep-awake infrastructure is added. Render's sleeping-service robots response can also temporarily affect crawling. Confirm memory, build/bandwidth quotas and any charges yourself; no paid plan is selected here.

## GitHub Actions CI and native continuous deployment

`.github/workflows/ci.yml` runs on pushes and PRs targeting **main**, and supports manual workflow dispatch. All jobs use read-only repository permission; checkout does not persist credentials. It uses the current official checkout/setup actions, Java 25, Node 24 and Python 3.12, with Maven/npm dependency caching.

| Check name | Work |
| --- | --- |
| Backend tests (Java 25) | Clean Maven verify, full backend suite; fresh PostgreSQL 16 service for the existing opt-in stabilization tests; other databases remain isolated |
| React tests and production build | `npm ci`, full React tests, TypeScript check and `/app` production build |
| F2 JavaScript and C8 geocoding | Existing dependency-free Node autocomplete tests and offline Python geocoding tests |
| Production Docker image | Requires all three preceding jobs; builds the complete image, checks the runtime JVM and non-root user, but does not start the application, publish or deploy |

The workflow's PostgreSQL username/password are synthetic credentials for a newly created runner-local `_test` database, not production secrets. It does not need real Google, Telegram, Cloudinary or Render credentials. No artifacts containing sessions, tokens, environment dumps or private accounts are uploaded. No deployment hook, registry push or Render API call is present.

In GitHub, protect `main`: require PR review as appropriate and require all four check names above before merge, with up-to-date checks where supported. Confirm the actual check names after the first workflow run. Administrators can bypass protections depending on repository settings; configure those manually. Keep this workflow running on every linked-branch push, with no path filter that could omit required checks.

In Render service Settings → Auto-Deploy, choose **After CI Checks Pass**, not On Commit. This requires a connected Git provider; linking only a public repository URL does not provide the same native CI integration. Render waits for detected checks, refuses deployment on a failed check or zero detected checks, and treats GitHub neutral/skipped conclusions as passed. See [Render deployment/CI gating documentation](https://render.com/docs/deploys). The unconditionally running test jobs prevent a failed suite from being hidden by the dependent Docker job being skipped.

Use this single native mechanism; do not also add an Actions deploy-hook job. Initial creation, manual Dashboard deploys, configuration changes or operator overrides can bypass the intended push/CI path, so perform those only deliberately after reviewing checks. Passing CI does **not** prove that real credentials, Render resources, quota, TLS/domain setup or production startup will succeed. Render must still build, start and pass `/health`. A future broken commit should remain undeployed when this gating is configured correctly; verify that behavior with a harmless rehearsal PR rather than breaking production.

## Manual public smoke checklist

Record the actual URL, commit SHA, date, Render deploy/health status and evidence without credential/cookie values. None of these live checks are claimed as performed here.

1. Open `https://<actual-hostname>/health`: expect 200 with only UP. Confirm HTTPS and no-store. Check that H2 console and debug/environment endpoints are unavailable.
2. Open `/`, `/app/spaces`, a real `/app/rooms/<id>` and a real `/app/spaces/<id>` directly and refresh. Verify assets load under `/app/assets/`, and unknown asset/API URLs do not return SPA HTML.
3. Open `/spaces`, `/spaces/<id>`, `/robots.txt` and `/sitemap.xml`. Verify preserved server-rendered content and HTTPS canonical URLs (subject to staging noindex/free-sleep caveats).
4. Complete real Google sign-in. Inspect the callback URI, HTTPS redirects and cookie **flags only**: Secure, HttpOnly, SameSite=Lax. Test profile and CSRF-protected logout. Never capture cookie/token values in evidence.
5. Create a booking, reject an overlap, cancel it and confirm persistence after a web-service restart. USER/OWNER/ADMIN restrictions and another owner's resources must remain enforced.
6. Open the same room in two visible windows. Compare WebSocket and polling modes. Verify a `wss://` connection, room-filtered updates after commit, cancellation updates, strict Origin behavior and recovery after restart.
7. As OWNER/ADMIN, select/preview/upload an image. Confirm its saved HTTPS Cloudinary URL and direct CDN image request on discovery/details. Test failed replacement retry without losing the previous image or selected file.
8. If Telegram is enabled, stop any independent local consumer, connect a test user's Telegram account and verify notification behavior. Restart the web service and confirm lease/offset persistence; do not infer always-on delivery from this test.
9. Verify no real secrets appear in browser bundles, API payloads, server logs or public screenshots. Confirm administrative APIs still require role/session/CSRF.
10. Push a reviewed harmless change through CI, then confirm Render deploys the matching commit only after successful checks. Test failure gating without manually overriding it. Record any cold-start/quota effects.

## Local verification and actual results

Commands executed or configured:

```powershell
# Backend, after deployment frontend build
.\mvnw.cmd -B -ntp -Pspa clean verify
.\mvnw.cmd -B '-Dtest=ProductionConfigurationTests,DeploymentHttpTests,HealthTests' test
python tools/verify_spa_jar.py
# Frontend
npm.cmd test
npm.cmd run build:deployment
# Repository root utilities
node --test spotpulse-backend/src/test/js/space-autocomplete.test.cjs
python -m unittest discover -s spotpulse-backend/tools -p 'test_*.py' -v
# Workflow syntax/expression validation, if installed
actionlint -shellcheck= -pyflakes= .github/workflows/ci.yml
```

Results on Windows / Java 25 / Node 24.21.0 / Python 3.12.0:

- Initial backend compilation: **passed**, Maven **21.164 seconds**.
- Dedicated configuration and real embedded-HTTP deployment tests: **16 passed**, **0 failures/errors/skips**, Maven **1:05 minutes**. They cover production/local selection and required variables, Render URI decoding/TLS, safe failures, secure forwarded HTTPS/cookies, OAuth redirects, SPA/static/API/SEO route separation, health and strict read-only WebSocket behavior.
- Full React suite: **97 passed in 11 files**, **0 failures/skips**, **42.85 seconds**, including basename and same-origin WebSocket checks plus all previous assignments.
- TypeScript and deployment frontend build: **passed**, 63 modules; Vite **8.63 seconds**. The generated index references `/app/assets/...`.
- F2 JavaScript autocomplete: **8 passed**, **0 failures/skips**, **509.9263 ms**.
- C8 Python geocoding: **5 passed**, **1.276 seconds**, offline mocked network.
- Workflow YAML parsed successfully with PyYAML. **Actionlint 1.7.12 passed**, with optional shellcheck/pyflakes disabled because those tools are not installed. The official release binary was downloaded to a temporary directory and checked against its published SHA-256 checksum; official action tags/inputs were also checked.
- Final full clean backend build with the SPA profile: **243 passed**, **0 failures/errors/skips**, Maven **5:18 minutes**, including existing booking, Telegram, premium, caching, image and availability regressions. The two additional image-directory tests check operation outside a Git checkout and rejection of storage inside the working directory.
- Packaged-JAR smoke check: **passed**. All **7** frontend distribution files match the packaged bytes; **2** referenced assets load. An actual Java process outside the checkout serves health, root redirect, four SPA deep links, legacy server-rendered spaces and public API; unknown assets return 404. Its database is isolated in-memory H2, with no external credentials. The first harness run exposed a Windows Java-launcher child-process cleanup failure; the harness now uses the detected JVM executable and bounded cleanup, and the rerun passed. This checks the assembled JAR, not a Docker image or a live production deployment.
- Docker CLI exists, but the daemon is unavailable: both restricted and unrestricted checks failed because the Docker engine pipe does not exist. Therefore a local Docker build/container run **could not be performed**. The CI Docker job is present but has not run on GitHub.
- A live local PostgreSQL integration run was not performed because no isolated PostgreSQL service was available. The CI service configuration provides that check after user push; this is not reported as a locally passing PostgreSQL run.

Automated tests use in-memory H2 or their verified temporary persistence fixture, dummy/test Google registration and mocked external APIs. They do not use Render, real Cloudinary/Telegram credentials or developer persistent databases. Prior C10 benchmark and C15/C19 verification artifacts are preserved around clean builds. Real Google/Cloudinary/Telegram functionality on Render remains a manual smoke test, not a mocked-test claim.

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Docker COPY/build fails | Repository root context, root Dockerfile, committed lockfile and source paths; do not set Render Root Directory to the backend |
| SPA index missing / direct route 404 | Run deployment Vite build and Maven `-Pspa`; inspect JAR `static/app/index.html`; do not use a root-based preview bundle |
| Missing production setting at startup | Add required names in Render web-service Environment; inspect the fixed error without printing values |
| JDBC driver/URL error | Use Internal DATABASE_URL or separate PostgreSQL JDBC credentials, not both; same-region DB and `sslmode=require`; no H2 in prod |
| `/health` returns DOWN / startup unhealthy | Database reachability, credentials, connection limits, quotas and service readiness; health exposes no detailed error intentionally |
| Google redirect mismatch | Actual dashboard hostname and exact `/login/oauth2/code/google`, HTTPS and no `/app` in callback; correct Google client and consent/test users |
| Login fails after domain change/restart | Use one canonical hostname, retry login with a new session, update Google callback and origin together |
| Mutation 403 | Valid login/role/ownership and fresh CSRF; retain Secure cookies and same-origin API calls rather than disabling protection |
| WSS rejected | Exact HTTPS Origin, canonical URL, healthy service and proxy Upgrade support; no wildcard origin workaround |
| Telegram 409/unavailable | Another poller on a different database, token missing/incorrect, sleeping service or lease expiry; keep optional and inspect sanitized status |
| Cloudinary upload failure | Backend-only complete credentials, selected cloudinary provider, provider quota/network and valid image limits; do not switch production to ephemeral local storage |
| Render deploys before CI | Native GitHub connection, linked main branch, Auto-Deploy After CI Checks Pass, branch protections and no duplicate hook/manual bypass |
| Cold start or memory termination | Monitor actual plan limits and workload, large image decoding and JVM memory; warm for rehearsal and choose capacity only after confirming costs |

## Remaining user actions and assignment readiness

The repository owner must review/commit/push, enable/run Actions, confirm the image build, choose/confirm Render plans, create the database and one web service, enter real backend environment variables, obtain the assigned hostname, update Google Cloud, bootstrap the first verified administrator if needed, configure native CI gating/branch protection, and complete the public smoke checklist. No billing details are requested or resources provisioned by this agent.

C3 configuration is ready for its first real GitHub execution; hosted CI/CD settings are not remotely enabled by this patch. C2 configuration is prepared, but **C2 must remain incomplete until actual public HTTPS deployment, authentication, storage and smoke behavior have been verified**. Keep screenshots/evidence free of credentials, token values and private customer information.

Changed files include the root Dockerfile/dockerignore, wrapper line-ending attributes, Maven SPA resource profile, production properties/resolver, limited SPA/health controllers, production OAuth UI-prefix support, frontend basename/build script, CI workflow, targeted tests and this document. The backend-only local Docker/Compose flow, README, generated `bin/`, provider credentials and persistent application records are not modified. No commit or push is performed.

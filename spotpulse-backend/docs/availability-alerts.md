# Persistent room availability alerts

The room page's **Check availability** controls still refresh the public room snapshot (C10).
**Notify me when available** separately saves an authenticated subscription. Refreshing the
room page restores its most recent active alert and times. The account page lists all alerts
with pagination and cancellation. Nothing is stored in browser storage or tied to a session.
Anonymous visitors must sign in. Every authenticated role may subscribe to publicly readable
rooms, consistent with the existing room/booking access policy. Even administrators can only
list/cancel their own alerts.

## Schema and API

The repository uses Hibernate `ddl-auto=update` on both local H2 and production PostgreSQL;
there is no Flyway/Liquibase or versioned SQL migration runner. This feature follows that
existing additive schema convention. The new `availability_alerts` entity adds one table,
three indexes and a unique constraint, without changing existing tables or records. On a
future deployment, the existing schema updater creates these objects. No production setting
or manual data migration is required. Do not use `create`, `create-drop` or a reset against
an existing application database. Those settings are used only in isolated automated tests.

The table stores user/room foreign keys, the exact requested local timestamps (microsecond
database precision), status, creation/success timestamps, next attempt, attempts, lease token
and a fixed, non-sensitive delivery reason. Foreign keys cascade only if their existing
parent is deleted, preserving existing user/room deletion operations. The uniqueness key is
`(user_id, room_id, start_time, end_time, active_marker)`. Active/ready rows use `ACTIVE`;
terminal rows use NULL. H2 and PostgreSQL both allow multiple NULL entries in this unique key.
The room's pessimistic write lock serializes concurrent API creation with booking writes;
the database unique constraint also protects against alternate insert paths.

- `POST /api/availability-alerts`: `{roomId, startTime, endTime}`. Authenticated identity is
  authoritative; a caller cannot choose another subscriber. Returns the existing active
  subscription on duplicate requests, otherwise the saved subscription (201).
- `GET /api/availability-alerts`: only the current user's alerts, using existing page/offset
  pagination. Optional `roomId`, `startTime`, `endTime`, `activeOnly` filters restore a slot.
- `DELETE /api/availability-alerts/{id}`: idempotently cancels an owned active/ready alert.
  Missing or another user's ID returns 404. All writes use existing session CSRF protection.

Creation requires a future, increasing interval of at most 12 hours, reusing the booking
duration validator and the authoritative availability service. A free interval returns 409
with an explanation to book immediately. Room reads are public; no room ownership is needed.

## Detection and delivery

Existing `AvailabilityChanged` events wake ACTIVE subscriptions after transaction commit.
They cover cancellation, confirmed booking deletion, and administrator changes/moves that
release a room, without changing `BookingService`. A durable database scan also checks active
rows every 30 seconds when due, so a crash between commit and the event listener does not
lose the watch. It observes currently committed availability, not a historic free window.

The dispatcher wakes every 10 seconds, handles at most 50 due rows per pass, and uses a
separate single delivery thread so Telegram cannot block C10 scheduling or booking requests.
Each claim locks room then subscription, rechecks the *entire* requested interval through
`AvailabilityService`, and commits READY plus a random 120-second lease. Network calls occur
outside database transactions using the existing Telegram client, account eligibility and
booking-notification preference. A second worker cannot claim the same unexpired lease.
Repeated events cannot resend SENT rows. A newly occupied interval goes back to ACTIVE.

Only an acknowledged successful Telegram response sets SENT/notifiedAt. Failures stay READY,
with bounded exponential retry (up to one hour, respecting Telegram retry-after up to one day).
Missing links, disabled preferences, bot mismatch, unconfigured or unavailable Telegram keep
delivery pending and are rechecked after one minute. Linking/re-enabling permits a later
attempt while the interval is still future and available. At the requested start time, alerts
expire; the next scan marks them EXPIRED, including after restart. API views show expiry
immediately, even between scans. They never reserve a room. Room links reuse the configured
public origin and SPA path; without a public origin, local links use the frontend origin.

Telegram `sendMessage` has no caller-supplied idempotency key. Leases and durable SENT state
deduplicate ordinary events, retries and simultaneous workers. A crash after Telegram accepts
a message but before SENT commits (or an ambiguous network timeout) can cause a repeat on
recovery. Exactly-once remote delivery cannot be guaranteed; the implementation favors
recoverability and never records a failed/unknown call as sent. An alert cancellation that
races with an already started network call cannot retract that call. Availability can also
change after the final check; the message explicitly says it is not a reservation.

## Safe local verification

Use only the existing local database. If the production token might be shared, start the
local backend with `--spotpulse.telegram.enabled=false`; no token changes are needed. To
disable only this feature's worker while inspecting persisted rows, use
`--spotpulse.availability-alerts.dispatch-enabled=false`. Neither setting affects Render.

1. Create a future occupied interval using a separate local account; sign in as the watcher.
2. Check that interval, save its alert, and verify **You're watching this time slot**.
3. Refresh, sign out/in, close/reopen the browser, then restart the local backend normally
   against the same file-backed H2 database. The watch and account list remain.
4. Cancel the occupying booking. With the dispatcher enabled and Telegram disabled locally,
   the database alert becomes READY with `not_configured` (or `not_linked` if unlinked), and
   `notified_at` remains NULL. It is scheduled, not falsely reported as delivered.
5. Cancel an alert from the room or account page; it becomes CANCELLED. After its start time,
   an uncancelled alert becomes EXPIRED. A still-overlapping booking keeps the watch ACTIVE.

`AvailabilityAlertTests` uses isolated H2 and a mocked Telegram client to assert success,
deduplication, retries, outages, after-commit behavior and concurrency without Bot API calls.
`LocalDatabasePersistenceTests` starts three real JVMs against its own temporary H2 database
and verifies both ACTIVE subscriptions and READY retry metadata across two restarts. Automated
contexts globally disable live Telegram and scheduled alert dispatch; focused tests invoke
the dispatcher explicitly with mocks. The isolated Playwright harness also has live Telegram
disabled. No real Telegram delivery is asserted by these tests.

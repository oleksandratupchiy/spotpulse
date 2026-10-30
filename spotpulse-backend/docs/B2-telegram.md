# B2: Telegram Bot API integration

The backend uses the official HTTPS Bot API (`getMe`, `getUpdates`, `sendMessage`):
https://core.telegram.org/bots/api
Deep links follow https://core.telegram.org/bots/features#deep-linking.

## Run and verify locally

1. Start the backend from the PowerShell session that already contains `TELEGRAM_BOT_TOKEN`. Restart a backend launched before that variable was configured. Do not put the credential in an IDE project, properties file, frontend variable, command argument, or database.
2. Start the React app using its existing Vite proxy and sign in through Google.
3. Open **Your profile → Telegram → Connect Telegram**. The actual username is discovered asynchronously through `getMe`; readiness can take a few seconds after startup.
4. The generated `https://t.me/<username>?start=<random code>` opens Telegram. Press **Start** in that private bot chat, even if you have previously started the bot. A bare `/start` cannot identify a SpotPulse account.
5. Telegram sends a connection confirmation, and the profile updates within five seconds. Popup blocking is handled by a visible **Open Telegram to finish connecting** link.
6. Select **Disconnect Telegram** to clear the connection and revoke pending links. Pending attempts can be revoked with **Cancel connection**.

No webhook, tunnel, public domain, manually copied chat ID, or bot username configuration is required. When no credential exists, discovery and booking work normally and the profile reports Telegram unavailable. `spotpulse.telegram.enabled=false` explicitly disables networking without removing the environment variable. Automated tests always force this setting off before context startup, then use test-only client doubles.

The bot supports secure account linking and real connection/help messages. Booking creation, cancellation, locking and after-commit cache invalidation retain their existing behavior.

## Security and persistence

- Authentication and the existing session CSRF policy protect `POST /api/account/telegram/connect` and `DELETE /api/account/telegram`. These endpoints accept no user ID or chat ID. `GET /api/account/telegram` returns only configured/available/connected booleans and the pending expiry time. Responses use `Cache-Control: no-store`.
- Each link uses 32 bytes from `SecureRandom`, encoded as 43 base64url characters. Only a SHA-256 hash is persisted, with a five-minute expiry. The deep link is returned once to its authenticated owner; status endpoints never return it. Treat that temporary link as private.
- Every new attempt clears the previous connection and replaces its outstanding code. Disconnect revokes the code. A successful link consumes it in the same transaction as the connection change and polling offset. Invalid, expired and used codes cannot link accounts.
- Only ordinary private messages from a non-bot sender whose Telegram user ID matches the positive chat ID are accepted. Groups, channels and mismatched senders are ignored. User-row locks serialize reconnect/disconnect against code consumption. A unique chat constraint prevents linking a chat to two accounts; the current account must explicitly disconnect it first.
- Separate JPA tables store account connections and per-bot offsets/leases. Hibernate's existing schema update works with H2 and PostgreSQL; user deletion cascades to its Telegram row. No changes to OAuth identity matching or user DTOs are needed.
- A single background worker starts after application readiness, only when configured. HTTPS requests have a five-second connect timeout and a thirty-second request timeout; long polling waits at most twenty seconds. Redirects are disabled. Network exceptions, HTTP/API failures and remote descriptions are replaced with fixed, credential-free errors. Rate-limit waits honor `retry_after`; processing/network failures back off. Confirmation messages are best effort: a delivery failure leaves a successful connection visible in the profile.
- A database lease lasts ninety seconds and is renewed before each poll/send. Instances sharing the same database coordinate using a pessimistic lock and a random process owner. Offset commits and linking are atomic, so restart/replayed updates cannot reuse codes. On orderly shutdown the worker is interrupted and the lease is released; a crashed worker's lease expires. Shutdown is bounded.
- Run one deployment/database per bot token. Independent databases cannot coordinate leases; Telegram will reject conflicting `getUpdates` requests with 409. A pre-existing webhook also prevents polling. Configure the dedicated bot without a webhook; the application does not silently delete remote webhook configuration. Logs show only sanitized numeric status categories, never remote descriptions or credential URLs. Avoid enabling low-level JDK HTTP wire logging, which is outside application logging.

## Automated and manual checks

Run backend tests with `./mvnw test` (Windows: `.\mvnw.cmd test`), and frontend tests/build with `npm test` and `npm run build`. Tests cover code randomness/hashing, expiry/replacement/replay, private-chat filtering, exclusive leases, offsets, chat uniqueness, per-account disconnect, authentication/CSRF, safe responses, disabled integration, sanitized client failures/rate limits, and profile actions.

For a manual security check, generate two links and verify that only the newest works; reuse a consumed link; wait five minutes before using a fresh link; send it to a group; try connecting the same chat from a second SpotPulse account; disconnect and retry old links; restart the backend and confirm that a completed link stays connected. No actual bot credential is required by or read into the test suite.

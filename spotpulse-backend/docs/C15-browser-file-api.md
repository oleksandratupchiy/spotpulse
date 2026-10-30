# C15 — Browser File API for coworking images

## Demonstrable behavior

OWNER and ADMIN create/edit forms include **Select coworking image** alongside the existing **Image URL** field. Selecting an image shows a local preview, filename, byte size, detected MIME type and dimensions. The chooser can replace a selection, and **Remove selection** clears it. Removing a local selection leaves the previously saved image unchanged. To detach a saved image, clear Image URL and save without a local selection.

**Save space** first persists the space using the existing API. Only after the server has established ownership does the UI attach the image. A selected file takes precedence over Image URL. Upload failures explicitly say that the space was saved but its image was not attached. The selection and saved ID remain in the form; retry updates that same space instead of creating another one. An attachment is only considered successful after its protected upload request succeeds.

Discovery cards, details and the editor use the stable `imageUrl` returned by the backend. Uploaded references honor the frontend API base configuration. Existing external image URLs remain supported, with no server-side remote fetching. Image and placeholder containers share a fixed height to prevent layout shifts. Broken images show the existing placeholder, and a new URL can recover from an earlier image failure.

## Browser APIs and validation

- Native `<input type="file" accept="image/jpeg,image/png,image/webp">` supports device selection and keyboard use, with labels, help text, status and error announcements.
- `File` supplies filename, size and declared type. Files remain binary; no base64/data URLs are generated.
- `Blob.slice(0, 256 * 1024)` and `FileReader.readAsArrayBuffer` read a bounded header. `Uint8Array` and `DataView` inspect JPEG markers, PNG IHDR and WebP headers for signatures and dimensions. Extensions are not trusted.
- Input limit: **8 MiB (8,388,608 bytes)**. Empty files, unsupported signatures, declared MIME/content mismatches, invalid dimensions, sides above **8192 pixels**, or more than **12,000,000 pixels** are rejected before pixel decoding. A missing browser MIME type is inferred from validated content and supplied on the multipart File.
- `URL.createObjectURL(file)` produces an efficient preview. Browser `Image` decoding must succeed and agree with inspected dimensions (including EXIF-swapped JPEG dimensions).
- AbortController cancels superseded reads/decodes; a generation guard prevents stale completion. `URL.revokeObjectURL` releases URLs on replacement, clear, validation/decode failure and unmount. Save is disabled during inspection.
- `FormData` carries the binary `File` to the existing API client. The client retains session credentials and obtains a fresh masked CSRF token; it lets the browser set the multipart boundary.

## Local storage and persistence

`ImageStorage` is the backend storage boundary; `LocalImageStorage` is its C15 implementation. `ImageValidation` independently verifies the upload before storage. JPEG/PNG use Java ImageIO; the TwelveMonkeys WebP ImageIO reader adds actual WebP decoding without any credentials.

The server checks actual bytes, exact allowed MIME/content agreement, size and dimensions before fully decoding. It re-encodes decoded pixels as JPEG or PNG, removing appended payloads, metadata and extra frames. WebP is normalized to PNG. The normalized output is also bounded to 8 MiB. Original filenames never become storage paths. Path-bearing names, control characters, SVG, HTML and other unsupported content are rejected.

The default directory is `LocalDatabaseEnvironment.dataDirectory(...)/images`, outside the checkout. On Windows this normally resolves to `%LOCALAPPDATA%/SpotPulse/images` (or the existing application-data override). Set `spotpulse.images.directory` to an absolute external directory to override image storage independently, for example:

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--spotpulse.images.directory=C:/SpotPulseData/images'
```

The launching backend account must be able to write there. Directory creation is lazy. Startup rejects a relative directory or a resolved directory within the checkout. No credentials or Cloudinary configuration are needed.

Each normalized image receives a UUID filename with a server-selected `.jpg` or `.png` suffix. `CREATE_NEW` prevents overwrites. Only generated-key files can be read, with no-follow file checks and bounded reads. The public reference `/api/spaces/images/{key}` is saved in the existing Space `imageUrl` column. No schema or DTO change is required. Files and database references survive application restarts; both must be retained/backed up together.

Attachment runs in a database transaction, locks the Space row and rechecks ownership after locking. A rollback deletes only the new image created by that transaction. Previously committed images are deliberately retained after replacement, URL editing or space deletion: another record, public URL or cache may still reference them. Existing images and records are never purged by this feature.

## HTTP API and authorization

| Endpoint | Behavior |
| --- | --- |
| `POST /api/spaces/{id}/image` | Multipart field `file`; authenticated OWNER of that space or ADMIN; valid CSRF required. Returns the updated Space DTO and `Cache-Control: no-store`. |
| `GET /api/spaces/images/{key}` / `HEAD ...` | Public access to one validated, generated image key only. Correct image Content-Type, `nosniff`, inline disposition and immutable public caching. Invalid/missing keys return 404. |
| Existing `POST /api/spaces`, `PUT /api/spaces/{id}` | Existing ownership/role rules; retained for normal form saving and URL editing. Creation establishes ownership before image attachment. |

Anonymous upload returns 401 when a valid CSRF token is supplied; missing/invalid CSRF can be rejected first with 403. USER and another OWNER receive 403. ADMIN can manage any space according to existing permissions. Malformed/empty/unsupported images receive 400, oversized images 413, unavailable storage 503. Server errors do not reveal local filesystem paths. Multipart limits are 8 MiB per file and 9 MiB per request.

The existing transaction-aware `ReadDataChanged(SPACES)` event invalidates public details, discovery/map and related read caches only after a successful commit. Image references change on replacement, allowing immutable image caching without stale image bytes. No public mutations, arbitrary static directory mount, remote image fetching or CSRF exemptions were added.

## C19 integration boundary

The React `attachSpaceImage` function owns the multipart attachment contract. C19 can keep that UI/API workflow and replace `ImageStorage` with a Cloudinary/CDN adapter returning a stable public reference. Keep independent server validation, authorization, transaction compensation and cache invalidation. Existing local references will need continued local serving or an explicit migration; they must not silently break when switching adapters. This task implements no Cloudinary calls and requires no CDN secrets.

## Manual browser demonstration checklist

Use the normal Vite frontend and backend, with an existing verified OWNER or ADMIN account. Avoid using a developer's production data for destructive demonstrations.

1. Sign in with Google as OWNER; open Manage spaces → Create space.
2. Select a JPEG, PNG or WebP. Show filename, exact byte size, MIME type, dimensions and preview before saving. Keyboard-focus the chooser and Remove selection button.
3. Replace the image, remove it, then reselect. Confirm the preview follows the current selection. In browser developer tools, watch object URLs being created/released when replacing and navigating away.
4. Try an empty file, SVG, text renamed `.png`, a MIME mismatch, a file over 8 MiB, a dimension above 8192, and a >12-megapixel image. Show the validation errors; no upload should occur.
5. Fill required space fields and save. In Network, show the space creation request followed by the CSRF-protected multipart image POST against the returned space ID. It contains binary bytes, with no base64 JSON upload.
6. Open discovery and the space details page. Reload and restart the backend; confirm the image reference and image still work. Replace the saved image and repeat.
7. Edit Image URL with a normal existing URL; save with no file selection. Clear the URL to demonstrate the placeholder. A broken URL must show the same graceful fallback.
8. Interrupt an image POST (for example block that endpoint in browser developer tools). Confirm the form says the space was saved but the attachment failed. Unblock and retry; verify it attaches to the same ID, with no second create POST.
9. As a different OWNER, attempt the image POST to that space; expect 403. As USER, expect 403 and no editor access. Anonymous access can view the uploaded image but cannot upload. As ADMIN, upload to the OWNER's space. Remove the CSRF header and show 403.

Automated React tests use jsdom and a controlled image decoder, while backend tests decode real image fixtures. An interactive browser/real Google login session was not available to this coding session; this checklist must still be exercised during the defense rehearsal.

## Verification results

Verification date: 2026-10-30, Java 25 / Windows, Node/Vite toolchain already installed in the repository. Commands run from their respective backend/frontend directories. Sandbox directory restrictions required approved reruns outside the sandbox.

- Initial `mvnw.cmd -B clean test`: compilation succeeded; **170 tests, 169 passed, 1 failed, 0 errors, 0 skipped**. The failure was the existing `LocalDatabasePersistenceTests` bootstrap subprocess timing out at two minutes under concurrent Windows load. Its child heap is now bounded, startup allowance is five minutes, and timeout failures include child logs. The final clean rerun passed as recorded below.
- Initial full React suite: **69 tests, 67 passed, 2 failed** (Premium initial lookup and map first lazy-load timing). Shared async wait allowance now matches existing Auth/Map's 10 seconds; whole-test allowance is 20 seconds and workers are bounded to two. No assertions were removed or tests skipped.
- Final full React run (`npm.cmd test`): **82 tests passed in 8 files**, zero failed/skipped; **110.56 seconds**. Includes all 13 Premium tests, Auth, booking, API, map, analytics, Telegram and all **13 image/File API tests**, including successful attachment and public-image recovery.
- Dedicated initial File API React run: **11/11 passed**.
- Final TypeScript and production build (`npm.cmd run build`, runs `tsc --noEmit && vite build`): **passed**, 61 modules transformed; Vite build **24.65 seconds**.
- Final backend (`mvnw.cmd -B clean test`): **BUILD SUCCESS**, **170 tests, 0 failures, 0 errors, 0 skipped**, **6:00 minutes**. Includes image tests (7), OAuth2/role/ownership/CSRF security (18), security caches (1), read caches (10), B6 Premium features (11) and persistence (1), booking analytics (5), backend/booking stabilization (18), persistent database configuration/restarts, pagination, discovery, SEO, map and Telegram regressions.
- Dedicated backend (`mvnw.cmd -B '-Dtest=SpaceImageTests,SecurityTests' test`): **BUILD SUCCESS**, **25 tests, 0 failures, 0 errors, 0 skipped**, **2:25 minutes** (7 upload/storage tests and 18 security tests).

Raw verification logs are retained under the ignored `spotpulse-backend/target/c15-verification/` directory. `git diff --check` also passed.

Tests never open the developer's persistent H2 database. The existing test-only database guard remains active. A test-classpath image-directory override provides random OS temporary storage for every context; upload tests use their own `@TempDir`. Persistence restart tests only create/remove their own verified random temporary directories.

Backend image tests cover real PNG/JPEG/WebP uploads, normalization of appended content, MIME/content mismatches, SVG/GIF/HTML, empty and oversized files, invalid dimensions/truncation, path-bearing names and read traversal, anonymous/USER/foreign OWNER denial, OWNER/ADMIN success, CSRF, public GET/HEAD, stable references, fresh storage adapters, replacement retention, cache invalidation and rollback cleanup.

## Limitations

- Local disk storage is for a single-instance development deployment; distributed hosting needs shared storage or C19. Old committed image files are retained, so disk usage grows until an explicitly designed reference-aware garbage collector is added. Process termination between writing bytes and database commit can leave an orphan; ordinary rollbacks are compensated.
- No client resizing/compression, crop UI, progress percentage or automatic orientation correction is included. JPEG EXIF is stripped during normalization; photos depending on EXIF orientation may need rotation before upload. WebP is converted to PNG, potentially increasing size; normalized output above 8 MiB is rejected.
- Metadata beyond the first 256 KiB is not scanned by the browser; unusually large JPEG metadata headers can be rejected. This is a deliberate bound on file reading. Animated WebP is rejected by client inspection; only still-image output is stored by the server.
- Images are public, like existing discovery images. Detaching a reference does not revoke an already public image URL or purge immutable browser caches.
- Existing external image URLs depend on their external host and network. No server-side URL fetching is introduced.
- Interactive browser verification and a live PostgreSQL run were not performed here; automated verification uses isolated H2. Existing PostgreSQL support is unchanged.

## Changed source files

- Backend: `pom.xml`; `images/ImageStorage.java`, `ImageValidation.java`, `LocalImageStorage.java`, `SpaceImageService.java`, `SpaceImageController.java`; `repository/SpaceRepository.java`; `controller/ApiExceptionHandler.java`; `application.properties`; `images/SpaceImageTests.java`; persistence test isolation and restart-test timing; this document.
- Frontend: `ImagePicker.tsx`, `images.ts`, `Management.tsx`, `api.ts`, `components.tsx`, `test/ImagePicker.test.tsx`, `test/setup.ts`, `vite.config.ts`.
- README files and generated `bin/` files are unchanged. No commit or push is performed.

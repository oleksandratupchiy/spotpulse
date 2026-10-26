# Persistent local database

## Local default and file location

Normal startup now uses the default Spring profile **local**, with file-based H2.
Previously `application.properties` selected `jdbc:h2:mem:spotpulse`, whose records
disappeared when the backend JVM stopped. The persistent default is an absolute
external path, independent of which repository directory launches Maven:

| Platform | Directory |
| --- | --- |
| Windows | `%LOCALAPPDATA%\SpotPulse` |
| Windows without LOCALAPPDATA | Java user home + `AppData\Local\SpotPulse` |
| macOS | Java user home + `Library/Application Support/SpotPulse` |
| Linux/other Unix with XDG_DATA_HOME | `$XDG_DATA_HOME/SpotPulse` |
| Other Unix fallback | Java user home + `.local/share/SpotPulse` |

The database basename is `spotpulse`: the main file is `spotpulse.mv.db` in that
directory. The URL uses the basename without `.mv.db`, for example
`jdbc:h2:file:C:/Users/<user>/AppData/Local/SpotPulse/spotpulse`.
On the implementation machine the default resolves to
`C:\Users\Alexandra T\AppData\Local\SpotPulse\spotpulse.mv.db`; that path is not
hardcoded into the application, and that real database was not opened during tests.

`LocalDatabaseEnvironment` runs after Spring config-data loading. It supplies a
low-priority URL only when the local profile applies and no explicit datasource URL
is present. Resolving the default does not open files or create directories. H2
creates its directory/database on the first actual local connection; subsequent
starts reuse existing files. Permission failures are errors, not a fallback to a
relative checkout path or a disposable database. Paths are absolute, existing
ancestor symlinks are resolved, and a generated data directory inside the checkout
(or current working directory when no checkout can be identified) is rejected.

Set **SPOTPULSE_DATA_DIR** to choose another absolute external directory. This is a
directory, not a JDBC URL or database filename. Relative paths and H2 URL-option
delimiters in this setting are rejected. Alternatively set the Spring property
`spotpulse.local.data-directory` through standard external configuration.

An explicit **SPRING_DATASOURCE_URL** always wins, including an existing absolute
H2 file URL, PostgreSQL URL, or intentionally disposable in-memory URL. A blank
explicit URL fails startup rather than silently choosing an embedded memory DB.
For explicit file URLs, always choose an absolute path outside the repository.
If you already have an H2 database with another basename, use its existing absolute
URL; this change does not copy, rename, reset or replace it.

`ddl-auto=update` remains the application default. Entities, columns, constraints,
ownership and security rules are unchanged. Destructive `create`, `create-drop` and
`drop` modes are rejected when generating the persistent local default. Do not
override schema-generation settings destructively against any existing database.
H2's web console remains disabled and denied by the security chain. Ignore patterns
also exclude accidental H2 artifacts from Git; the real default remains outside Git.

## Starting locally and verifying restart

From `spotpulse-backend`, in the PowerShell session already containing your Google
environment variables, run:

```powershell
.\mvnw.cmd spring-boot:run
```

No datasource environment variable or extra profile flag is needed. If
`SPRING_PROFILES_ACTIVE` is set to a non-local profile, explicitly select local or
use that deployment's explicit datasource settings. This default does not override
an existing `SPRING_DATASOURCE_URL` from an earlier setup.

To verify normal persistence:

1. Sign in normally with Google and note the account ID and role on `/account`.
2. Using the appropriate existing roles, create a real space/room and booking, or
   note existing records. Their authorization rules still apply.
3. Stop the backend normally with Ctrl+C and restart with the same environment.
4. Sign in again. The same Google subject resolves to the same database account and
   role. Ownership, rooms and bookings remain. Check the existing record IDs.

Backend restart expires its in-memory HTTP sessions and Caffeine caches. The browser
may need to sign in again; this does not recreate the database account or reset its
role. Use one backend process per H2 file database. H2 file locking remains enabled;
AUTO_SERVER and lock-disabling options are not added. Stop the backend before making
an ordinary filesystem backup of its database files.

Records that already disappeared when an old in-memory backend stopped cannot be
recovered by changing the URL. If the old in-memory process is still running and
contains records you need, arrange an explicit export/import before stopping it.
No automatic migration from a running memory DB is attempted.

## ADMIN bootstrap and OAuth compatibility

Google configuration, scopes, session protection, CSRF and RBAC are unchanged.
No default accounts, password login or automatic role promotions are introduced.
The existing one-time bootstrap flow now works with the normal local default:

1. With `SPOTPULSE_BOOTSTRAP_ADMIN_ID` unset, sign in with the intended account.
   A new identity still receives USER. Note its actual account ID.
2. Stop the backend, set that operator-controlled bootstrap variable to the reviewed
   account ID, and restart against the same file database.
3. Bootstrap promotes that existing Google-linked account only if no ADMIN exists.
   It does not assign legacy spaces or link accounts by email.
4. Stop, remove the bootstrap variable, restart, and sign in again. The ADMIN role
   persists. Leaving the bootstrap flag set on a later start is intentionally refused.

OWNER promotion and legacy ownership assignment remain explicit ADMIN actions.
See [C16/QA3](C16-QA3-oauth2-rbac.md) for the security/migration policy. This focused
document supersedes earlier assignment docs that described the local default as
in-memory or required opting into a file database before bootstrap.

## Separate empty demonstration database

Use a **new** external directory; do not delete the normal database:

```powershell
$spotpulseDemoDirectory = Join-Path $env:LOCALAPPDATA 'SpotPulse-Demo-Separate'
$env:SPOTPULSE_DATA_DIR = $spotpulseDemoDirectory
.\mvnw.cmd spring-boot:run
```

If the chosen directory already contains `spotpulse.mv.db`, its data is reused,
so pick another directory for an empty demo. An already-set `SPRING_DATASOURCE_URL`
takes precedence over this setting: keep it for the existing database, or deliberately
unset it in a separate demonstration terminal before selecting a different directory.
Remove `SPOTPULSE_DATA_DIR` in that terminal when returning to the normal default.
Do not set bootstrap to an ID from a different database.

## PostgreSQL and explicit overrides

`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and
`SPRING_DATASOURCE_PASSWORD` retain normal Spring precedence. Supply PostgreSQL
credentials through your existing secure environment, without printing them. Use an
actual URL such as `jdbc:postgresql://localhost:5432/spotpulse_db`. The application
now infers the driver and Hibernate dialect from the URL instead of globally forcing
H2. Existing explicit `SPRING_DATASOURCE_DRIVER_CLASS_NAME` and
`SPRING_JPA_DATABASE_PLATFORM` overrides remain supported.

Docker's existing PostgreSQL URL, driver, dialect and credentials configuration is
unchanged, and an explicit URL skips local path resolution entirely. No PostgreSQL
server or existing PostgreSQL data was modified during this work. Live PostgreSQL
execution was not tested; URL override behavior is covered automatically.

## Test isolation and automated verification

Existing integration tests keep their explicit in-memory URLs. The basic context
test now also specifies memory H2 and asserts its actual connection URL. In addition,
a **test-classpath-only** environment processor runs before Hibernate initializes:

- generated local defaults become a unique in-memory test database;
- explicit non-isolated file databases are rejected before connections/schema DDL;
- file persistence tests are allowed only inside their own newly created
  `spotpulse-persistence-*` directory directly under the OS temporary directory;
- a separate conflicting Hikari JDBC URL is rejected;
- the pre-existing external PostgreSQL test opt-in `SPOTPULSE_TEST_DATABASE_URL`
  requires a dedicated database whose name ends in `_test`. Never use application
  data for this destructive integration-test opt-in.

The guard and subprocess fixture entry point are compiled only for tests and are not
packaged in the production application. Tests cannot automatically fall back to the
developer's persistent default. Deliberately bypassing/replacing the test harness or
changing its safety rules is outside that guarantee.

`LocalDatabaseConfigurationTests` covers platform paths, the local profile, explicit
memory/PostgreSQL overrides, non-local profiles, rejected relative/checkout paths,
blank URLs, destructive local DDL and test isolation.
`LocalDatabasePersistenceTests` launches **three separate backend JVMs** against one
new temporary file DB: write fixtures, restart with explicit admin bootstrap, then
restart again without bootstrap and verify all records/roles/relationships and stable
OAuth subject mapping. Child processes do not receive real Google credentials. Every
context closes before the next starts. Cleanup verifies the random temporary root
and deletes only that test's database/log/marker files, never real application data.

From `spotpulse-backend`:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd '-Dtest=SecurityTests,SecurityCacheTests,AdminBootstrapTests' test
.\mvnw.cmd '-Dtest=LocalDatabaseConfigurationTests,LocalDatabasePersistenceTests,SpotpulseApplicationTests' test
```

Exact installed Maven/cache commands for this workspace:

```powershell
$mvn = 'C:/Users/Alexandra T/.m2/wrapper/dists/apache-maven-3.9.16-bin/5grr65jo27hi51sujmtcldfovl/apache-maven-3.9.16/bin/mvn.cmd'
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o clean test
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o '-Dtest=SecurityTests,SecurityCacheTests,AdminBootstrapTests' test
& $mvn '-Dmaven.repo.local=C:/Users/Alexandra T/.m2/repository' -o '-Dtest=LocalDatabaseConfigurationTests,LocalDatabasePersistenceTests,SpotpulseApplicationTests' test
```

No frontend code is changed, so frontend tests/build are not needed for this task.
No README, deployment file, authentication implementation or generated bin file was
modified. All earlier uncommitted changes were preserved. No commit or push was made.

## Files in this change

- Root `.gitignore`: exclude accidental H2 database artifacts.
- `src/main/resources/application.properties` and `application-local.properties`:
  default local profile and external-directory configuration; infer the database driver/dialect.
- `src/main/resources/META-INF/spring.factories` and
  `src/main/java/com/spotpulse/config/LocalDatabaseEnvironment.java`: register and
  resolve the safe local default after external configuration.
- `src/test/resources/META-INF/spring.factories` and
  `src/test/java/com/spotpulse/persistence/TestDatabaseIsolation.java`: test-only
  isolation before database initialization.
- `src/test/java/com/spotpulse/SpotpulseApplicationTests.java` and three new
  persistence helpers/tests (`LocalDatabaseConfigurationTests`,
  `LocalDatabasePersistenceTests`, `LocalDatabaseProcessProbe`): configuration,
  isolation and real process-restart verification.
- This document.

## Verification results (2026-10-26)

- Clean backend compilation and full suite (`clean test`): **133 tests, zero
  failures, zero errors, zero skipped**.
- Dedicated security/cache-security/admin-bootstrap run: **20 tests, zero
  failures, zero errors, zero skipped** (`BUILD SUCCESS`).
- Dedicated configuration/persistence/context run: **8 tests passed**. The full
  suite repeated these checks, including the normal default profile without an
  explicit active-profile flag.
- The process-restart test used three separate JVMs and two restarts against one
  isolated temporary H2 file database. Google provider/subject mappings, USER,
  OWNER and ADMIN roles, ownership, room and booking records survived. Bootstrap
  promoted only the explicitly selected existing Google-linked fixture account;
  the following restart retained its role without a bootstrap setting.
- Only the newly created temporary test database directory was cleaned up. The
  developer's normal database and PostgreSQL databases were not opened or modified.
- Frontend verification was not run because this change contains no frontend edits.

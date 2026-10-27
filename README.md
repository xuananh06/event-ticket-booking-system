# Event Ticket Booking System

A Java web project for event ticket booking and seat reservation. The application provides account security, reference/account administration, organizer-owned event setup and publication, and public event/seat browsing. The SQL Server schema also supports the planned booking domain.

## Capabilities

Implemented:

- Customer registration, login for three roles, and POST-only logout.
- Bcrypt password hashing, session ID rotation, session-bound CSRF protection, and current account/role checks on protected requests.
- English, responsive Bootstrap views with shared navigation, field errors, safe input retention, UTF-8, and escaped user content.
- Admin category, venue, physical-seat and account management, active-state controls and guarded deletion.
- Organizer-owned draft event CRUD and read-only access to other owned event statuses, using Asia/Bangkok forms and UTC storage.
- Public upcoming published-event lists/details, literal title search, combined category/venue/date filters and pagination.
- Additive per-event seat generation, exact VND pricing, atomic selected-seat price changes and guarded publication.
- Read-only paged seat layouts, effective availability counts and available-price ranges, without customer/booking-owner disclosure.
- External database configuration and a bounded, read-only connectivity check at application startup.
- A nine-table SQL Server schema with relational constraints, query indexes, demo accounts, events, and seat inventory.
- Unit tests, real SQL Server schema/registration tests, and isolated Tomcat HTTP checks with independent cookie sessions.

Planned capabilities, not yet available as application workflows:

- Timed seat reservations, booking management, simulated payments, cancellations, and dashboards.

Role welcome pages retain account information and navigation to the implemented management screens. Booking controls and statistics are not available.

## Requirements

- JDK 17 and Maven 3.6.3 or later. Confirm `java -version` and `mvn -version` use Java 17.
- Apache Tomcat **10.1.44**.
- Microsoft SQL Server **2022** with an accessible TCP endpoint. SSMS and `sqlcmd` are optional administration clients.
- A dedicated empty database and permission to create application tables and indexes. Use a separately provisioned, least-privilege database identity for normal application operation.

The application uses Jakarta Servlet, JSP/JSTL/EL, and JDBC with manual constructor injection. It does not use Spring or an ORM.

After cloning the repository, run the examples from its root directory in PowerShell. Replace the sample installation paths with your own. The database examples use a dedicated database named `event_booking_test`; it does not need to exist to build the WAR or run the page without database configuration.

| Dependency | Pinned version | Scope |
| --- | --- | --- |
| Jakarta Servlet API | 6.0.0 | Provided by Tomcat |
| Jakarta EL API | 5.0.0 | Provided by Tomcat |
| Jakarta Tags API / implementation | 3.0.2 / 3.0.1 | Packaged in WAR |
| Microsoft JDBC Driver | 12.10.2.jre11 | Packaged in WAR |
| JUnit Jupiter | 5.12.2 | Tests |
| Favre bcrypt | 0.10.2 | Packaged in WAR; also used by seed tooling/tests |
| Bootstrap CSS | 5.3.8 | Vendored under `assets/vendor`, with its MIT license header |

Build plugin versions are pinned in `pom.xml`. Tomcat 10.1 implements Servlet 6.0, JSP 3.1, and EL 5.0; it does not supply JSTL. The JDBC `jre11` artifact supports Java 17. References: [Tomcat compatibility](https://tomcat.apache.org/migration-10.1.html), [Jakarta Tags](https://jakarta.ee/specifications/tags/3.0/), [Microsoft JDBC requirements](https://learn.microsoft.com/en-us/sql/connect/jdbc/system-requirements-for-the-jdbc-driver), [Microsoft JDBC release](https://github.com/microsoft/mssql-jdbc/releases/tag/v12.10.2), [bcrypt library](https://github.com/patrickfav/bcrypt), [JUnit](https://docs.junit.org/5.12.2/user-guide/).

## Database setup

Select a dedicated empty user database. For a local Windows-authenticated test installation, create a new database explicitly:

```powershell
& {
    sqlcmd -S localhost -E -b -Q "IF DB_ID(N'event_booking_test') IS NOT NULL THROW 51000, 'Database already exists; creation refused.', 1; CREATE DATABASE [event_booking_test];"
    if ($LASTEXITCODE -ne 0) { throw 'Database creation failed; setup stopped.' }
    sqlcmd -S localhost -E -d event_booking_test -b -f 65001 -i database/schema.sql
    if ($LASTEXITCODE -ne 0) { throw 'Schema creation failed; setup stopped.' }
    sqlcmd -S localhost -E -d event_booking_test -b -f 65001 -i database/seed.sql
    if ($LASTEXITCODE -ne 0) { throw 'Seed loading failed.' }
}
```

Check each command's exit code before continuing. In SSMS, select the intended database in the database selector, open each UTF-8 script, and execute schema before seed. For SQL authentication, use `sqlcmd -U your_login` with an interactive password prompt rather than putting a password in command arguments. Database creation requires separate permissions; the application never creates databases, users, or logins.

Both scripts refuse system databases. Schema creation is transactional and refuses to run when any of the nine application tables already exists. Seed is transactional and refuses to run if any application table contains rows. Reruns preserve existing rows and return errors; they are not migrations or data refresh commands. Execute setup without concurrent application traffic. Neither script drops, truncates, or deletes existing data.

Scripts are each a single T-SQL batch without `GO`, so JDBC can execute each complete script. `GO` is an SSMS/sqlcmd batch separator, not a T-SQL statement; do not add it to a script passed directly to JDBC.

The schema has nine tables: `users`, `categories`, `venues`, `seats`, `events`, `bookings`, `event_seats`, `booking_items`, and `payments`. It includes:

- `BIGINT IDENTITY` keys, Unicode `NVARCHAR`, `DECIMAL(18,2)` VND amounts, and UTC `DATETIME2(3)` fields.
- Composite foreign keys for event/seat venue consistency, booking/item event consistency, and current inventory ownership.
- Explicit inventory owner/expiry shapes, booking/payment timestamp shapes, nonnegative amounts, and a maximum five-minute booking deadline.
- Unique email, physical layouts, per-event seats, booking codes, per-booking seat items, and per-booking payment keys.
- A filtered unique index on successful payment history, retained after refund, and indexes for event searches, booking history, and inventory availability.
- No cascading deletion of transaction history. Historical items may reference the same seat in different bookings.

Email must equal SQL Server `LOWER(LTRIM(RTRIM(email)))`, without leading/trailing ASCII spaces. Its explicit collation is `Latin1_General_100_CI_AS_SC`: uniqueness is case insensitive and accent sensitive. Public account forms accept ASCII email addresses and normalize them with locale-independent lowercase before database access. Statuses use binary collation and reject trailing spaces. Constraints do not establish booking ownership rules, matching item totals and hold deadlines, minimum item counts, valid state transitions, or concurrency-safe allocation. Those booking workflows are not implemented.

### Demo data and password format

Seed creates three accounts, two categories, one venue, eight physical seats, two events sharing the venue, and sixteen independent event-seat rows. Events start relative to `SYSUTCDATETIME()`. One is PUBLISHED and one DRAFT. Booking/item/payment tables remain empty.

| Account | Role | Public demo password |
| --- | --- | --- |
| `admin@example.test` | ADMIN | `DemoOnly!ChangeMe` |
| `organizer@example.test` | ORGANIZER | `DemoOnly!ChangeMe` |
| `customer@example.test` | CUSTOMER | `DemoOnly!ChangeMe` |

Use these public accounts on the sign-in page of a seeded test installation. They are application accounts, separate from database connection credentials. Keep demo accounts out of a public deployment. New seed installations use English demo labels; existing account names and other stored records are preserved when the application is updated.

Seed and application password format: Favre bcrypt 0.10.2, `$2a$` format, cost **12**, independent cryptographic random salts, and 60-character encoded hashes. The schema enforces this format; changing algorithm or cost requires a schema migration. Registration and the offline helper accept passwords of 8-72 UTF-8 bytes, without trimming or truncation. Unit tests check the actual seed hashes with the correct and incorrect password.

Generate replacement hashes offline, from the repository root:

```powershell
mvn -B test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/test-classpath.txt'
$testClasspath = Get-Content -Raw target/test-classpath.txt
java -cp "target/test-classes;target/classes;$testClasspath" com.eventbooking.tools.DemoPasswordHashes
# Or generate three independently salted hashes of the public demo password:
java -cp "target/test-classes;target/classes;$testClasspath" com.eventbooking.tools.DemoPasswordHashes --demo
```

The interactive command reads without echo in a terminal. It does not accept passwords in command arguments. The helper and test libraries are excluded from the WAR; bcrypt and its runtime dependency are included for authentication.

## Account usage

Open the application root, then select **Create account** or **Sign in**. Registration always creates an active CUSTOMER, regardless of submitted role or activation fields. Successful registration redirects to sign-in with a one-time success message; it does not automatically authenticate the account.

Names support Unicode and allow 1-150 UTF-16 code units after stripping outer whitespace. Control characters are rejected. Email addresses allow at most 254 ASCII characters, including at most 64 before `@`. Password confirmation must match exactly. Validation retains only the name and email; password fields remain empty. Client validation supplements the server checks.

| Route, relative to the deployed context | Behavior |
| --- | --- |
| `GET /` | Public welcome page |
| `GET /register`, `POST /register` | Registration form and customer creation |
| `GET /login`, `POST /login` | Sign-in form and authentication |
| `POST /logout` | Invalidate the current session and return home; GET returns 405 |
| `GET /customer/home` | CUSTOMER welcome page |
| `GET /organizer/home` | ORGANIZER welcome page |
| `GET /admin/home` | ADMIN welcome page |
| `GET /health` | Generic, cached database startup snapshot as JSON |
| `GET /admin/categories`, `/admin/venues`, `/admin/seats` | Paged Admin reference lists and create/edit forms; optional edit `id` |
| `POST /admin/categories`, `/admin/venues`, `/admin/seats` | Explicit `save`, `status` or `delete` actions |
| `GET /admin/users`, `POST /admin/users` | Admin account list/search/forms and explicit management actions |
| `GET /organizer/events`, `POST /organizer/events` | Organizer-owned list/details/forms and draft save/delete actions |
| `GET /events`, `GET /events/detail?id=...` | Public eligible-event list/details |
| `GET /organizer/events/inventory?eventId=...` | Owned inventory, pricing and publication readiness |
| `POST /organizer/events/inventory` | Explicit `generate`, `price` or confirmed `publish` actions |
| `GET /events/seats?eventId=...` | Read-only eligible public seat layout, prices and effective availability |

Guests requesting protected pages redirect to sign-in. A signed-in user requesting another role's page receives 403. The application rereads the current role and active status from the database rather than trusting a cached role. Disabled/deleted identities are invalidated. Unknown accounts, wrong passwords, and inactive accounts receive the same credential error. Database failures produce a generic 503 for database-dependent actions.

Forms submit session-bound `_csrf` values in the request body, including registration, login, and logout. Keep cookies enabled. Missing, invalid, cross-session, or stale tokens return 403; reopen the form after expiry. Authentication changes the session ID and CSRF token. Successful actions use HTTP 303 redirects to fixed application destinations; credentials and tokens are never added to redirect URLs.

## Management and event browsing

Admin navigation provides Categories, Venues, Seats and Accounts. Lists include inactive records and accept page/state filters. Categories have unique names; seats have a unique venue/row/number combination. Deletion removes only unreferenced records. Database references produce a controlled 409 conflict, preserving related records/history. A category or venue required by an upcoming published event cannot be deactivated. Seats used by event inventory cannot be moved, relabeled, deactivated or deleted through management.

Account creation accepts a valid role, initial state and confirmed password. Profile/role/state edits preserve the hash. Administrators cannot disable, delete or demote themselves or remove the last active administrator. Event owners must retain ORGANIZER and booking owners must retain CUSTOMER. Referenced users may be disabled when permitted, with history retained. Every mutation revalidates the actor inside its transaction.

Organizers can view only their own management records and mutate only DRAFT events. New events are always DRAFT; submitted owner/status fields cannot publish or transfer them through an ordinary save. Drafts with inventory/bookings cannot be deleted or moved to another venue. New assignments require active categories/venues; unchanged inactive references can remain on an existing draft and their labels remain visible. Descriptions are escaped plain text with preserved line breaks and a 10,000 UTF-16-unit limit. Titles allow up to 200 units. Drafts must start in the future and end after they start. Forms/display use Asia/Bangkok; JDBC stores UTC DATETIME2 with millisecond precision. Inventory setup and publication have stricter guards described below.

Anyone can browse `/events`. SQL visibility requires PUBLISHED, a start strictly after database UTC time, and an active ORGANIZER/category/venue. Draft/past/closed/cancelled/inactive records and missing events share the public 404 detail response. Management ownership checks apply independently.

Event filters are `q` (literal title text, at most 100 characters), `category`, `venue`, inclusive local `from`/`to` dates, `page` and `size`. Default size is 10, maximum 50; pages are positive and capped at 1,000,000. Ordering uses start time and ID. Count/list use the same captured database time and predicates. Empty or beyond-last pages show a clear empty state. Suggestions include up to 50 reference records; ID fields accept valid identifiers beyond those suggestions. Pagination retains encoded filters under the actual deployment context.

Mutations submit `action`, fields and CSRF in the POST body without action query strings. GET can load a form but never saves, deletes or changes active state. Validation returns 400, role failures 403, missing/not-owned records 404, integrity/business conflicts 409 and unavailable services 503. Management writes use short SERIALIZABLE transactions and a transaction-owned SQL Server application lock. This conservative serialization favors correctness for this project over high write throughput.

## Seat inventory, pricing and publication

Open **Manage events**, select an owned event, then **Seat inventory and prices**. Only an active ORGANIZER can change their own future DRAFT event with active category/venue and valid stored details. Admin has no organizer override. Events with booking/item history or inventory that is HELD, SOLD or has an owner/deadline cannot be reconfigured, even after a hold expires.

**Generate missing seats** copies currently active physical seats from the event venue. Enter an explicit initial price for new rows. Existing inventory IDs, prices and states are preserved; repeating generation with no missing seats reports a no-op. An empty active-seat source is rejected. After Admin adds physical seats to a draft's venue, generation can add those missing seats. No action deletes/rebuilds existing inventory.

Prices use exact `BigDecimal` / `DECIMAL(18,2)` amounts in VND. Enter a nonnegative ordinary decimal, up to `9999999999999999.99`, with at most two fractional digits; explicit `0` is valid for free seats. Input is bounded to 64 characters after stripping surrounding whitespace. Missing values, signs, exponent notation, separators, nonfinite values and excess fractional precision are rejected without rounding. Update one seat using its row form, or select 1-50 distinct seats on the current page and submit one batch price. Every inventory ID must belong to the owned event; any invalid member rejects the entire batch. Events sharing a venue keep independent prices and inventory.

The publication section displays readiness or concrete reasons. Confirm that event details, inventory and prices are final, then submit **Publish event**. The server locks/rechecks current data and requires DRAFT, active parents/organizer, valid future times, nonempty pristine inventory, no history and exact one-to-one coverage of the venue's currently active physical seats. Database UTC time is checked again at the guarded status update. Repeated publication returns a controlled conflict. Published details, venue, inventory membership and prices are read-only. Later physical-seat additions do not automatically change a published event's frozen inventory.

Public event details link to **View seat availability and prices**. The same future/PUBLISHED/active-parent visibility rules apply to guessed seat-page IDs. Layout pages show real row labels, physical numbers and inventory identity; sparse numbers do not create empty grid cells. Page/size bounds match event browsing, with at most 50 seats per page. Counts cover the full inventory and the price range covers effectively available seats only.

AVAILABLE is available; HELD is held only while its expiry is strictly after captured database UTC time; equality or an expired hold is effectively available. SOLD or inactive/mismatched physical seats are unavailable. The legend and text states supplement color. Public reads never rewrite stored states, expose customer/current-booking identity or create reservations. Availability is informational; seat selection, holds, checkout and payment are not implemented.

## External configuration

`.env.example` lists the supported environment variables. **The application and Tomcat do not automatically load `.env` files.** Set variables in the process environment before starting Tomcat, or use the deployment environment's secret configuration. Keep real credentials outside source files, command arguments, and the deployed WAR.

| Variable | Default / requirement |
| --- | --- |
| `EVENT_DB_HOST` | Required when database configuration is present; server hostname |
| `EVENT_DB_PORT` | `1433`; integer from 1 to 65535; use the instance's actual TCP port |
| `EVENT_DB_NAME` | Required; pre-created application database |
| `EVENT_DB_AUTH` | `sql-password` or `windows-integrated`; default `sql-password` |
| `EVENT_DB_USER` | Required for SQL authentication; absent for Windows authentication |
| `EVENT_DB_PASSWORD` | Required for SQL authentication; absent for Windows authentication |
| `EVENT_DB_ENCRYPT` | `true` |
| `EVENT_DB_TRUST_SERVER_CERTIFICATE` | `false` |
| `EVENT_SESSION_TIMEOUT_SECONDS` | `1200` (20 minutes); integer from 1 to 86400 |
| `EVENT_SESSION_COOKIE_SECURE` | `false` for local HTTP; set `true` for HTTPS deployment |

Boolean settings accept only `true` or `false`, ignoring case. With no `EVENT_DB_*` variables, home/health/assets and auth forms remain usable. Database-dependent authentication, catalog and management operations fail safely; valid auth submissions and catalog requests return generic 503 responses. Partial or malformed configuration fails initialization with setting names only. `GET /health` returns HTTP 200 with the cached `NOT_CONFIGURED`, `AVAILABLE` or `UNAVAILABLE` startup snapshot. `AVAILABLE` proves only a bounded startup `SELECT 1`, not schema readiness or continuing health. Use-case connections can fail independently. Restart after changing configuration.

Sessions use cookie-only tracking, HttpOnly, and SameSite=Lax. Only the user ID is stored as authenticated identity, alongside CSRF state and optional UI success messages. The configured timeout is an inactivity timeout. Use HTTPS and `EVENT_SESSION_COOKIE_SECURE=true` for deployment; Secure cookies will not work over ordinary local HTTP. TLS termination and trusted proxy/connector configuration belong to the Tomcat/reverse-proxy setup; this application does not configure TLS or force HTTPS redirects.

Example for SQL authentication in PowerShell:

```powershell
$env:EVENT_DB_HOST = 'localhost'
$env:EVENT_DB_PORT = '1433'
$env:EVENT_DB_NAME = 'event_booking_test'
$env:EVENT_DB_AUTH = 'sql-password'
$databaseCredential = Get-Credential -Message 'SQL Server application login'
$env:EVENT_DB_USER = $databaseCredential.UserName
$env:EVENT_DB_PASSWORD = $databaseCredential.GetNetworkCredential().Password
```

Windows authentication uses the identity running the JVM and requires the matching Microsoft native library for its architecture:

```powershell
$env:EVENT_DB_HOST = 'localhost'
$env:EVENT_DB_PORT = '1433'
$env:EVENT_DB_NAME = 'event_booking_test'
$env:EVENT_DB_AUTH = 'windows-integrated'
Remove-Item Env:EVENT_DB_USER, Env:EVENT_DB_PASSWORD -ErrorAction SilentlyContinue
```

Download `mssql-jdbc_auth.zip` from the [official driver release](https://github.com/microsoft/mssql-jdbc/releases/tag/v12.10.2). Extract it outside `target` if using `mvn clean`. The examples use `C:\tools\mssql-jdbc-auth\x64`, containing `mssql-jdbc_auth-12.10.2.x64.dll`, for an x64 JVM. The DLL is not packaged in the WAR. Windows authentication requires the JVM's Windows identity to have database access; it does not require a SQL-authentication username or password.

Use a trusted SQL Server certificate matching the configured hostname. For an isolated local test with a self-signed certificate, explicitly setting `EVENT_DB_TRUST_SERVER_CERTIFICATE=true` keeps encryption but bypasses certificate validation; keep the default `false` for normal deployment. The configured host and port must match the SQL Server instance's accessible TCP endpoint.

`DataSourceFactory` uses typed driver setters and bounded login/socket timeouts. `SQLServerDataSource` is a connection factory, not a connection pool. The bootstrap injects dependencies through constructors. The probe and DAO acquire fresh connections and close result sets, statements, and connections with try-with-resources; no JDBC connection is retained between requests. Authentication uses parameterized queries; application permissions need SELECT and the relevant INSERT/UPDATE/DELETE access for implemented management, plus permission to request the database application lock. Application code does not require schema creation permissions. Integration tests require additional permissions for their test fixtures and schema checks.

## Build and deployment

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
jar tf target/event-booking.war
```

The WAR contains the application, protected JSP views, local Bootstrap/shared CSS, JSTL API/implementation, JDBC driver, and bcrypt runtime libraries. Servlet/EL APIs, test libraries/tools, native DLLs, credentials, and database scripts are excluded. Maven uses `--release 17` and UTF-8 for compilation.

For a standalone Windows installation from the Tomcat ZIP distribution, set any database variables in the same shell, then deploy the WAR:

```powershell
$env:CATALINA_HOME = 'C:\tools\apache-tomcat-10.1.44'
$env:CATALINA_BASE = $env:CATALINA_HOME
# Windows integrated authentication only; use the native library directory above.
$env:JAVA_OPTS = ($env:JAVA_OPTS + ' "-Djava.library.path=C:\tools\mssql-jdbc-auth\x64"').Trim()
Copy-Item -LiteralPath target/event-booking.war -Destination (Join-Path $env:CATALINA_BASE 'webapps\event-booking.war')
& "$env:CATALINA_HOME\bin\catalina.bat" run
```

Omit the `JAVA_OPTS` line for SQL password authentication or a page without database configuration. `JAVA_HOME` must point to JDK 17. Tomcat's startup scripts also read `CATALINA_BASE/bin/setenv.bat`; restrict access to any external configuration containing secrets. A Windows service uses its own account, environment, and JVM settings and does not run `catalina.bat` or inherit variables set in a separate PowerShell session.

Open `http://localhost:8080/event-booking/` for the default connector/context. Servlets forward to JSP views under `WEB-INF`; direct JSP access returns 404. Shared Bootstrap views work without external CDNs or JavaScript. Requests and responses use UTF-8; JSP output uses `c:out` escaping. The product interface is English, while user names and passwords retain their Unicode content.

For automatic deployment into `webapps`, Tomcat derives the context path from the WAR filename, so `event-booking.war` maps to `/event-booking`. The `path` attribute in the bundled `META-INF/context.xml` does not override that naming rule. An IDE or explicit server deployment configuration may use another context path. See [Tomcat context naming](https://tomcat.apache.org/tomcat-10.1-doc/config/context.html#Naming).

### Isolated Tomcat smoke verification

The Windows script requires readable Tomcat 10.1.44 binaries/configuration, JDK 17, and an already-built `target/event-booking.war`. It inherits database variables from the launching shell. Parameters:

- `-TomcatHome`: required Tomcat installation directory.
- `-JavaHome`: defaults to `JAVA_HOME`.
- `-JdbcNativeDirectory`: optional directory containing the matching native JDBC library.
- `-ExpectedDatabaseStatus`: `NOT_CONFIGURED` (default), `AVAILABLE`, or `UNAVAILABLE`.
- `-ContextName`: WAR deployment name, default `event-booking`; letters, digits, underscores, and hyphens are accepted, starting with a letter or digit, up to 50 characters. The reserved root-context name `ROOT` is not supported by this script.
- `-AuthenticationTests`: additionally run authentication, management/catalog and inventory/publication/availability JUnit HTTP scenarios through Maven `http-it`. Requires Maven on PATH. With `AVAILABLE`, also requires seeded accounts, fixture-write database access and matching `EVENT_TEST_DB_CONFIRM` / `EVENT_DB_NAME`.

#### PowerShell execution policy

Windows PowerShell may block `.ps1` files before the smoke test starts, with errors such as "running scripts is disabled" or "not digitally signed." Inspect policy using the same `powershell` executable used by the examples:

```powershell
powershell -NoProfile -Command "Get-ExecutionPolicy -List"
```

For a trusted local copy, and only where local testing is permitted, the following commands use `-ExecutionPolicy Bypass` for the new PowerShell process. This does not persistently change execution policy or write policy settings to the registry. Group Policy settings (`MachinePolicy` or `UserPolicy`) take precedence. Respect organizational restrictions; if they prohibit execution, contact your administrator rather than changing machine-wide policy or attempting to override them. References: [Windows PowerShell execution policies](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1), [powershell.exe parameters](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-5.1#-executionpolicy-executionpolicy).

Without database configuration, clear only the application database variables in the current shell and expect `NOT_CONFIGURED`:

```powershell
Get-ChildItem Env:EVENT_DB_* | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:\tools\apache-tomcat-10.1.44' -JavaHome 'C:\Program Files\Java\jdk-17' -ExpectedDatabaseStatus NOT_CONFIGURED -AuthenticationTests
```

With the Windows-authentication configuration above, an accessible database, and the matching native JDBC DLL:

```powershell
$env:EVENT_TEST_DB_CONFIRM = $env:EVENT_DB_NAME
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:\tools\apache-tomcat-10.1.44' -JavaHome 'C:\Program Files\Java\jdk-17' -JdbcNativeDirectory 'C:\tools\mssql-jdbc-auth\x64' -ExpectedDatabaseStatus AVAILABLE -ContextName campus-auth -AuthenticationTests
```

If you ran the first example, reapply the database environment configuration before running the second. Use `-ExpectedDatabaseStatus UNAVAILABLE` with deliberately unreachable local settings to check the generic startup status and authentication failure behavior. For SQL password authentication, omit `-JdbcNativeDirectory` and set the SQL username/password environment variables instead. Omit `-AuthenticationTests` to run only the basic smoke assertions.

The script uses the supplied Tomcat binaries, validates version and Java runtime, creates a separate base under `target`, listens on an ephemeral loopback port, deploys the WAR, and checks HTTP 200, UTF-8, JSTL/EL, local assets, the expected startup snapshot, protected JSP 404, unknown route 404, and root POST 405. It stops only its disposable JVM and retains response/log evidence. It does not start or alter an installed Tomcat service. With `-AuthenticationTests`, only this disposable JVM uses a five-second session timeout and non-Secure cookies for HTTP/expiry checks; the launching environment is restored. These are HTTP deployment checks; inspect browser rendering separately.

## Tests

`mvn -B clean verify` runs database-independent unit tests for configuration, JDBC resource cleanup, validation, hashing, generic errors, CSRF, session settings, and role filters, verifies seed passwords, and builds the WAR. It does not connect to SQL Server or start Tomcat.

Run database integration tests explicitly against a dedicated SQL Server 2022 database already initialized with schema and seed:

```powershell
# With SQL password authentication configured above:
$env:EVENT_TEST_DB_CONFIRM = $env:EVENT_DB_NAME
mvn -B clean verify -Psqlserver-it
```

For Windows integrated authentication, use this command instead after configuring that mode:

```powershell
$env:EVENT_TEST_DB_CONFIRM = $env:EVENT_DB_NAME
mvn -B clean verify -Psqlserver-it '-DargLine=-Djava.library.path=C:\tools\mssql-jdbc-auth\x64'
```

The profile requires matching `EVENT_TEST_DB_CONFIRM` and `EVENT_DB_NAME`, complete settings, connectivity and SQL Server major version 16. Missing prerequisites fail rather than skip tests. Use a dedicated test database with the original seed contents and sample events still in the future. Schema fixture transactions roll back. Registration/management tests commit uniquely owned fixtures to exercise real connections, then delete only their exact owned rows. Identity counters may advance. Existing users and business records are not reset or reseeded. The last-admin concurrency test creates a uniquely named isolated schema with fixture tables and two administrators, routes the production JDBC SQL to those tables, then drops only that owned schema/tables. Test credentials need CREATE SCHEMA/TABLE and cleanup permissions for this test; normal application credentials do not.

Schema checks cover JDBC/Unicode, trusted constraints, both venue foreign keys, booking/item/current-owner event matching, inventory shapes, historical seat references, payment key scope, success uniqueness after refund, normalized email, amount/time checks, deletion protection, and script rerun refusal. Registration checks cover persistence, compatible stored hashes, normalization, duplicate mapping, and one winner from competing inserts. Schema fixtures do not implement booking/payment application workflows. Management SQL checks additionally cover duplicate category/seat/email values, actor and reference guards, last-admin concurrency with two independent connections, role/history restrictions, draft ownership/status predicates, UTC persistence, inactive references, rollback on errors, public visibility, literal/combined filtering and stable pagination.

Inventory SQL checks additionally cover additive/repeated generation, exact/free prices, mixed-ID batch atomicity, publication coverage/history guards, independent event prices, real connection races with pricing/generation/reference edits/publication, delayed publication across its start boundary, deterministic hold-expiry equality, public visibility, full-count paging and non-mutating availability reads. HELD/SOLD/booking-item rows are test-owned fixtures; no application booking endpoint is implied.

The script's `-AuthenticationTests` switch runs three JUnit HTTP scenarios with independent cookie sessions. Authentication checks registration, validation/escaping, three seeded role logins, all nine role-route pairs, negative/stale CSRF, session rotation/replay/logout, inactive/changed accounts, expiry and logout from authenticated POST error pages. Management/catalog checks real rendered forms, reference/account/event CRUD, role/ownership and credential-field tampering, CSRF rejection, public visibility, encoded paging and invalid inputs. Inventory checks rendered generation/pricing/publication forms, immutable setup, ownership/CSRF/tampering, effective public availability and validation/conflict logout. Without an available database they check foundation availability and generic auth/catalog/inventory 503 responses. Seeded accounts are used for login only. Fixture users have `auth-<UUID>-...@example.test` emails; reference data uses a `Fixture <UUID>` label. An abruptly terminated test may leave owned fixtures or an isolated `p3_admin_<UUID>` schema; inspect exact ownership before manual cleanup.

Surefire reports are in `target/surefire-reports`; Failsafe reports are in `target/failsafe-reports`. Each HTTP invocation replaces its scenario report. Tomcat smoke logs and the captured anonymous response are under `target/tomcat-smoke-*/logs`; auth form bodies, passwords, cookies, and tokens are not written as evidence. `mvn clean` removes `target`, including those artifacts and any native library extracted there. Stop any manually launched JVM using that directory before cleaning on Windows. A successful build alone does not establish deployment or database correctness.

## Technical limitations

Seat allocation, holds, checkout, booking/payment/cancellation workflows and dashboards are not available. Authentication has no email verification, password recovery/change, MFA or rate limiting. Bootstrap CSS is local; forms require no JavaScript. Reference suggestions and inventory pages are capped at 50; explicit reference IDs and paging support larger data sets. Management writes are deliberately serialized. Availability uses one captured database time and identical predicates across separate read statements, rather than promising a snapshot-isolated view during external writes. HTTPS/proxy setup, production permission provisioning, pooling, load testing and a broader browser/accessibility matrix require deployment-specific work.

## Troubleshooting

| Symptom | Meaning and next step |
| --- | --- |
| PowerShell reports scripts are disabled or the script is not signed | Script loading was blocked before the smoke test ran. Inspect execution policy using the command above; this is not an application, database, or Tomcat test result. |
| The script reports a missing WAR or wrong Tomcat/Java version | The script started but its prerequisites failed. Build the WAR and correct `-TomcatHome`/`-JavaHome`. |
| `NOT_CONFIGURED` when a database was expected | The launching process has no `EVENT_DB_*` variables. Set them in the same shell or service environment; copying `.env.example` alone does not configure the application. |
| `UNAVAILABLE` | The application started but its database probe failed. Check host/TCP port, database existence, account permissions, authentication mode, certificate trust, and the native DLL/JVM architecture for Windows authentication. The status intentionally omits driver details. |
| Sign-in/registration returns 503 although `/health` says `AVAILABLE` | The snapshot only checked connectivity at startup. Check current database access, the initialized schema, and permissions; retry after the service is restored. |
| A form returns 403 | Reopen it to obtain a token for the current session. Keep cookies enabled; do not reuse a pre-login token or put form values in a query string. |
| Sign-in repeatedly returns to the form on local HTTP | Check whether Secure cookies are enabled. Use `EVENT_SESSION_COOKIE_SECURE=false` for the isolated HTTP test and `true` with HTTPS deployment. |
| A protected page returns 403 or redirects to sign-in | Check the account's current role/active status and session expiry. Navigation links do not grant access. |
| Application initialization fails after adding database settings | Partial settings, invalid booleans/port, or mixed authentication modes are rejected. Check required variable names and remove SQL username/password variables when using Windows authentication. |
| HTTP deployment check fails after the script starts | Inspect `target/tomcat-smoke-*/logs` for deployment/JSP/startup errors and compare the expected database status. Changing execution policy does not fix these failures. For manual deployment, inspect `CATALINA_BASE/logs` and confirm the context path. |
| Schema or seed rerun is refused | This is the scripts' data-preservation guard. Use an explicitly selected empty database for initial setup; rerunning is not a migration or reset operation. |
| Integration tests fail with modified or old demo data | The suite expects the original seed contents and future sample events. Initialize a separate empty test database rather than refreshing existing data automatically. |
| `mvn clean` cannot delete a Tomcat log under `target` | Stop the manually launched test JVM holding the file, then rerun clean. The smoke script normally stops its own process in `finally`. |

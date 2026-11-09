# Event Ticket Booking System

A Java web project for event ticket booking and seat reservation. The application provides account security, reference/account administration, organizer-owned event setup and publication, public event/seat browsing, Customer reservations, simulated payment and booking management.

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
- Customer selection of 1-50 seats, atomic PENDING bookings and five-minute holds using SQL Server time.
- Ownership-protected checkout with exact price/seat snapshots, effective expiry and safe release of expired holds.
- Explicit successful/failed payment simulation, idempotent replay and atomic whole-booking confirmation.
- Owner-only paged booking/payment history, effective status filters and booking details.
- Whole-booking cancellation before event start and full simulated refunds, preserving transaction history and later seat owners.
- All-time Customer booking counts, Organizer-owned event/sales statistics and Admin system-wide totals.
- External database configuration and a bounded, read-only connectivity check at application startup.
- A nine-table SQL Server schema with relational constraints, query indexes, demo accounts, events, and seat inventory.
- Unit tests, real SQL Server schema/registration tests, and isolated Tomcat HTTP checks with independent cookie sessions.

Planned capabilities, not yet available as application workflows:

- Real payment providers, QR tickets/check-in and partial refunds are not implemented.

Role home pages retain account information and navigation alongside actual all-time statistics. Customers enter reservation selection from an eligible event's public seat page.

## Requirements

- JDK 17 and Maven 3.6.3 or later. Confirm `java -version` and `mvn -version` use Java 17.
- Apache Tomcat **10.1.44**.
- Microsoft SQL Server **2022** with an accessible TCP endpoint. SSMS and `sqlcmd` are optional administration clients.
- A dedicated empty database and permission to create application tables and indexes. Use a separately provisioned, least-privilege database identity for normal application operation.

The application uses Jakarta Servlet, JSP/JSTL/EL, and JDBC with manual constructor injection. It does not use Spring or an ORM.

After cloning the repository, run the examples from its root directory in PowerShell. Replace the sample installation paths with your own. The database examples use a dedicated database named `event_booking_p1_test`; it does not need to exist to build the WAR or run the page without database configuration.

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

Select a dedicated empty user database. For a SQL-authenticated test installation, use an existing setup login authorized to create the database/schema. Replace setup_login below; sqlcmd prompts for its password:

```powershell
& {
    sqlcmd -S localhost -U setup_login -b -Q "IF DB_ID(N'event_booking_p1_test') IS NOT NULL THROW 51000, 'Database already exists; creation refused.', 1; CREATE DATABASE [event_booking_p1_test];"
    if ($LASTEXITCODE -ne 0) { throw 'Database creation failed; setup stopped.' }
    sqlcmd -S localhost -U setup_login -d event_booking_p1_test -b -f 65001 -i database/schema.sql
    if ($LASTEXITCODE -ne 0) { throw 'Schema creation failed; setup stopped.' }
    sqlcmd -S localhost -U setup_login -d event_booking_p1_test -b -f 65001 -i database/seed.sql
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

Email must equal SQL Server `LOWER(LTRIM(RTRIM(email)))`, without leading/trailing ASCII spaces. Its explicit collation is `Latin1_General_100_CI_AS_SC`: uniqueness is case insensitive and accent sensitive. Public account forms accept ASCII email addresses and normalize them with locale-independent lowercase before database access. Statuses use binary collation and reject trailing spaces. Constraints alone do not establish booking ownership, matching item totals/deadlines, minimum item counts or safe allocation; reservation service transactions enforce those rules alongside the retained constraints.

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
| `GET /customer/home` | CUSTOMER account and owned booking statistics |
| `GET /organizer/home` | ORGANIZER account and owned event/sales statistics |
| `GET /admin/home` | ADMIN account and system-wide statistics |
| `GET /health` | Generic, cached database startup snapshot as JSON |
| `GET /admin/categories`, `/admin/venues`, `/admin/seats` | Paged Admin reference lists and create/edit forms; optional edit `id` |
| `POST /admin/categories`, `/admin/venues`, `/admin/seats` | Explicit `save`, `status` or `delete` actions |
| `GET /admin/users`, `POST /admin/users` | Admin account list/search/forms and explicit management actions |
| `GET /organizer/events`, `POST /organizer/events` | Organizer-owned list/details/forms and draft save/delete actions |
| `GET /events`, `GET /events/detail?id=...` | Public eligible-event list/details |
| `GET /organizer/events/inventory?eventId=...` | Owned inventory, pricing and publication readiness |
| `POST /organizer/events/inventory` | Explicit `generate`, `price` or confirmed `publish` actions |
| `GET /events/seats?eventId=...` | Read-only eligible public seat layout, prices and effective availability |
| `GET /customer/events/seats?eventId=...` | Protected Customer seat selection with `page` and `size` |
| `POST /customer/bookings/hold` | Atomic current-event seat hold; repeated `eventSeatId` fields and paging in the body |
| `GET /customer/bookings/checkout?bookingId=...` | Read-only summary belonging to the current Customer |
| `POST /customer/bookings/expire` | Owner-only normalization/release of an already expired PENDING booking |
| `GET /customer/bookings` | Owner-only history; `status`, `page` and `size` filters |
| `GET /customer/bookings/detail?bookingId=...` | Owned snapshots, current state and paged attempts; `paymentPage` and `paymentSize` |
| `POST /customer/bookings/pay` | Explicit simulation outcome with an original form key and body-only booking identity |
| `GET /customer/bookings/cancel?bookingId=...` | Read-only whole-booking cancellation confirmation |
| `POST /customer/bookings/cancel` | Explicitly confirmed cancellation and any full simulated refund |

Guests requesting protected pages redirect to sign-in. A signed-in user requesting another role's page receives 403. The application rereads the current role and active status from the database rather than trusting a cached role. Disabled/deleted identities are invalidated. Unknown accounts, wrong passwords, and inactive accounts receive the same credential error. Database failures produce a generic 503 for database-dependent actions.

Forms submit session-bound `_csrf` values in the request body, including registration, login, and logout. Keep cookies enabled. Missing, invalid, cross-session, or stale tokens return 403; reopen the form after expiry. Authentication changes the session ID and CSRF token. Successful actions use HTTP 303 redirects to fixed application destinations; credentials and tokens are never added to redirect URLs.

## Role statistics

Open **Your account** after signing in. Summary cards cover all time and use the current server-authenticated account; query parameters cannot select another Customer, Organizer or role.

- Customers see Total, Pending, Confirmed, Expired and Cancelled bookings, with links to the corresponding history filters. Pending means an unexpired PENDING hold; Expired includes stored EXPIRED and PENDING whose deadline has been reached. Confirmed bookings remain confirmed after the original deadline. The four status counts sum to Total bookings.
- Organizers see all owned events, upcoming published events, tickets sold and simulated gross/refunded/net payments. Upcoming published is a state/time count, not a guarantee of purchase eligibility. Historical sales remain included after event start or related-record deactivation.
- Administrators see all accounts, events and bookings, confirmed tickets and the same financial totals across the system. Account counts include inactive accounts and the current Admin.

Tickets sold counts seats in currently CONFIRMED bookings, including free tickets. Pending holds and cancelled/refunded tickets are excluded. Gross payments sums every payment with a recorded success, including amounts now REFUNDED; Refunded payments sums current REFUNDED rows. Net revenue is Gross payments minus Refunded payments. Failed attempts, replay and repeated cancellation add no money. Separate equal-value payments each count, independently of seat quantity and failed-attempt count. All monetary values are exact two-decimal VND amounts from simulations.

Each summary revalidates the current active role and reads through one bounded SERIALIZABLE transaction with one captured database UTC time. It does not modify bookings, normalize expiry or take the management write lock. A genuinely empty authorized scope shows zeros; database/read failures return a controlled error page. Read locks can briefly delay concurrent financial changes; this is not a high-throughput performance guarantee.

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

AVAILABLE is available; HELD is held only while its expiry is strictly after captured database UTC time; equality or an expired hold is effectively available. SOLD or inactive/mismatched physical seats are unavailable. The legend and text states supplement color. Public reads never rewrite stored states, expose customer/current-booking identity or create reservations. Customers can choose **Select seats** to enter the protected reservation flow; guests receive a sign-in link. Other roles keep read-only event browsing.

## Customer seat reservations

Sign in as a CUSTOMER, browse an upcoming published event, open its seat availability page and choose **Select seats**. Select 1-50 available inventory seats from the current page. Changing page or page size clears that page's selection; there is no basket spanning pages. Selection alone and GET/HEAD requests never allocate seats.

The selected count and provisional VND total update with optional local JavaScript using exact integer cents. With JavaScript disabled, checkboxes and form submission still work; checkout shows the server-calculated total. The server ignores submitted price, total, customer, code, state and deadline fields. It uses stored prices, permits free seats and rejects an aggregate total exceeding `9999999999999999.99` without rounding or partial allocation.

Choose **Hold selected seats for five minutes**. One transaction revalidates the current active CUSTOMER, eligible event/organizer/category/venue and physical inventory, creates a PENDING booking, claims every selected seat, writes immutable item price/label snapshots and stores the exact total. A booking is created only if every requested seat is available or has an expired hold. Missing, foreign, inactive, sold or actively held seats reject the entire selection. Duplicate/malformed selections return 400; unavailable seats and conflicting requests return 409. Valid paging and visible selections are retained on recoverable selection errors so the user can correct and resubmit.

SQL Server supplies one UTC creation value at millisecond precision; every seat receives the booking's exact deadline, five minutes after that creation time. Lock waits consume that fixed lifetime. Eligibility and expiry are checked again after waiting, before completion. Refreshing checkout, signing in again, session expiry or resubmitting cannot extend the deadline. Repeating an actively held selection returns a conflict without another booking or a renewed allocation. Reserve competing seats only after reviewing current availability.

Success redirects to the owned checkout URL. It shows the booking code, escaped event title and seat snapshots, quantity, unit prices, total, original deadline and effective state. **A PENDING booking is a temporary reservation, not a confirmed ticket.** Remaining seconds are a server-time snapshot at page load, not a continuously running or authoritative browser timer. Refresh to obtain a new remaining-time check without renewal. Use **My bookings** to return to your reservations.

A PENDING booking is effectively EXPIRED when its deadline is equal to or earlier than database UTC time. Checkout GET does not write cleanup. Other customers can reuse expired inventory immediately, without a scheduler or opening the old checkout. The optional **Release this expired hold** POST records EXPIRED and releases only expired HELD rows still owned by that booking. It cannot cancel an unexpired reservation, release sold inventory or free seats reassigned to a newer booking. Repeated expiration is harmless, and booking/item history is retained. Another Customer's guessed booking ID returns 404 without disclosing its details; ADMIN and ORGANIZER cannot enter Customer routes.

## Simulated payment and booking management

For a live eligible checkout, choose **Simulate successful payment** or **Simulate failed payment**. These controls never transfer money or collect bank/card credentials. The server uses the stored exact VND total, including zero for free bookings. Success records one SUCCEEDED attempt, confirms the entire booking and changes every currently owned held seat to SOLD atomically. Failure records FAILED and retains the pending booking, held seats and original deadline. Expired holds, started/ineligible events and inconsistent totals/allocation reject new attempts without partial writes.

Each rendered new attempt carries a strict lowercase UUID key. Resubmitting that form or choosing **Replay recorded attempt** returns its recorded outcome without creating another payment or changing inventory. Reusing a key with a different outcome conflicts. Use **Open a new simulation attempt** after a failure for a deliberate retry with a new key. Keys are scoped to an owned booking and never authorize access. Replay remains available after expiry, event start, later success or cancellation/refund; the page distinguishes the original outcome from the current payment and booking states. A refunded success still counts as the booking's single lifetime success.

**My bookings** lists only the current Customer's bookings, newest first, with stable ID ordering for equal creation times. Filters accept PENDING, CONFIRMED, EXPIRED and CANCELLED, using effective expiry without changing stored rows. History and payment pages default to 10 records, support sizes 1-50 and pages 1-1,000,000. Details retain access after event start or sale-reference deactivation and show snapshots, original/confirmation/cancellation times and paged attempts. List/detail/retry navigation retains validated paging/filter context.

Choose **Cancel whole booking**, review the confirmation page, check the explicit confirmation and submit. Cancellation is allowed strictly before event start according to SQL Server time. Live PENDING bookings release all held seats without a refund, retaining any failed attempts. CONFIRMED bookings receive a full simulated refund of the stored total and release all sold seats, even after the original hold deadline. The successful attempt becomes REFUNDED in place, retaining its key, fingerprint, amount and success time; booking confirmation time and item history remain. Partial cancellation/refund is unavailable. Repeated cancellation returns the current result without another refund/release, including after event start or another Customer rebooks the seats. Expired PENDING bookings use the expiration action instead.

Payment and cancellation require the current active CUSTOMER owner, valid session CSRF and query-free POST bodies. Missing/duplicate identities, keys, outcomes or confirmation fields are rejected. Client-supplied amounts, owners, financial states and timestamps cannot change the server-derived action. Successful actions redirect with HTTP 303 to fixed owned details; no payment keys, CSRF tokens or amounts appear in redirect URLs. GET/HEAD and refreshing confirmation/history do not perform financial or allocation changes.

## External configuration

Database connections use **SQL Server Authentication only**. SQL Server must already accept SQL-authenticated connections, and an enabled SQL login must have access to the selected database through a mapped database user and the permissions required below. These credentials are separate from the seeded application accounts. Arrange server authentication/login provisioning separately; the application does not change server authentication mode, create logins or grant privileges.

### Explicit properties file

The tracked [config/database.properties.example](config/database.properties.example) is a safe template. Copy it to `config/database.properties` only if that file is missing, then fill the SQL credentials locally. The actual file is individually ignored by Git and excluded from the WAR; the config directory and safe example remain trackable. Restrict access to the actual file to the account running Tomcat. Do not commit, print or pass its credentials as command arguments.

Supported keys are case sensitive:

| Property | Requirement / default |
| --- | --- |
| `db.host` | Required SQL Server hostname; example `localhost` |
| `db.port` | `1433`; decimal integer from 1 to 65535 |
| `db.name` | Required database name; example `event_booking_p1_test` |
| `db.user` | Required SQL login |
| `db.password` | Required nonempty decoded password; never trimmed |
| `db.encrypt` | `true` |
| `db.trustServerCertificate` | `false` |

The file is UTF-8 Java `.properties` syntax; an optional UTF-8 BOM is accepted. Write `\\` for a literal backslash and escape each leading password space as `\ `. Escapes such as `\t`, `\n` and `\uXXXX` are decoded before validation. Trailing and decoded password whitespace is preserved. Quotes are literal value characters, not grouping syntax. Unknown/misspelled or duplicate decoded keys are rejected. Boolean values accept only `true` or `false`, ignoring case; blank values, extra decoded spaces and aliases such as `yes` are invalid.

Select the complete file with this option on the **actual Tomcat JVM**:

```text
-Deventbooking.db.config=D:/GitHub/event-ticket-booking-system/config/database.properties
```

Replace the absolute path with the location of your clone. For a path containing spaces, quote the value:

```text
-Deventbooking.db.config="D:/My Project/config/database.properties"
```

When the selector is present, only that file supplies database settings. There is no environment merge or fallback. A blank, relative, missing, unreadable, non-regular or invalid file fails application initialization with safe diagnostics; it does not silently become NOT_CONFIGURED. The application does not search the working directory, discover the project root, or load this file from the classpath.

Use a SQL Server certificate trusted by the JVM and matching the hostname. For a self-signed certificate in an explicitly permitted local development setup, `db.trustServerCertificate=true` keeps encryption while bypassing certificate validation. Keep the secure default `false` for normal deployment.

### Environment alternative

When `eventbooking.db.config` is absent, the application uses `EVENT_DB_*` variables from the server process. Neither `.env` nor `database.properties` is automatically loaded. The [.env.example](.env.example) file is reference only.

| Environment variable | Requirement / default |
| --- | --- |
| `EVENT_DB_HOST` | Required host |
| `EVENT_DB_PORT` | `1433`, range 1-65535 |
| `EVENT_DB_NAME` | Required database name |
| `EVENT_DB_USER` | Required SQL login |
| `EVENT_DB_PASSWORD` | Required password; never trimmed |
| `EVENT_DB_AUTH` | Optional compatibility key; only `sql-password` is supported |
| `EVENT_DB_ENCRYPT` | `true` |
| `EVENT_DB_TRUST_SERVER_CERTIFICATE` | `false`; explicit local self-signed option only |

Unsupported `EVENT_DB_*` names and authentication modes, including `windows-integrated`, are rejected. No Windows authentication DLL or `java.library.path` setup is required.

For environment mode, enter an existing SQL login through an interactive prompt in the shell that will start Tomcat:

```powershell
$databaseCredential = Get-Credential -Message 'Enter the existing SQL database login'
$env:EVENT_DB_HOST = 'localhost'
$env:EVENT_DB_PORT = '1433'
$env:EVENT_DB_NAME = 'event_booking_p1_test'
$env:EVENT_DB_AUTH = 'sql-password'
$env:EVENT_DB_USER = $databaseCredential.UserName
$env:EVENT_DB_PASSWORD = $databaseCredential.GetNetworkCredential().Password
$env:EVENT_DB_ENCRYPT = 'true'
$env:EVENT_DB_TRUST_SERVER_CERTIFICATE = 'false'
```

Without a file selector or any `EVENT_DB_*` variables, the public foundation remains usable with NOT_CONFIGURED; database-dependent operations fail generically. Partial/invalid configuration fails initialization. A valid selected source with a failed bounded connection probe reports UNAVAILABLE. Logs report source type FILE, ENVIRONMENT or NONE and safe status/diagnostics, without configuration values or sensitive exception chains.

Session configuration is independent:

| Variable | Default / requirement |
| --- | --- |
| `EVENT_SESSION_TIMEOUT_SECONDS` | `1200`; integer 1-86400 |
| `EVENT_SESSION_COOKIE_SECURE` | `false` for local HTTP; enable for HTTPS deployment |

`DataSourceFactory` uses typed driver setters, explicit SqlPassword authentication, setUser/setPassword and bounded timeouts. It is a connection factory, not a pool. Connections/statements/results are acquired per use case and closed; no request connection is shared. The normal application SQL user needs SELECT and relevant INSERT/UPDATE/DELETE permissions plus permission to request the database application lock. Integration tests need additional fixture and isolated-schema permissions.

## Build and deployment

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-17'
$env:Path = "$env:JAVA_HOME/bin;$env:Path"
mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
jar tf target/event-booking.war
```

The WAR contains protected JSP views, local assets, JSTL, JDBC and bcrypt runtime libraries. It excludes test code/libraries, credentials, local properties/environment files, native DLLs and SQL scripts. Compilation uses release 17 and UTF-8.

### Standalone Tomcat

For a ZIP installation, supply the selected path as a JVM option in the process starting Tomcat:

```powershell
$env:CATALINA_HOME = 'C:/tools/apache-tomcat-10.1.44'
$env:CATALINA_BASE = $env:CATALINA_HOME
$env:JAVA_OPTS = ($env:JAVA_OPTS + ' "-Deventbooking.db.config=D:/GitHub/event-ticket-booking-system/config/database.properties"').Trim()
Copy-Item -LiteralPath target/event-booking.war -Destination (Join-Path $env:CATALINA_BASE 'webapps/event-booking.war')
& "$env:CATALINA_HOME/bin/catalina.bat" run
```

Use the quoted whole JVM argument shown above when the path contains spaces. For environment mode or NOT_CONFIGURED, omit that JVM option. A service uses its own account, environment and JVM options; variables set in a separate shell do not configure an already-running service.

### IntelliJ Tomcat VM options

Open **Run > Edit Configurations**, select the **Tomcat Server > Local** configuration actually used to launch the application, and add the selector to **Server > VM options**. Select JDK 17 for the server. Configure the WAR/exploded artifact and application context on the Deployment tab. This option belongs to the server process; changing the IDE's own JVM options does not configure Tomcat. See the [JetBrains Tomcat configuration reference](https://www.jetbrains.com/help/idea/run-debug-configuration-tomcat-server.html).

### NetBeans Tomcat JVM options

In **Services > Servers**, open **Properties** for the Tomcat instance used by the project. On **Platform**, choose JDK 17 and add the selector to **VM Options**. In project properties, confirm the intended Tomcat server and context path. NetBeans' own `netbeans.conf` JVM options are separate from the server. The [Apache NetBeans Tomcat customizer](https://github.com/apache/netbeans/blob/master/enterprise/tomcat5/src/org/netbeans/modules/tomcat5/customizer/Bundle.properties) defines the Platform/VM Options fields and applies server changes on its next start.

Database settings are loaded once during application initialization. Changing the JVM selector requires restarting the actual Tomcat process. After changing file contents, reload/redeploy the application or restart Tomcat to create new settings/DataSource instances. Rebuild and deploy the current WAR after code changes. Restarting only the browser or IDE does not replace an already-running server's configuration.

Open `http://localhost:8080/event-booking/health` for the default deployment, using the actual connector port/context when an IDE configures another URL. HTTP 200 contains a cached startup database status: NOT_CONFIGURED, AVAILABLE or UNAVAILABLE. AVAILABLE proves only the startup SELECT 1, not schema readiness or continuing health. Check the source-type/startup lines in that server's logs without printing credentials. JSPs under WEB-INF remain protected; application links honor the deployed context. Tomcat derives automatic context names from the WAR filename; see [Tomcat context naming](https://tomcat.apache.org/tomcat-10.1-doc/config/context.html#Naming).

### Isolated Tomcat smoke verification

`scripts/Test-Tomcat.ps1` uses readable Tomcat 10.1.44 binaries, JDK 17 and an already-built WAR. It creates an isolated base under target, chooses a loopback port, and stops only its own disposable JVM. It never restarts an installed/shared Tomcat.

- `-TomcatHome`: required Tomcat binary directory.
- `-JavaHome`: JDK 17 path; defaults to JAVA_HOME.
- `-DatabaseConfigFile`: optional existing absolute file path, forwarded to both the actual Tomcat JVM and Maven's HTTP test JVM.
- `-ExpectedDatabaseStatus`: NOT_CONFIGURED (default), AVAILABLE or UNAVAILABLE.
- `-ContextName`: default event-booking; 1-50 letters/digits/underscores/hyphens, starting with a letter/digit; ROOT is unsupported.
- `-AuthenticationTests`: runs all six authentication, management/catalog, inventory, reservation, payment/history and cancellation/refund HTTP scenarios. AVAILABLE needs a confirmed dedicated database and fixture permissions.

Windows PowerShell can block .ps1 execution before the smoke test starts. Inspect policy with the same executable:

```powershell
powershell -NoProfile -Command "Get-ExecutionPolicy -List"
```

For a trusted permitted local test, the examples use process-only ExecutionPolicy Bypass. It does not persistently change policy; organizational Group Policy restrictions still apply. Do not change machine-wide policy or bypass organizational restrictions. Script-policy errors occur before testing and are separate from application/database/deployment failures. See [Windows PowerShell execution policies](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1).

Without either database source, clear application DB variables in the launching shell and ensure no selector is supplied by global JVM options:

```powershell
Get-ChildItem Env:EVENT_DB_* | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:/tools/apache-tomcat-10.1.44' -JavaHome 'C:/Program Files/Java/jdk-17' -ExpectedDatabaseStatus NOT_CONFIGURED -AuthenticationTests
```

With a filled local file and an existing SQL login authorized for the designated test database:

```powershell
$env:EVENT_TEST_DB_CONFIRM = 'event_booking_p1_test'
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:/tools/apache-tomcat-10.1.44' -JavaHome 'C:/Program Files/Java/jdk-17' -DatabaseConfigFile 'D:/GitHub/event-ticket-booking-system/config/database.properties' -ExpectedDatabaseStatus AVAILABLE -ContextName campus-auth -AuthenticationTests
```

For environment mode, omit DatabaseConfigFile and set the complete SQL-authentication environment above. For UNAVAILABLE checks, select a separate local test file using an unreachable loopback endpoint; retain the usable project configuration. Omit AuthenticationTests for basic smoke only.

The script verifies the actual Tomcat configuration-source log and the HTTP fork's selected source, health status, UTF-8/JSTL/EL, assets, protected JSP/unknown-route 404 and root POST 405. Auth tests use a short timeout only in that disposable HTTP server. Reports/logs remain under target; HTTP markup checks do not establish browser visual acceptance.

## Tests

`mvn -B clean verify` runs database-independent Java 17 unit tests and builds the WAR. Focused tests cover file selection/precedence, UTF-8 and properties decoding, safe failures, centralized SQL settings and dedicated-database guards alongside existing security/business-unit checks.

Use a dedicated SQL Server 2022 database already initialized with schema/seed and an existing SQL login. In file mode:

```powershell
$env:EVENT_TEST_DB_CONFIRM = 'event_booking_p1_test'
mvn -B clean verify -Psqlserver-it '-Deventbooking.db.config=D:/GitHub/event-ticket-booking-system/config/database.properties'
```

PowerShell quotes the entire Maven property argument when the path contains spaces:

```powershell
mvn -B clean verify -Psqlserver-it '-Deventbooking.db.config=D:/My Project/config/database.properties'
```

The activated Maven profile explicitly forwards the selector to Surefire and Failsafe JVMs. The application and integration tests use the same loader and source-selection rules. EVENT_TEST_DB_CONFIRM must exactly match the resolved database name; file mode does not require a duplicate EVENT_DB_NAME. System databases are rejected and SQL Server major version 16 is checked before fixtures. In environment mode, omit the JVM selector and supply the complete SQL Authentication variables; keep the same explicit test confirmation.

Missing credentials/connectivity fail the SQL profile rather than silently skipping. Do not reset/reseed populated data to obtain a pass. Rerun-safety checks compare all nine current table counts/content after each refused script, including existing financial history. Some foundation tests require the seeded reference/accounts and future sample events. Schema fixtures roll back; committed management/booking fixtures are cleaned by exact ownership. Identity counters may advance. The last-admin race creates and drops only its uniquely owned isolated schema; that test login needs CREATE SCHEMA/TABLE and cleanup permissions, while normal application operation does not.

Schema checks cover JDBC/Unicode, trusted constraints, both venue foreign keys, booking/item/current-owner event matching, inventory shapes, historical seat references, payment key scope, success uniqueness after refund, normalized email, amount/time checks, deletion protection, and script rerun refusal. Registration checks cover persistence, compatible stored hashes, normalization, duplicate mapping, and one winner from competing inserts. Schema fixtures do not implement booking/payment application workflows. Management SQL checks additionally cover duplicate category/seat/email values, actor and reference guards, last-admin concurrency with two independent connections, role/history restrictions, draft ownership/status predicates, UTC persistence, inactive references, rollback on errors, public visibility, literal/combined filtering and stable pagination.

Inventory SQL checks additionally cover additive/repeated generation, exact/free prices, mixed-ID batch atomicity, publication coverage/history guards, independent event prices, real connection races with pricing/generation/reference edits/publication, delayed publication across its start boundary, deterministic hold-expiry equality, public visibility, full-count paging and non-mutating availability reads.

Reservation SQL checks cover exact booking/items/deadlines, ownership, aggregate overflow, whole-selection rollback and injected item-write failure. Synchronized independent connections exercise single-seat, overlapping/disjoint and repeat requests, plus the production guarded allocation SQL without the shared management application lock. Other checks force waits across event-start/hold-deadline boundaries, reject lock-timeout operations, reuse expired inventory while the old booking is locked, and prove repeated old expiration preserves a newer owner's hold and both item histories. All writes belong to exact fixture events; seeded and existing business data are preserved.

Payment/history/cancellation SQL checks cover exact success/failure/free amounts, immutable snapshots, replay across state/time changes, scoped keys and one lifetime success, effective filtered history and bounded attempts. Distinct real SERIALIZABLE connections exercise the production transaction work with and without shared application-lock coordination. Boundary and injected-failure checks prove complete rollback after financial inserts/refunds and partial seat updates. Payment/cancel, repeated cancel, expiry/takeover and rebooking races assert consistent financial/booking/inventory state and preservation of newer owners.

The script's `-AuthenticationTests` switch runs seven JUnit HTTP scenarios with independent cookie sessions. Authentication checks registration, validation/escaping, three seeded role logins, all nine role-route pairs, negative/stale CSRF, session rotation/replay/logout, inactive/changed accounts, expiry and logout from authenticated POST error pages. Management/catalog checks real rendered forms, reference/account/event CRUD, role/ownership and credential-field tampering, CSRF rejection, public visibility, encoded paging and invalid inputs. Inventory checks rendered generation/pricing/publication forms, immutable setup, ownership/CSRF/tampering, effective public availability and validation/conflict logout. Reservations exercise actual non-first-page forms, corrected resubmission, server-derived fields, owned checkout, negative CSRF, mixed-seat atomicity, real session expiry without renewal, expired takeover and safe normalization/logout. Payment and cancellation use actual rendered forms to verify outcomes, retries/replays, history, full refunds, rebooking, critical duplicate-field rejection and error-page logout. Statistics checks rendered role summaries, scoped zeros, financial reconciliation, query-scope tampering, read-only behavior, safe failures, links and logout. Without an available database they check guest boundaries, foundation availability and generic auth/catalog/inventory 503 responses. Seeded accounts are used for login only. Fixture users have `auth-<UUID>-...@example.test` emails; reference data uses a `Fixture <UUID>` label. An abruptly terminated test may leave owned fixtures or an isolated `p3_admin_<UUID>` schema; inspect exact ownership before manual cleanup.

Surefire reports are in `target/surefire-reports`; Failsafe reports are in `target/failsafe-reports`. Each HTTP invocation replaces its scenario report. Tomcat smoke logs and the captured anonymous response are under `target/tomcat-smoke-*/logs`; auth form bodies, passwords, cookies, and tokens are not written as evidence. `mvn clean` removes `target`, including those artifacts. Stop any manually launched JVM using that directory before cleaning on Windows. A successful build alone does not establish deployment or database correctness.

## Technical limitations

Payments/refunds are simulations only. Real payment integration, QR generation/check-in, partial cancellation/refund, Admin/Organizer booking overrides are unavailable. Authentication has no email verification, password recovery/change, MFA or rate limiting. Bootstrap CSS is local; core forms and checkout require no JavaScript. Reference suggestions and inventory pages are capped at 50; explicit reference IDs and paging support larger data sets. Reservation, payment and management writes share deliberately conservative database serialization, so high-throughput booking performance is not established. Availability/history use captured database time and matching predicates across separate read statements, rather than promising a snapshot-isolated view during external writes. Expiry has no background worker; effective expiration/reuse and owner-triggered normalization work independently of one. HTTPS/proxy setup, production permission provisioning, pooling, load testing and a broader browser/accessibility matrix require deployment-specific work.

## Troubleshooting

| Symptom | Meaning and next step |
| --- | --- |
| PowerShell reports scripts are disabled or the script is not signed | Script loading was blocked before the smoke test ran. Inspect execution policy using the command above; this is not an application, database, or Tomcat test result. |
| The script reports a missing WAR or wrong Tomcat/Java version | The script started but its prerequisites failed. Build the WAR and correct `-TomcatHome`/`-JavaHome`. |
| `NOT_CONFIGURED` when a database was expected | The actual server JVM has no file selector or database environment. Add the VM option to the server configuration and restart/redeploy, or set environment values before starting it. Merely creating database.properties or .env does not select them. |
| `UNAVAILABLE` | Configuration loaded, but the startup SQL connection probe failed. Check TCP endpoint, existing SQL login/password and database mapping, permissions, encryption and certificate trust. A local self-signed certificate needs the explicit development trust option. The status omits sensitive driver details. |
| Sign-in/registration returns 503 although `/health` says `AVAILABLE` | The snapshot only checked connectivity at startup. Check current database access, the initialized schema, and permissions; retry after the service is restored. |
| A form returns 403 | Reopen it to obtain a token for the current session. Keep cookies enabled; do not reuse a pre-login token or put form values in a query string. |
| Sign-in repeatedly returns to the form on local HTTP | Check whether Secure cookies are enabled. Use `EVENT_SESSION_COOKIE_SECURE=false` for the isolated HTTP test and `true` with HTTPS deployment. |
| A protected page returns 403 or redirects to sign-in | Check the account's current role/active status and session expiry. Navigation links do not grant access. |
| Application initialization fails after selecting settings | Check that the file selector is absolute/readable and that required fields, exact key names, port, booleans and UTF-8/properties syntax are valid. File errors never fall back to environment settings. Only SQL Server Authentication is supported. |
| HTTP deployment check fails after the script starts | Inspect `target/tomcat-smoke-*/logs` for deployment/JSP/startup errors and compare the expected database status. Changing execution policy does not fix these failures. For manual deployment, inspect `CATALINA_BASE/logs` and confirm the context path. |
| Schema or seed rerun is refused | This is the scripts' data-preservation guard. Use an explicitly selected empty database for initial setup; rerunning is not a migration or reset operation. |
| Integration tests fail with modified or old demo data | The suite expects the original seed contents and future sample events. Initialize a separate empty test database rather than refreshing existing data automatically. |
| `mvn clean` cannot delete a Tomcat log under `target` | Stop the manually launched test JVM holding the file, then rerun clean. The smoke script normally stops its own process in `finally`. |

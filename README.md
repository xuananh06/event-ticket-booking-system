# Event Ticket Booking System

A university Java web application. P1 provides the application foundation, SQL Server schema, demo data, and verification tools. Registration, login, CRUD, booking, payments, and dashboards are reserved for later milestones.

## Requirements

- JDK 17 and Maven 3.6.3 or later. Confirm `java -version` and `mvn -version` use Java 17.
- Apache Tomcat **10.1.44**.
- Microsoft SQL Server **2022** with an accessible TCP endpoint. SSMS and `sqlcmd` are optional administration clients.
- A dedicated empty database and permission to create application tables and indexes. Use a separately provisioned, least-privilege database identity for normal application operation.

The application uses Jakarta Servlet, JSP/JSTL/EL, and JDBC with manual constructor injection. It does not use Spring or an ORM.

| Dependency | Pinned version | Scope |
| --- | --- | --- |
| Jakarta Servlet API | 6.0.0 | Provided by Tomcat |
| Jakarta EL API | 5.0.0 | Provided by Tomcat |
| Jakarta Tags API / implementation | 3.0.2 / 3.0.1 | Packaged in WAR |
| Microsoft JDBC Driver | 12.10.2.jre11 | Packaged in WAR |
| JUnit Jupiter | 5.12.2 | Tests |
| Favre bcrypt | 0.10.2 | Offline seed tooling and tests |

Build plugin versions are pinned in `pom.xml`. Tomcat 10.1 implements Servlet 6.0, JSP 3.1, and EL 5.0; it does not supply JSTL. The JDBC `jre11` artifact supports Java 17. References: [Tomcat compatibility](https://tomcat.apache.org/migration-10.1.html), [Jakarta Tags](https://jakarta.ee/specifications/tags/3.0/), [Microsoft JDBC requirements](https://learn.microsoft.com/en-us/sql/connect/jdbc/system-requirements-for-the-jdbc-driver), [Microsoft JDBC release](https://github.com/microsoft/mssql-jdbc/releases/tag/v12.10.2), [bcrypt library](https://github.com/patrickfav/bcrypt), [JUnit](https://docs.junit.org/5.12.2/user-guide/).

## Database setup

Select a dedicated empty user database. For a local Windows-authenticated test installation, create a new database explicitly:

```powershell
sqlcmd -S localhost -E -b -Q "IF DB_ID(N'event_booking_p1_test') IS NOT NULL THROW 51000, 'Database already exists; creation refused.', 1; CREATE DATABASE [event_booking_p1_test];"
sqlcmd -S localhost -E -d event_booking_p1_test -b -f 65001 -i database/schema.sql
# Run only if schema.sql succeeded.
sqlcmd -S localhost -E -d event_booking_p1_test -b -f 65001 -i database/seed.sql
```

Check each command's exit code before continuing. In SSMS, select the intended database in the database selector, open each UTF-8 script, and execute schema before seed. For SQL authentication, use `sqlcmd -U your_login` with an interactive password prompt rather than putting a password in command arguments. Database creation requires separate permissions; the application never creates databases, users, or logins.

Both scripts refuse system databases. Schema creation is transactional and refuses to run when any of the nine application tables already exists. Seed is transactional and refuses to run if any application table contains rows. Reruns preserve existing rows and return errors; they are not migrations or data refresh commands. Execute setup without concurrent application traffic. Neither script drops, truncates, or deletes existing data.

Scripts are each a single T-SQL batch without `GO`, so JDBC can execute each complete script. `GO` is an SSMS/sqlcmd batch separator, not a T-SQL statement; do not add it to a script passed directly to JDBC.

The schema has nine tables: `users`, `categories`, `venues`, `seats`, `events`, `bookings`, `event_seats`, `booking_items`, and `payments`. It includes:

- `BIGINT IDENTITY` keys, Vietnamese `NVARCHAR`, `DECIMAL(18,2)` VND amounts, and UTC `DATETIME2(3)` fields.
- Composite foreign keys for event/seat venue consistency, booking/item event consistency, and current inventory ownership.
- Explicit inventory owner/expiry shapes, booking/payment timestamp shapes, nonnegative amounts, and a maximum five-minute booking deadline.
- Unique email, physical layouts, per-event seats, booking codes, per-booking seat items, and per-booking payment keys.
- A filtered unique index on successful payment history, retained after refund, and indexes for event searches, booking history, and inventory availability.
- No cascading deletion of transaction history. Historical items may reference the same seat in different bookings.

Email must equal SQL Server `LOWER(LTRIM(RTRIM(email)))`, without leading/trailing ASCII spaces. Its explicit collation is `Latin1_General_100_CI_AS_SC`: uniqueness is case insensitive and accent sensitive. Statuses use binary collation and reject trailing spaces. Full input validation, role/ownership rules, item totals, matching hold deadlines, minimum item counts, state transitions, and concurrency enforcement belong to later service transactions; constraints alone do not implement those workflows.

### Demo data and password format

Seed creates three accounts, two categories, one venue, eight physical seats, two events sharing the venue, and sixteen independent event-seat rows. Events start relative to `SYSUTCDATETIME()`. One is PUBLISHED and one DRAFT. Booking/item/payment tables remain empty.

| Account | Role | Public demo password |
| --- | --- | --- |
| `admin@example.test` | ADMIN | `DemoOnly!ChangeMe` |
| `organizer@example.test` | ORGANIZER | `DemoOnly!ChangeMe` |
| `customer@example.test` | CUSTOMER | `DemoOnly!ChangeMe` |

These public accounts are for a local demonstration. Database account credentials are configured separately. P1 does not expose a login page.

Password contract for P2: Favre bcrypt 0.10.2, `$2a$` format, cost **12**, independent cryptographic random salts, 60-character encoded hashes. The schema enforces this format; changing algorithm or cost requires a schema migration. Validate passwords as 8-72 UTF-8 bytes before hashing and never silently truncate. Unit tests verify all actual seed hashes with the correct and incorrect password.

Generate replacement hashes offline, from the repository root:

```powershell
mvn -B test-compile dependency:build-classpath '-Dmdep.includeScope=test' '-Dmdep.outputFile=target/test-classpath.txt'
$testClasspath = Get-Content -Raw target/test-classpath.txt
java -cp "target/test-classes;target/classes;$testClasspath" com.eventbooking.tools.DemoPasswordHashes
# Or generate three independently salted hashes of the public demo password:
java -cp "target/test-classes;target/classes;$testClasspath" com.eventbooking.tools.DemoPasswordHashes --demo
```

The interactive command reads without echo in a terminal. It does not accept passwords in command arguments. The helper and bcrypt are excluded from the WAR.

## External configuration

`.env.example` is a reference template. **The application and Tomcat do not automatically load `.env` files.** Set process environment variables before starting Tomcat, or use the deployment environment's secret configuration. Never commit real credentials.

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

Boolean settings accept only `true` or `false`, ignoring case. No `EVENT_DB_*` variables means the smoke page can run without a database and shows `NOT_CONFIGURED`. Partial or malformed configuration fails application initialization with setting names only. A configured but unreachable database yields `UNAVAILABLE`; the foundation page remains accessible. `AVAILABLE` proves only the bounded startup `SELECT 1`, not schema readiness or continuing database health. Requests do not repeat the probe. Restart the application after changing configuration.

Example for SQL authentication in PowerShell:

```powershell
$env:EVENT_DB_HOST = 'localhost'
$env:EVENT_DB_PORT = '1433'
$env:EVENT_DB_NAME = 'event_booking_p1_test'
$env:EVENT_DB_AUTH = 'sql-password'
$databaseCredential = Get-Credential -Message 'SQL Server application login'
$env:EVENT_DB_USER = $databaseCredential.UserName
$env:EVENT_DB_PASSWORD = $databaseCredential.GetNetworkCredential().Password
```

Windows authentication uses the identity running the JVM and requires the matching Microsoft native library for its architecture:

```powershell
$env:EVENT_DB_HOST = 'localhost'
$env:EVENT_DB_PORT = '1433'
$env:EVENT_DB_NAME = 'event_booking_p1_test'
$env:EVENT_DB_AUTH = 'windows-integrated'
Remove-Item Env:EVENT_DB_USER, Env:EVENT_DB_PASSWORD -ErrorAction SilentlyContinue
```

Download `mssql-jdbc_auth.zip` from the [official driver release](https://github.com/microsoft/mssql-jdbc/releases/tag/v12.10.2). Extract it outside `target` if using `mvn clean`; for an x64 JVM, supply the directory containing `mssql-jdbc_auth-12.10.2.x64.dll` as `-Djava.library.path=...`. The DLL is not packaged in the WAR. No SQL logins need to be created for Windows authentication.

Use a trusted SQL Server certificate matching the configured hostname. For an isolated local test with a self-signed certificate, explicitly setting `EVENT_DB_TRUST_SERVER_CERTIFICATE=true` keeps encryption but bypasses certificate validation; keep the default `false` for normal deployment. Do not change server-wide authentication, firewall, or TLS settings to run this application.

`DataSourceFactory` uses typed driver setters and bounded login/socket timeouts. `SQLServerDataSource` is a connection factory, not a connection pool. `DatabaseProbe` acquires a fresh connection and closes result sets, statements, and connections with try-with-resources. Future services must own one connection per use case, share it with participating DAOs, and control commit/rollback. Never store a request connection in a Servlet or application singleton.

## Build and deployment

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B clean verify
jar tf target/event-booking.war
```

The WAR contains the application, protected JSP, JSTL API/implementation, and JDBC driver. Servlet/EL APIs, test libraries, native DLLs, credentials, and database scripts are excluded. Maven uses `--release 17` and UTF-8 for compilation.

Configure `JAVA_HOME`, `CATALINA_HOME`, and an appropriate `CATALINA_BASE`, set the database environment, copy `target/event-booking.war` into that base's `webapps` directory, and run Tomcat 10.1.44. For a terminal deployment, start `CATALINA_HOME/bin/catalina.bat run` from the configured shell. Tomcat also reads `CATALINA_BASE/bin/setenv.bat`; keep any local secret configuration outside Git. For Windows integrated authentication, add the native library directory to the JVM's `java.library.path` through `JAVA_OPTS` in that file. A Windows service uses its own account and environment; it does not inherit variables set in a separate PowerShell session.

Open `http://localhost:8080/event-booking/` for the default connector/context. The root Servlet forwards to `WEB-INF/views/home.jsp`. It renders Vietnamese text, four JSTL list entries, EL values, and a generic startup connectivity status. Direct JSP access returns 404. Only the context root is mapped; other pages and workflows are absent. Requests and responses default to UTF-8 in `web.xml`; JSP output uses `c:out` escaping.

### Isolated Tomcat smoke verification

```powershell
# No database settings: expect NOT_CONFIGURED.
powershell -NoProfile -File scripts/Test-Tomcat.ps1 -TomcatHome D:\ApacheTomcat -JavaHome 'C:\Program Files\Java\jdk-17'
# With the configured local Windows-authenticated database:
powershell -NoProfile -File scripts/Test-Tomcat.ps1 -TomcatHome D:\ApacheTomcat -JavaHome 'C:\Program Files\Java\jdk-17' -JdbcNativeDirectory C:\local\jdbc-native\x64 -ExpectedDatabaseStatus AVAILABLE
```

The script uses the supplied Tomcat binaries, validates version and Java runtime, creates a separate base under `target`, listens on an ephemeral loopback port, deploys the built WAR, and verifies HTTP 200, UTF-8, JSTL/EL, expected status, protected JSP 404, unknown route 404, and POST 405. It stops only its own disposable JVM and retains response/log evidence. It does not start or alter an installed Tomcat service. Use `-ExpectedDatabaseStatus UNAVAILABLE` with deliberately unreachable local settings to verify the generic failure page. This is an HTTP deployment check, not a browser visual review.

## Tests

`mvn -B clean verify` runs database-independent unit tests, validates configuration and JDBC resource cleanup, verifies seed passwords, and builds the WAR. It does not connect to SQL Server or start Tomcat.

Run database integration tests explicitly against a dedicated SQL Server 2022 database already initialized with schema and seed:

```powershell
# Set EVENT_DB_* using one authentication mode above.
$env:EVENT_TEST_DB_CONFIRM = $env:EVENT_DB_NAME
mvn -B clean verify -Psqlserver-it
# Windows integrated authentication also needs this JVM option:
mvn -B clean verify -Psqlserver-it '-DargLine=-Djava.library.path=C:\local\jdbc-native\x64'
```

The profile fails when configuration, explicit database confirmation, connectivity, or the required SQL Server major version is missing. It does not silently skip unavailable integration tests. Do not run it against shared or production data. It expects the original seed contents and uses short fixture transactions with rollback. Rows are restored, while SQL Server identity counters may advance. It tests JDBC/Unicode, trusted constraints, both venue foreign keys, booking/item/current-owner event matching, invalid and valid inventory shapes, historical rebooking, payment key scope, success uniqueness after refund, normalized email, amount/time checks, deletion protection, and script rerun refusal.

Surefire reports are in `target/surefire-reports`; Failsafe reports are in `target/failsafe-reports`. Tomcat smoke evidence is under `target/tomcat-smoke-*`. `mvn clean` removes `target`, including those artifacts and any native library extracted there. A successful build alone does not establish deployment or database correctness. P1 does not test authentication, booking concurrency, payment workflows, or performance.

## Repository policy

The root `/docs/` directory is local, ignored documentation. Keep its implementation status and review notes local; do not stage, force-add, commit, or push that directory. README and other Markdown outside `docs` remain eligible for Git. Local documentation is not transferred by a clone. Source, tests, SQL, build files, and the safe environment template are reviewable Git deliverables. Stop at P1 before implementing another milestone.

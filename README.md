# Event Ticket Booking System

A university Java web project for event ticket booking and seat reservation. The current application provides a runnable web foundation and a SQL Server schema for the booking domain.

## Capabilities

Implemented:

- A Servlet forwarding to a protected JSP with Vietnamese UTF-8 text, JSTL iteration, and escaped EL output.
- External database configuration and a bounded, read-only connectivity check at application startup.
- A nine-table SQL Server schema with relational constraints, query indexes, demo accounts, events, and seat inventory.
- Unit tests, an optional SQL Server integration test profile, and an isolated Tomcat HTTP smoke-test script.

Planned capabilities, not yet available as application workflows:

- Registration, login, and role-based access for customers, organizers, and administrators.
- Event browsing, search, and administrative CRUD.
- Timed seat reservations, booking management, simulated payments, cancellations, and dashboards.

The database represents these domains, but the current web application exposes only the startup page.

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
| Favre bcrypt | 0.10.2 | Offline seed tooling and tests |

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

- `BIGINT IDENTITY` keys, Vietnamese `NVARCHAR`, `DECIMAL(18,2)` VND amounts, and UTC `DATETIME2(3)` fields.
- Composite foreign keys for event/seat venue consistency, booking/item event consistency, and current inventory ownership.
- Explicit inventory owner/expiry shapes, booking/payment timestamp shapes, nonnegative amounts, and a maximum five-minute booking deadline.
- Unique email, physical layouts, per-event seats, booking codes, per-booking seat items, and per-booking payment keys.
- A filtered unique index on successful payment history, retained after refund, and indexes for event searches, booking history, and inventory availability.
- No cascading deletion of transaction history. Historical items may reference the same seat in different bookings.

Email must equal SQL Server `LOWER(LTRIM(RTRIM(email)))`, without leading/trailing ASCII spaces. Its explicit collation is `Latin1_General_100_CI_AS_SC`: uniqueness is case insensitive and accent sensitive. Statuses use binary collation and reject trailing spaces. Constraints do not establish role/ownership rules, matching item totals and hold deadlines, minimum item counts, valid state transitions, or concurrency-safe allocation. The application workflows enforcing those rules are not implemented.

### Demo data and password format

Seed creates three accounts, two categories, one venue, eight physical seats, two events sharing the venue, and sixteen independent event-seat rows. Events start relative to `SYSUTCDATETIME()`. One is PUBLISHED and one DRAFT. Booking/item/payment tables remain empty.

| Account | Role | Public demo password |
| --- | --- | --- |
| `admin@example.test` | ADMIN | `DemoOnly!ChangeMe` |
| `organizer@example.test` | ORGANIZER | `DemoOnly!ChangeMe` |
| `customer@example.test` | CUSTOMER | `DemoOnly!ChangeMe` |

These public accounts are demo database records. Database connection credentials are configured separately, and the application does not yet expose a login page.

Seed password format: Favre bcrypt 0.10.2, `$2a$` format, cost **12**, independent cryptographic random salts, and 60-character encoded hashes. The schema enforces this format; changing algorithm or cost requires a schema migration. The offline helper accepts passwords of 8-72 UTF-8 bytes. Unit tests check the actual seed hashes with the correct and incorrect password.

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

Boolean settings accept only `true` or `false`, ignoring case. No `EVENT_DB_*` variables means the smoke page can run without a database and shows `NOT_CONFIGURED`. Partial or malformed configuration fails application initialization with setting names only. A configured but unreachable database yields `UNAVAILABLE`; the foundation page remains accessible. `AVAILABLE` proves only the bounded startup `SELECT 1`, not schema readiness or continuing database health. Requests do not repeat the probe. Restart the application after changing configuration.

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

`DataSourceFactory` uses typed driver setters and bounded login/socket timeouts. `SQLServerDataSource` is a connection factory, not a connection pool. The bootstrap injects dependencies through constructors. `DatabaseProbe` acquires a fresh connection and closes result sets, statements, and connections with try-with-resources; no JDBC connection is retained between requests.

## Build and deployment

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
$env:Path = "$env:JAVA_HOME\bin;$env:Path"
mvn -B clean verify
if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
jar tf target/event-booking.war
```

The WAR contains the application, protected JSP, JSTL API/implementation, and JDBC driver. Servlet/EL APIs, test libraries, native DLLs, credentials, and database scripts are excluded. Maven uses `--release 17` and UTF-8 for compilation.

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

Open `http://localhost:8080/event-booking/` for the default connector/context. The root Servlet forwards to `WEB-INF/views/home.jsp`. It renders Vietnamese text, four JSTL list entries, EL values, and a generic startup connectivity status. Direct JSP access returns 404. Only the context root is mapped; other pages and workflows are absent. Requests and responses default to UTF-8 in `web.xml`; JSP output uses `c:out` escaping.

For automatic deployment into `webapps`, Tomcat derives the context path from the WAR filename, so `event-booking.war` maps to `/event-booking`. The `path` attribute in the bundled `META-INF/context.xml` does not override that naming rule. An IDE or explicit server deployment configuration may use another context path. See [Tomcat context naming](https://tomcat.apache.org/tomcat-10.1-doc/config/context.html#Naming).

### Isolated Tomcat smoke verification

The Windows script requires readable Tomcat 10.1.44 binaries/configuration, JDK 17, and an already-built `target/event-booking.war`. It inherits database variables from the launching shell. Its parameters are `-TomcatHome` (required), `-JavaHome` (defaults to `JAVA_HOME`), `-JdbcNativeDirectory` (optional), and `-ExpectedDatabaseStatus` (`NOT_CONFIGURED`, `AVAILABLE`, or `UNAVAILABLE`; default `NOT_CONFIGURED`).

#### PowerShell execution policy

Windows PowerShell may block `.ps1` files before the smoke test starts, with errors such as "running scripts is disabled" or "not digitally signed." Inspect policy using the same `powershell` executable used by the examples:

```powershell
powershell -NoProfile -Command "Get-ExecutionPolicy -List"
```

For a trusted local copy, and only where local testing is permitted, the following commands use `-ExecutionPolicy Bypass` for the new PowerShell process. This does not persistently change execution policy or write policy settings to the registry. Group Policy settings (`MachinePolicy` or `UserPolicy`) take precedence. Respect organizational restrictions; if they prohibit execution, contact your administrator rather than changing machine-wide policy or attempting to override them. References: [Windows PowerShell execution policies](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_execution_policies?view=powershell-5.1), [powershell.exe parameters](https://learn.microsoft.com/en-us/powershell/module/microsoft.powershell.core/about/about_powershell_exe?view=powershell-5.1#-executionpolicy-executionpolicy).

Without database configuration, clear only the application database variables in the current shell and expect `NOT_CONFIGURED`:

```powershell
Get-ChildItem Env:EVENT_DB_* | ForEach-Object { Remove-Item -LiteralPath ('Env:' + $_.Name) }
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:\tools\apache-tomcat-10.1.44' -JavaHome 'C:\Program Files\Java\jdk-17' -ExpectedDatabaseStatus NOT_CONFIGURED
```

With the Windows-authentication configuration above, an accessible database, and the matching native JDBC DLL:

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File scripts/Test-Tomcat.ps1 -TomcatHome 'C:\tools\apache-tomcat-10.1.44' -JavaHome 'C:\Program Files\Java\jdk-17' -JdbcNativeDirectory 'C:\tools\mssql-jdbc-auth\x64' -ExpectedDatabaseStatus AVAILABLE
```

If you ran the first example, reapply the database environment configuration before running the second. Use `-ExpectedDatabaseStatus UNAVAILABLE` with deliberately unreachable local settings to check the generic connection-failure page. For SQL password authentication, omit `-JdbcNativeDirectory` and set the SQL username/password environment variables instead.

The script uses the supplied Tomcat binaries, validates version and Java runtime, creates a separate base under `target`, listens on an ephemeral loopback port, deploys the WAR, and checks HTTP 200, UTF-8, JSTL/EL, the expected database status, protected JSP 404, unknown route 404, and POST 405. It stops only its disposable JVM and retains response/log evidence. It does not start or alter an installed Tomcat service. This is an HTTP deployment check, not a browser visual review.

## Tests

`mvn -B clean verify` runs database-independent unit tests, validates configuration and JDBC resource cleanup, verifies seed passwords, and builds the WAR. It does not connect to SQL Server or start Tomcat.

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

The profile requires matching `EVENT_TEST_DB_CONFIRM` and `EVENT_DB_NAME`, complete database settings, connectivity, and SQL Server major version 16. Missing prerequisites cause failure rather than skipped tests. Use a dedicated test database with the original seed contents and sample events still in the future. Fixture transactions roll back their rows, while SQL Server identity counters may advance. The suite tests JDBC/Unicode, trusted constraints, both venue foreign keys, booking/item/current-owner event matching, inventory shapes, historical seat references, payment key scope, success uniqueness after refund, normalized email, amount/time checks, deletion protection, and script rerun refusal. These are schema checks; they do not exercise booking or payment application workflows.

Surefire reports are in `target/surefire-reports`; Failsafe reports are in `target/failsafe-reports`. Tomcat smoke logs and the captured response are under `target/tomcat-smoke-*/logs`. `mvn clean` removes `target`, including those artifacts and any native library extracted there. A successful build alone does not establish deployment or database correctness. Authentication, booking concurrency, payment workflows, performance, and browser visual rendering are outside the current test coverage.

## Troubleshooting

| Symptom | Meaning and next step |
| --- | --- |
| PowerShell reports scripts are disabled or the script is not signed | Script loading was blocked before the smoke test ran. Inspect execution policy using the command above; this is not an application, database, or Tomcat test result. |
| The script reports a missing WAR or wrong Tomcat/Java version | The script started but its prerequisites failed. Build the WAR and correct `-TomcatHome`/`-JavaHome`. |
| `NOT_CONFIGURED` when a database was expected | The launching process has no `EVENT_DB_*` variables. Set them in the same shell or service environment; copying `.env.example` alone does not configure the application. |
| `UNAVAILABLE` | The application started but its database probe failed. Check host/TCP port, database existence, account permissions, authentication mode, certificate trust, and the native DLL/JVM architecture for Windows authentication. The status intentionally omits driver details. |
| Application initialization fails after adding database settings | Partial settings, invalid booleans/port, or mixed authentication modes are rejected. Check required variable names and remove SQL username/password variables when using Windows authentication. |
| HTTP deployment check fails after the script starts | Inspect `target/tomcat-smoke-*/logs` for deployment/JSP/startup errors and compare the expected database status. Changing execution policy does not fix these failures. For manual deployment, inspect `CATALINA_BASE/logs` and confirm the context path. |
| Schema or seed rerun is refused | This is the scripts' data-preservation guard. Use an explicitly selected empty database for initial setup; rerunning is not a migration or reset operation. |
| Integration tests fail with modified or old demo data | The suite expects the original seed contents and future sample events. Initialize a separate empty test database rather than refreshing existing data automatically. |

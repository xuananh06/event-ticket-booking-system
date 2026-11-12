package com.eventbooking.integration;

import com.eventbooking.model.Role;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real HTTP requests against the isolated Tomcat started by Test-Tomcat.ps1.
 */
class AuthenticationHttpCheck {
    private static final String PASSWORD = " Http fixture password! ";
    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([A-Za-z0-9_-]{43})\"");
    private final URI base = URI.create(System.getProperty("auth.baseUrl", ""));

    @Test
    void bothIdentifiersForEveryRolePreserveSessionsPermissionsAndLegacyEmail() throws Exception {
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            unavailableUsernameForm(); return;
        }
        try (TestDatabase db = new TestDatabase()) {
            for (Role role : Role.values()) {
                String email = db.email(), username = TestDatabase.usernameFor(email);
                long id = db.auth.register("Username " + role.name(), username, email, PASSWORD, PASSWORD);
                db.changeAccount(email, role.name(), true);
                for (String identifier : List.of(" " + username.toUpperCase(Locale.ROOT) + " ", " " + email.toUpperCase(Locale.ROOT) + " ")) {
                    Session browser = new Session();
                    var page = browser.get("login"); status(200, page);
                    assertTrue(page.body().contains("for=\"identifier\">Email or username</label>"));
                    assertTrue(Pattern.compile("(?s)<input[^>]*name=\"identifier\"[^>]*type=\"text\"[^>]*autocomplete=\"username\"").matcher(page.body()).find());
                    String beforeId = browser.id(), beforeToken = token(page);
                    redirect(browser.post("login", form(beforeToken, "identifier", identifier, "password", PASSWORD)), role.getHomePath());
                    assertFalse(beforeId.equals(browser.id()), "Both identifiers must rotate the session ID; values suppressed.");
                    var home = browser.get(relative(role.getHomePath())); status(200, home);
                    assertTrue(home.body().contains("data-role=\"" + role.name() + "\""));
                    String authenticatedToken = token(home);
                    assertFalse(beforeToken.equals(authenticatedToken), "Both identifiers must rotate CSRF state; values suppressed.");
                    for (Role target : Role.values()) {
                        status(target == role ? 200 : 403, browser.get(relative(target.getHomePath())));
                    }
                    status(403, browser.post("logout", form(beforeToken)));
                    status(405, browser.get("logout"));
                    status(200, browser.get(relative(role.getHomePath())));
                    redirect(browser.post("logout", form(authenticatedToken)), "/");
                    redirect(browser.get(relative(role.getHomePath())), "/login");
                }
                for (String identifier : List.of(username, email)) {
                    Session wrong = new Session(); String csrf = token(wrong.get("login"));
                    var response = wrong.post("login", form(csrf, "identifier", identifier, "password", "Wrong password!"));
                    status(401, response); assertTrue(response.body().contains("The sign-in details are incorrect."));
                    assertFalse(response.body().contains("Wrong password!"));
                }
                db.changeAccount(email, role.name(), false);
                for (String identifier : List.of(username, email)) {
                    Session denied = new Session(); String csrf = token(denied.get("login"));
                    var response = denied.post("login", form(csrf, "identifier", identifier, "password", PASSWORD));
                    status(401, response); assertTrue(response.body().contains("The sign-in details are incorrect."));
                }
                db.changeAccount(email, role.name(), true);
                assertEquals(id, db.auth.authenticate(username, PASSWORD).getId());
            }
            String email = db.email(); long legacy = db.legacyUser("Legacy email member", email, PASSWORD);
            Session browser = new Session(); signIn(browser, email, PASSWORD, Role.CUSTOMER);
            status(200, browser.get("customer/home"));
            assertEquals(legacy, db.auth.authenticate(email, PASSWORD).getId());
            assertTrue(new com.eventbooking.dao.UserDao(java.util.Optional.of(db.source)).findByUsername(TestDatabase.usernameFor(email)).isEmpty(),
                    "Legacy email sign-in must not assign an implicit username.");
            Session unknown = new Session(); String csrf = token(unknown.get("login"));
            var absent = unknown.post("login", form(csrf, "identifier", TestDatabase.usernameFor(db.email()), "password", "DemoOnly!ChangeMe"));
            status(401, absent); assertTrue(absent.body().contains("The sign-in details are incorrect."));
        }
        System.out.println("HTTP both identifiers for all roles, session/CSRF rotation, permissions, disabled accounts and legacy email: PASS");
    }

    @Test
    void registrationRejectsMalformedAndDuplicateUsernamesIncludingSeparateRacingRequests() throws Exception {
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) {
            Session browser = new Session(); String csrf = token(browser.get("register"));
            status(503, browser.post("register", form(csrf, "fullName", "Unavailable Member", "username", "unavailable-member",
                    "email", "unavailable@example.test", "password", PASSWORD, "confirmation", PASSWORD))); return;
        }
        try (TestDatabase db = new TestDatabase()) {
            Session browser = new Session(); String email = db.email(), username = TestDatabase.usernameFor(email);
            for (String invalid : new String[]{"", "ab", "x".repeat(31), "with@sign", "bad name", "\u212aelvin", "caf\u00e9", "<script>probe</script>"}) {
                String csrf = token(browser.get("register"));
                var rejected = browser.post("register", form(csrf, "fullName", "Username Member", "username", invalid,
                        "email", email, "password", PASSWORD, "confirmation", PASSWORD));
                status(400, rejected); HttpBrowser.invalidField(rejected, "username", "username-error");
                assertFalse(rejected.body().contains("<script>probe</script>"));
                assertFalse(rejected.body().contains(PASSWORD)); assertEquals(0, db.count(email));
            }
            String csrf = token(browser.get("register"));
            var missing = browser.post("register", form(csrf, "fullName", "Missing Username", "email", email, "password", PASSWORD, "confirmation", PASSWORD));
            status(400, missing); HttpBrowser.invalidField(missing, "username", "username-error");
            csrf = token(browser.get("register"));
            redirect(browser.post("register", form(csrf, "fullName", "Username Member", "username", " " + username.toUpperCase(Locale.ROOT) + " ",
                    "email", email, "password", PASSWORD, "confirmation", PASSWORD, "role", "ADMIN")), "/login");
            assertEquals(Role.CUSTOMER, db.auth.authenticate(username, PASSWORD).getRole());
            String duplicateEmail = db.email();
            csrf = token(browser.get("register"));
            var duplicateName = browser.post("register", form(csrf, "fullName", "Duplicate Username", "username", username.toUpperCase(Locale.ROOT),
                    "email", duplicateEmail, "password", PASSWORD, "confirmation", PASSWORD));
            status(400, duplicateName); HttpBrowser.invalidField(duplicateName, "username", "username-error");
            assertFalse(duplicateName.body().contains("UX_users_username")); assertEquals(0, db.count(duplicateEmail));
            csrf = token(browser.get("register"));
            var duplicateMail = browser.post("register", form(csrf, "fullName", "Duplicate Email", "username", TestDatabase.usernameFor("other-" + email),
                    "email", email.toUpperCase(Locale.ROOT), "password", PASSWORD, "confirmation", PASSWORD));
            status(400, duplicateMail); HttpBrowser.invalidField(duplicateMail, "email", "email-error");
            assertFalse(duplicateMail.body().contains("UQ_users_email")); assertEquals(1, db.count(email));

            String firstEmail = db.email(), secondEmail = db.email(), shared = TestDatabase.usernameFor(firstEmail);
            Session first = new Session(), second = new Session();
            String firstToken = token(first.get("register")), secondToken = token(second.get("register"));
            assertFalse(firstToken.equals(secondToken), "Concurrent requests must use independent session tokens.");
            var executor = Executors.newFixedThreadPool(2); var ready = new CountDownLatch(2); var start = new CountDownLatch(1);
            try {
                var a = executor.submit(() -> racingRegistration(first, firstToken, firstEmail, shared, ready, start));
                var b = executor.submit(() -> racingRegistration(second, secondToken, secondEmail, shared.toUpperCase(Locale.ROOT), ready, start));
                assertTrue(ready.await(10, TimeUnit.SECONDS)); start.countDown();
                var one = a.get(45, TimeUnit.SECONDS); var two = b.get(45, TimeUnit.SECONDS);
                assertEquals(1, java.util.stream.Stream.of(one, two).filter(r -> r.statusCode() == 303).count(), "One registration must succeed.");
                assertEquals(1, java.util.stream.Stream.of(one, two).filter(r -> r.statusCode() == 400).count(), "One registration must receive a field error.");
                var rejected = one.statusCode() == 400 ? one : two;
                HttpBrowser.invalidField(rejected, "username", "username-error");
                assertEquals(1, db.count(firstEmail) + db.count(secondEmail), "Exactly one separate registration request may win.");
                assertEquals(Role.CUSTOMER, db.auth.authenticate(shared, PASSWORD).getRole());
            } finally { start.countDown(); executor.shutdownNow(); assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS)); }
        }
        System.out.println("HTTP username validation/escaping, distinct duplicate fields and independent registration race: PASS");
    }

    @Test
    void adminLegacyAssignmentRenameDuplicatesAndStatusPreserveUsernamesAndPasswords() throws Exception {
        if (!"AVAILABLE".equals(System.getProperty("auth.databaseExpected"))) { unavailableUsernameForm(); return; }
        try (P3Fixture f = new P3Fixture()) {
            HttpBrowser admin = new HttpBrowser(); admin.login(TestDatabase.usernameFor(f.email(f.admin)), P3Fixture.PASSWORD, "admin/home");
            var management = new com.eventbooking.service.AdminUserService(f.access, new com.eventbooking.dao.AdminUserDao(), new com.eventbooking.security.PasswordHasher());
            String missingUsernameEmail = f.db.email();
            var create = HttpBrowser.form(admin.get("admin/users"), "save", null);
            var missingUsername = admin.post(create.action(), create.fields("action", "save", "id", "", "fullName", "Missing username", "email", missingUsernameEmail,
                    "role", "CUSTOMER", "active", "true", "password", P3Fixture.PASSWORD, "confirmation", P3Fixture.PASSWORD));
            status(400, missingUsername); HttpBrowser.invalidField(missingUsername, "username", "username-error"); assertEquals(0, f.db.count(missingUsernameEmail));
            String email = f.db.email(), username = TestDatabase.usernameFor(email);
            long id = f.db.legacyUser("Legacy account", email, P3Fixture.PASSWORD);
            String hash = new com.eventbooking.dao.UserDao(java.util.Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash();
            var edit = HttpBrowser.form(admin.get("admin/users?id=" + id), "save", id);
            assertNull(URI.create(edit.action()).getQuery(), "Mutation URL must not carry a query.");
            admin.redirect(admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Legacy edited", "username", "", "email", email, "role", "CUSTOMER", "active", "true")), "admin/users");
            assertNull(management.find(f.admin, id).username());
            edit = HttpBrowser.form(admin.get("admin/users?id=" + id), "save", id);
            admin.redirect(admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Assigned member", "username", " " + username.toUpperCase(Locale.ROOT) + " ", "email", email, "role", "CUSTOMER", "active", "true")), "admin/users");
            assertEquals(username, management.find(f.admin, id).username());
            edit = HttpBrowser.form(admin.get("admin/users?id=" + id), "save", id);
            var clear = admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Must not apply", "username", "", "email", email, "role", "CUSTOMER", "active", "true"));
            status(400, clear); HttpBrowser.invalidField(clear, "username", "username-error");
            assertEquals(username, management.find(f.admin, id).username()); assertEquals("Assigned member", management.find(f.admin, id).fullName());
            String renamed = TestDatabase.usernameFor("renamed-" + email);
            edit = HttpBrowser.form(clear, "save", id);
            admin.redirect(admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Renamed member", "username", renamed, "email", email, "role", "CUSTOMER", "active", "true", "password", "ignored")), "admin/users");
            edit = HttpBrowser.form(admin.get("admin/users?id=" + id), "save", id);
            var duplicate = admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Must not apply", "username", TestDatabase.usernameFor(f.email(f.admin)).toUpperCase(Locale.ROOT), "email", email, "role", "ORGANIZER", "active", "false"));
            status(400, duplicate); HttpBrowser.invalidField(duplicate, "username", "username-error");
            assertEquals(Role.CUSTOMER, management.find(f.admin, id).role()); assertTrue(management.find(f.admin, id).active());
            edit = HttpBrowser.form(duplicate, "save", id);
            var duplicateMail = admin.post(edit.action(), edit.fields("action", "save", "id", Long.toString(id), "fullName", "Must not apply", "username", renamed, "email", f.email(f.admin), "role", "CUSTOMER", "active", "true"));
            status(400, duplicateMail); HttpBrowser.invalidField(duplicateMail, "email", "email-error");
            assertEquals(renamed, management.find(f.admin, id).username()); assertEquals(email, management.find(f.admin, id).email());
            var change = HttpBrowser.form(admin.get("admin/users"), "status", id);
            admin.redirect(admin.post(change.action(), change.fields("action", "status", "id", Long.toString(id), "active", "false", "username", "")), "admin/users");
            assertEquals(renamed, management.find(f.admin, id).username());
            Session inactive = new Session(); String csrf = token(inactive.get("login"));
            status(401, inactive.post("login", form(csrf, "identifier", renamed, "password", P3Fixture.PASSWORD)));
            change = HttpBrowser.form(admin.get("admin/users"), "status", id);
            admin.redirect(admin.post(change.action(), change.fields("action", "status", "id", Long.toString(id), "active", "true", "username", "ignored-name")), "admin/users");
            assertEquals(renamed, management.find(f.admin, id).username());
            assertTrue(hash.equals(new com.eventbooking.dao.UserDao(java.util.Optional.of(f.db.source)).findByEmail(email).orElseThrow().getPasswordHash()), "Username edits/status must preserve the encoded password.");
            Session old = new Session(); csrf = token(old.get("login"));
            status(401, old.post("login", form(csrf, "identifier", username, "password", P3Fixture.PASSWORD)));
            for (String identifier : List.of(email, renamed)) { new HttpBrowser().login(identifier, P3Fixture.PASSWORD, "customer/home"); }
        }
        System.out.println("HTTP legacy edits, username assignment/rename/clear refusal, duplicate rollback and status/password preservation: PASS");
    }

    private HttpResponse<String> racingRegistration(Session browser, String csrf, String email, String username, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown(); assertTrue(start.await(10, TimeUnit.SECONDS));
        return browser.post("register", form(csrf, "fullName", "Concurrent username member", "username", username, "email", email, "password", PASSWORD, "confirmation", PASSWORD));
    }

    private void unavailableUsernameForm() throws Exception {
        Session browser = new Session(); var page = browser.get("login"); status(200, page);
        assertTrue(page.body().contains("for=\"identifier\">Email or username</label>"));
        status(503, browser.post("login", form(token(page), "identifier", "unavailable-member", "password", PASSWORD)));
    }

    @Test
    void deployedAuthenticationContract() throws Exception {
        String expectedSource = System.getProperty("auth.databaseSourceExpected");
        if (expectedSource != null) {
            assertEquals(expectedSource, com.eventbooking.config.DatabaseConfigLoader.load().source().name(),
                    "The HTTP test JVM must use the selected database configuration source.");
        }
        assertEquals("http", base.getScheme(), "Supply the isolated HTTP base URL.");
        assertEquals("127.0.0.1", base.getHost(), "HTTP checks target loopback only.");
        assertTrue(base.getPath().endsWith("/"));
        publicPagesAndAnonymousCsrf();
        String expected = System.getProperty("auth.databaseExpected");
        assertTrue(java.util.Set.of("AVAILABLE", "NOT_CONFIGURED", "UNAVAILABLE").contains(expected));
        if (expected.equals("AVAILABLE")) {
            try (TestDatabase db = new TestDatabase()) {
                registrationAndValidation(db);
                seededRolesAndSessionLifecycle();
                assertAll("Logout from independently authenticated POST error pages",
                        () -> postErrorNavigationLogout("missing", 404),
                        () -> postErrorNavigationLogout("", 405));
                accountChangesAndExpiry(db);
            }
        } else {
            unavailableDatabase();
        }
    }

    private void publicPagesAndAnonymousCsrf() throws Exception {
        Session first = new Session();
        Session other = new Session();
        HttpResponse<String> register = first.get("register");
        status(200, register);
        assertTrue(register.body().contains("<html lang=\"en\">"));
        assertTrue(register.headers().firstValue("Content-Type").orElseThrow().contains("UTF-8"));
        String cookie = register.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Lax"));
        assertTrue(cookie.contains("Path=" + base.getPath().replaceAll("/$", "")));
        assertFalse(cookie.contains("Secure"), "This isolated test uses local HTTP.");
        assertEquals("DENY", register.headers().firstValue("X-Frame-Options").orElseThrow());
        assertTrue(register.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertTrue(register.body().contains("action=\"" + path("register") + "\""));
        assertTrue(register.body().contains("href=\"" + path("assets/css/app.css") + "\""));
        String token = token(register);
        String otherToken = token(other.get("login"));
        assertFalse(token.equals(otherToken), "Independent sessions must have different tokens.");
        for (String action : new String[]{"register", "login", "logout"}) {
            for (String invalid : new String[]{null, "x".repeat(43), otherToken}) {
                status(403, first.post(action, form(invalid, "identifier", "blocked@example.test", "username", "blocked-member", "email", "blocked@example.test", "password", PASSWORD,
                        "confirmation", PASSWORD, "fullName", "Blocked Member")));
            }
            status(403, first.post(action + "?ignored=1", form(token)));
        }
        for (Role role : Role.values()) {
            redirect(first.get(relative(role.getHomePath())), "/login");
        }
        status(200, first.get("assets/css/app.css"));
        HttpResponse<String> feedbackScript = first.get("assets/js/form-feedback.js");
        assertEquals(200, feedbackScript.statusCode());
        assertTrue(feedbackScript.headers().firstValue("Content-Type").orElseThrow().contains("javascript"));
        status(200, first.get("assets/vendor/bootstrap-5.3.8.min.css"));
        for (String view : new String[]{"home.jsp", "auth/login.jsp", "auth/register.jsp", "account/home.jsp", "error.jsp"}) {
            status(404, first.get("WEB-INF/views/" + view));
        }
        status(404, first.get("unknown-route"));
        status(405, first.post("", Map.of()));
        status(405, first.get("logout"));
        System.out.println("HTTP public pages, UTF-8, headers, assets, JSP protection, guest routes, and anonymous CSRF: PASS");
    }

    private void registrationAndValidation(TestDatabase db) throws Exception {
        Session browser = new Session();
        String token = token(browser.get("register"));
        String email = db.email();
        for (String invalid : new String[]{null, "x".repeat(43), token(new Session().get("register"))}) {
            status(403, browser.post("register", form(invalid, "fullName", "Blocked Member", "username", TestDatabase.usernameFor(email), "email", email,
                    "password", PASSWORD, "confirmation", PASSWORD)));
            assertEquals(0, db.count(email), "CSRF rejection must occur before registration.");
        }
        // Dedicated Unicode and HTML-escaping fixture; this is user input, not application copy.
        String name = "Nguyễn Minh Anh 🎟 <b>Member</b>";
        HttpResponse<String> invalid = browser.post("register", form(token, "fullName", name, "username", TestDatabase.usernameFor(email), "email", "bad-email",
                "password", PASSWORD, "confirmation", "different"));
        status(400, invalid);
        HttpBrowser.invalidField(invalid, "email", "email-error");
        HttpBrowser.invalidField(invalid, "confirmation", "confirmation-error");
        assertTrue(invalid.body().contains("The password confirmation does not match."));
        assertTrue(invalid.body().contains("Nguyễn Minh Anh 🎟 &lt;b&gt;Member&lt;/b&gt;"));
        assertFalse(invalid.body().contains(PASSWORD));
        assertFalse(Pattern.compile("type=\"password\"[^>]*value=").matcher(invalid.body()).find());
        assertTrue(token.equals(token(invalid)), "Validation must keep a usable form token.");
        HttpResponse<String> registered = browser.post("register", form(token, "fullName", name, "username", TestDatabase.usernameFor(email),
                "email", " " + email.toUpperCase(Locale.ROOT) + " ", "password", PASSWORD, "confirmation", PASSWORD,
                "role", "ADMIN", "id", "1", "is_active", "0"));
        redirect(registered, "/login");
        assertEquals(1, db.count(email));
        var profile = db.auth.authenticate(email, PASSWORD);
        assertEquals(Role.CUSTOMER, profile.getRole(), "Submitted privilege fields must be ignored.");
        assertEquals(name, profile.getFullName());
        HttpResponse<String> loginPage = browser.get("login");
        assertTrue(loginPage.body().contains("Your account is ready. Sign in to continue."));
        assertFalse(browser.get("login").body().contains("Your account is ready."), "Success flash is consumed once.");
        status(400, browser.post("register", form(token(loginPage), "fullName", name, "username", TestDatabase.usernameFor("duplicate-" + email), "email", email.toUpperCase(Locale.ROOT),
                "password", PASSWORD, "confirmation", PASSWORD)));
        assertEquals(1, db.count(email));
        signIn(browser, email, PASSWORD, Role.CUSTOMER);
        HttpResponse<String> home = browser.get("customer/home");
        status(200, home);
        assertTrue(home.body().contains("Nguyễn Minh Anh 🎟 &lt;b&gt;Member&lt;/b&gt;"));
        assertFalse(home.body().contains("<b>Member</b>"));
        assertTrue(home.body().contains("action=\"" + path("logout") + "\""));
        System.out.println("HTTP registration, validation, duplicate email, forced CUSTOMER, safe field retention, Unicode and output escaping: PASS");
    }

    private void seededRolesAndSessionLifecycle() throws Exception {
        String crossSessionToken = token(new Session().get("login"));
        for (Role role : Role.values()) {
            Session browser = new Session();
            String beforeToken = token(browser.get("login"));
            String beforeId = browser.id();
            redirect(browser.post("login", form(beforeToken, "identifier", role.name().toLowerCase(Locale.ROOT) + "@example.test",
                    "password", "DemoOnly!ChangeMe", "next", "https://example.invalid/")), role.getHomePath());
            assertFalse(beforeId.equals(browser.id()), "Successful login must rotate the session ID.");
            redirect(new Session(beforeId).get(relative(role.getHomePath())), "/login");
            HttpResponse<String> home = browser.get(relative(role.getHomePath()));
            status(200, home);
            assertTrue(home.body().contains("data-role=\"" + role.name() + "\""));
            String authenticatedToken = token(home);
            assertFalse(beforeToken.equals(authenticatedToken), "Successful login must rotate CSRF state.");
            HttpResponse<String> missingPage = browser.get("unknown-route");
            status(404, missingPage);
            assertTrue(authenticatedToken.equals(token(missingPage)), "Shared error navigation must retain a usable logout form.");
            for (Role target : Role.values()) {
                status(target == role ? 200 : 403, browser.get(relative(target.getHomePath())));
            }
            for (String invalid : new String[]{null, "x".repeat(43), crossSessionToken, beforeToken}) {
                status(403, browser.post("logout", form(invalid)));
                status(200, browser.get(relative(role.getHomePath())));
            }
            status(405, browser.get("logout"));
            status(200, browser.get(relative(role.getHomePath())));
            String authenticatedId = browser.id();
            redirect(browser.post("logout", form(authenticatedToken)), "/");
            redirect(new Session(authenticatedId).get(relative(role.getHomePath())), "/login");
            redirect(browser.get(relative(role.getHomePath())), "/login");
            status(403, browser.post("login", form(authenticatedToken, "identifier", "customer@example.test", "password", "DemoOnly!ChangeMe")));
        }
        System.out.println("HTTP all three seeded logins, nine role-route pairs, session/CSRF rotation, stale/replayed sessions, and POST-only logout: PASS");
    }

    private void accountChangesAndExpiry(TestDatabase db) throws Exception {
        String email = db.email();
        db.auth.register("Lifecycle Member", TestDatabase.usernameFor(email), email, PASSWORD, PASSWORD);
        Session browser = new Session();
        String token = token(browser.get("login"));
        String unknown = db.email();
        HttpResponse<String> absent = browser.post("login", form(token, "identifier", unknown, "password", PASSWORD));
        HttpResponse<String> wrong = browser.post("login", form(token, "identifier", email, "password", "Wrong password!"));
        status(401, absent);
        status(401, wrong);
        String generic = "The sign-in details are incorrect.";
        assertTrue(absent.body().contains(generic));
        assertTrue(wrong.body().contains(generic));
        assertFalse(wrong.body().contains("Wrong password!"));
        db.changeAccount(email, "CUSTOMER", false);
        HttpResponse<String> inactive = browser.post("login", form(token, "identifier", email, "password", PASSWORD));
        status(401, inactive);
        assertTrue(inactive.body().contains(generic));
        redirect(browser.get("customer/home"), "/login");
        db.changeAccount(email, "CUSTOMER", true);
        signIn(browser, email, PASSWORD, Role.CUSTOMER);
        db.changeAccount(email, "ORGANIZER", true);
        status(403, browser.get("customer/home"));
        status(200, browser.get("organizer/home"));
        db.changeAccount(email, "ORGANIZER", false);
        String disabledId = browser.id();
        redirect(browser.get("organizer/home"), "/login");
        redirect(new Session(disabledId).get("organizer/home"), "/login");
        db.changeAccount(email, "CUSTOMER", true);
        signIn(browser, email, PASSWORD, Role.CUSTOMER);
        String expiringToken = token(browser.get("customer/home"));
        int seconds = Integer.parseInt(System.getProperty("auth.expirySeconds"));
        assertTrue(seconds >= 1 && seconds <= 10, "Use a short timeout only in the isolated test JVM.");
        Thread.sleep((seconds + 2L) * 1000);
        redirect(browser.get("customer/home"), "/login");
        status(403, browser.post("login", form(expiringToken, "identifier", email, "password", PASSWORD)));
        System.out.println("HTTP generic failed/inactive login, live role/status changes, and real session expiry: PASS");
    }

    private void postErrorNavigationLogout(String errorPath, int expectedStatus) throws Exception {
        Session browser = new Session();
        signIn(browser, "customer@example.test", "DemoOnly!ChangeMe", Role.CUSTOMER);
        HttpResponse<String> home = browser.get("customer/home");
        status(200, home);
        String authenticatedToken = token(home);
        String authenticatedId = browser.id();
        HttpResponse<String> errorPage = browser.post(errorPath, Map.of());
        status(expectedStatus, errorPage);
        var logoutForm = Pattern.compile("(?s)<form\\s+method=\"post\"\\s+action=\"([^\"]+)\"[^>]*>(.*?)</form>").matcher(errorPage.body());
        assertTrue(logoutForm.find(), "The error page must render a logout form; response body suppressed.");
        String logoutAction = logoutForm.group(1);
        assertEquals(path("logout"), logoutAction);
        assertTrue(logoutForm.group(2).contains(">Sign out</button>"));
        var renderedToken = TOKEN.matcher(logoutForm.group(2));
        assertTrue(renderedToken.find(), "POST /" + errorPath + " HTTP " + expectedStatus + " logout form must contain a CSRF token; value suppressed.");
        String logoutToken = renderedToken.group(1);
        assertTrue(authenticatedToken.equals(logoutToken), "Error navigation must preserve the session token.");
        redirect(browser.post(logoutAction, form(logoutToken)), "/");
        for (Role role : Role.values()) {
            redirect(browser.get(relative(role.getHomePath())), "/login");
        }
        redirect(new Session(authenticatedId).get("customer/home"), "/login");
        status(403, browser.post("logout", form(logoutToken)));
        System.out.println("HTTP POST /" + errorPath + " -> " + expectedStatus + " -> rendered logout -> protected access denied: PASS");
    }

    private void unavailableDatabase() throws Exception {
        Session browser = new Session();
        String token = token(browser.get("register"));
        status(503, browser.post("register", form(token, "fullName", "Unavailable Member", "username", "unavailable-member", "email", "unavailable@example.test",
                "password", PASSWORD, "confirmation", PASSWORD)));
        token = token(browser.get("login"));
        status(503, browser.post("login", form(token, "identifier", "customer@example.test", "password", "DemoOnly!ChangeMe")));
        token = token(browser.get("login"));
        status(503, browser.post("login", form(token, "identifier", "unavailable-member", "password", "DemoOnly!ChangeMe")));
        redirect(browser.get("customer/home"), "/login");
        status(200, browser.get(""));
        System.out.println("HTTP public availability and generic authentication HTTP 503 without an available database: PASS");
    }

    private void signIn(Session browser, String identifier, String password, Role role) throws Exception {
        String token = token(browser.get("login"));
        redirect(browser.post("login", form(token, "identifier", identifier, "password", password)), role.getHomePath());
    }

    private static Map<String, String> form(String token, String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        if (token != null) {
            values.put("_csrf", token);
        }
        for (int index = 0; index < pairs.length; index += 2) {
            values.put(pairs[index], pairs[index + 1]);
        }
        return values;
    }

    private String path(String path) {
        return base.getPath() + relative(path);
    }

    private static String relative(String path) {
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private static String token(HttpResponse<String> response) {
        var match = TOKEN.matcher(response.body());
        assertTrue(match.find(), "A form must contain a session-bound token; response body suppressed.");
        return match.group(1);
    }

    private static void status(int expected, HttpResponse<String> response) {
        assertEquals(expected, response.statusCode(), "Unexpected HTTP status; response body suppressed.");
        assertFalse(response.body().contains("jdbc:sqlserver"));
        assertFalse(response.body().contains("java.sql."));
        assertFalse(response.body().contains("$2a$12$"));
        assertFalse(response.body().contains("${"));
    }

    private void redirect(HttpResponse<String> response, String target) {
        status(303, response);
        assertEquals(path(target), response.headers().firstValue("Location").orElseThrow());
        assertFalse(response.headers().firstValue("Location").orElseThrow().contains("?"));
    }

    private final class Session {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(5)).build();

        Session() {
        }

        Session(String id) {
            HttpCookie cookie = new HttpCookie("JSESSIONID", id);
            cookie.setPath(base.getPath().replaceAll("/$", ""));
            cookie.setVersion(0);
            cookies.getCookieStore().add(base, cookie);
        }

        String id() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID"))
                    .findFirst().orElseThrow().getValue();
        }

        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15)).GET().build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        HttpResponse<String> post(String path, Map<String, String> values) throws Exception {
            String encoded = values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                    .collect(java.util.stream.Collectors.joining("&"));
            return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
                            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                            .POST(HttpRequest.BodyPublishers.ofString(encoded, StandardCharsets.UTF_8)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }

        private String encode(String value) {
            return URLEncoder.encode(value, StandardCharsets.UTF_8);
        }
    }
}

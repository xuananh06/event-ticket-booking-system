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
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests against the isolated Tomcat started by Test-Tomcat.ps1. */
class AuthenticationHttpCheck {
    private static final String PASSWORD = " Http fixture password! ";
    private static final Pattern TOKEN = Pattern.compile("name=\"_csrf\" value=\"([A-Za-z0-9_-]{43})\"");
    private final URI base = URI.create(System.getProperty("auth.baseUrl", ""));

    @Test void deployedAuthenticationContract() throws Exception {
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
        } else { unavailableDatabase(); }
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
                status(403, first.post(action, form(invalid, "email", "blocked@example.test", "password", PASSWORD,
                        "confirmation", PASSWORD, "fullName", "Blocked Member")));
            }
            status(403, first.post(action + "?ignored=1", form(token)));
        }
        for (Role role : Role.values()) { redirect(first.get(relative(role.getHomePath())), "/login"); }
        status(200, first.get("assets/css/app.css"));
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
            status(403, browser.post("register", form(invalid, "fullName", "Blocked Member", "email", email,
                    "password", PASSWORD, "confirmation", PASSWORD)));
            assertEquals(0, db.count(email), "CSRF rejection must occur before registration.");
        }
        // Dedicated Unicode and HTML-escaping fixture; this is user input, not application copy.
        String name = "Nguyễn Minh Anh 🎟 <b>Member</b>";
        HttpResponse<String> invalid = browser.post("register", form(token, "fullName", name, "email", "bad-email",
                "password", PASSWORD, "confirmation", "different"));
        status(400, invalid);
        assertTrue(invalid.body().contains("The password confirmation does not match."));
        assertTrue(invalid.body().contains("Nguyễn Minh Anh 🎟 &lt;b&gt;Member&lt;/b&gt;"));
        assertFalse(invalid.body().contains(PASSWORD));
        assertFalse(Pattern.compile("type=\"password\"[^>]*value=").matcher(invalid.body()).find());
        assertTrue(token.equals(token(invalid)), "Validation must keep a usable form token.");
        HttpResponse<String> registered = browser.post("register", form(token, "fullName", name,
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
        status(400, browser.post("register", form(token(loginPage), "fullName", name, "email", email.toUpperCase(Locale.ROOT),
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
            redirect(browser.post("login", form(beforeToken, "email", role.name().toLowerCase(Locale.ROOT) + "@example.test",
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
            status(403, browser.post("login", form(authenticatedToken, "email", "customer@example.test", "password", "DemoOnly!ChangeMe")));
        }
        System.out.println("HTTP all three seeded logins, nine role-route pairs, session/CSRF rotation, stale/replayed sessions, and POST-only logout: PASS");
    }

    private void accountChangesAndExpiry(TestDatabase db) throws Exception {
        String email = db.email();
        db.auth.register("Lifecycle Member", email, PASSWORD, PASSWORD);
        Session browser = new Session();
        String token = token(browser.get("login"));
        String unknown = db.email();
        HttpResponse<String> absent = browser.post("login", form(token, "email", unknown, "password", PASSWORD));
        HttpResponse<String> wrong = browser.post("login", form(token, "email", email, "password", "Wrong password!"));
        status(401, absent); status(401, wrong);
        String generic = "Email or password is incorrect.";
        assertTrue(absent.body().contains(generic)); assertTrue(wrong.body().contains(generic));
        assertFalse(wrong.body().contains("Wrong password!"));
        db.changeAccount(email, "CUSTOMER", false);
        HttpResponse<String> inactive = browser.post("login", form(token, "email", email, "password", PASSWORD));
        status(401, inactive); assertTrue(inactive.body().contains(generic));
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
        status(403, browser.post("login", form(expiringToken, "email", email, "password", PASSWORD)));
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
        for (Role role : Role.values()) { redirect(browser.get(relative(role.getHomePath())), "/login"); }
        redirect(new Session(authenticatedId).get("customer/home"), "/login");
        status(403, browser.post("logout", form(logoutToken)));
        System.out.println("HTTP POST /" + errorPath + " -> " + expectedStatus + " -> rendered logout -> protected access denied: PASS");
    }

    private void unavailableDatabase() throws Exception {
        Session browser = new Session();
        String token = token(browser.get("register"));
        status(503, browser.post("register", form(token, "fullName", "Unavailable Member", "email", "unavailable@example.test",
                "password", PASSWORD, "confirmation", PASSWORD)));
        token = token(browser.get("login"));
        status(503, browser.post("login", form(token, "email", "customer@example.test", "password", "DemoOnly!ChangeMe")));
        redirect(browser.get("customer/home"), "/login");
        status(200, browser.get(""));
        System.out.println("HTTP public availability and generic authentication HTTP 503 without an available database: PASS");
    }

    private void signIn(Session browser, String email, String password, Role role) throws Exception {
        String token = token(browser.get("login"));
        redirect(browser.post("login", form(token, "email", email, "password", password)), role.getHomePath());
    }

    private static Map<String, String> form(String token, String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        if (token != null) { values.put("_csrf", token); }
        for (int index = 0; index < pairs.length; index += 2) { values.put(pairs[index], pairs[index + 1]); }
        return values;
    }

    private String path(String path) { return base.getPath() + relative(path); }
    private static String relative(String path) { return path.startsWith("/") ? path.substring(1) : path; }
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
        Session() { }
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
        private String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    }
}

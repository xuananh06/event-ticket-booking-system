package com.eventbooking.security;

import com.eventbooking.config.WebSettings;
import com.eventbooking.dao.UserDao;
import com.eventbooking.filter.AuthenticationFilter;
import com.eventbooking.filter.AuthorizationFilter;
import com.eventbooking.filter.CsrfFilter;
import com.eventbooking.filter.RequestSecurityFilter;
import com.eventbooking.model.Role;
import com.eventbooking.model.UserAccount;
import com.eventbooking.model.UserProfile;
import com.eventbooking.service.AuthService;
import com.eventbooking.support.HttpExchange;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SecurityFiltersTest {
    @Test void csrfIsSessionBoundRotatedAndRejectsMissingDuplicateOrExpiredValues() {
        CsrfTokens tokens = new CsrfTokens(1200);
        HttpExchange.Session first = new HttpExchange.Session();
        HttpExchange.Session second = new HttpExchange.Session();
        String token = tokens.issue(first.value);
        assertEquals(43, token.length());
        assertEquals(1200, first.timeout);
        assertEquals(token, tokens.issue(first.value));
        assertTrue(tokens.accepts(first.value, new String[]{token}));
        assertFalse(tokens.accepts(null, new String[]{token}));
        assertFalse(tokens.accepts(first.value, null));
        assertFalse(tokens.accepts(first.value, new String[]{token, token}));
        assertFalse(tokens.accepts(second.value, new String[]{token}));
        assertNotEquals(token, tokens.rotate(first.value));
        assertFalse(tokens.accepts(first.value, new String[]{token}));
        first.value.invalidate();
        assertFalse(tokens.accepts(first.value, new String[]{token}));
    }

    @Test void csrfStopsEachActionBeforeTheChainAndPreservesValidFormTokensOnValidationRenders() throws Exception {
        CsrfTokens tokens = new CsrfTokens(1200);
        for (String path : new String[]{"/register", "/login", "/logout"}) {
            HttpExchange exchange = new HttpExchange(); exchange.path = path; exchange.method = "POST";
            AtomicBoolean called = new AtomicBoolean();
            CsrfFilter filter = new CsrfFilter(tokens);
            filter.doFilter(exchange.request, exchange.response, (req, res) -> called.set(true));
            assertFalse(called.get()); assertEquals(403, exchange.status);
            String token = tokens.issue(exchange.request.getSession(true));
            exchange.parameters.put("_csrf", new String[]{token});
            filter.doFilter(exchange.request, exchange.response, (req, res) -> called.set(true));
            assertTrue(called.get()); assertEquals(token, exchange.attributes.get("csrfToken"));
            called.set(false); exchange.query = "_csrf=" + token;
            filter.doFilter(exchange.request, exchange.response, (req, res) -> called.set(true));
            assertFalse(called.get());
        }
    }

    @Test void rootPostStillReachesTheServletAndAssetsDoNotCreateSessions() throws Exception {
        HttpExchange exchange = new HttpExchange(); exchange.path = ""; exchange.method = "POST";
        AtomicBoolean called = new AtomicBoolean();
        new CsrfFilter(new CsrfTokens(1200)).doFilter(exchange.request, exchange.response, (req, res) -> called.set(true));
        assertTrue(called.get());
        exchange.path = "/assets/css/app.css"; exchange.method = "GET";
        new CsrfFilter(new CsrfTokens(1200)).doFilter(exchange.request, exchange.response, (req, res) -> { });
        assertNull(exchange.session);
    }

    @Test void guestsRedirectWithinTheActualContextAndDbOutagesNeverAllowProtectedAccess() throws Exception {
        AuthService unavailable = new AuthService(new UserDao(Optional.empty()), new PasswordHasher());
        AuthenticationFilter filter = new AuthenticationFilter(unavailable);
        HttpExchange guest = new HttpExchange(); guest.path = "/admin/home";
        AtomicBoolean called = new AtomicBoolean();
        filter.doFilter(guest.request, guest.response, (req, res) -> called.set(true));
        assertFalse(called.get()); assertEquals("/different-context/login", guest.headers.get("Location"));
        guest.session = new HttpExchange.Session();
        guest.session.attributes.put(SessionIdentity.ATTRIBUTE, new SessionIdentity(1));
        filter.doFilter(guest.request, guest.response, (req, res) -> called.set(true));
        assertFalse(called.get()); assertEquals(503, guest.status);
        guest.path = "";
        filter.doFilter(guest.request, guest.response, (req, res) -> called.set(true));
        assertTrue(called.get());
    }

    @Test void errorNavigationReceivesAnExistingSessionsTokenWithoutCreatingUnknownRouteSessions() throws Exception {
        HttpExchange exchange = new HttpExchange(); exchange.path = "/unknown-route";
        CsrfTokens tokens = new CsrfTokens(1200);
        CsrfFilter filter = new CsrfFilter(tokens);
        filter.doFilter(exchange.request, exchange.response, (req, res) -> { });
        assertNull(exchange.session);
        String token = tokens.issue(exchange.request.getSession(true));
        filter.doFilter(exchange.request, exchange.response, (req, res) -> { });
        assertEquals(token, exchange.attributes.get("csrfToken"));
    }

    @Test void postErrorNavigationRetainsExistingTokensWithoutCreatingAnonymousSessions() throws Exception {
        CsrfTokens tokens = new CsrfTokens(1200);
        CsrfFilter filter = new CsrfFilter(tokens);
        for (String path : new String[]{"/missing", ""}) {
            HttpExchange exchange = new HttpExchange(); exchange.path = path; exchange.method = "POST";
            AtomicBoolean called = new AtomicBoolean();
            filter.doFilter(exchange.request, exchange.response, (req, res) -> called.set(true));
            assertTrue(called.get(), "Unsupported POST must reach the normal 404/405 handling.");
            assertNull(exchange.session);
            var session = exchange.request.getSession(true);
            session.setAttribute(SessionIdentity.ATTRIBUTE, new SessionIdentity(7));
            String token = tokens.issue(session);
            filter.doFilter(exchange.request, exchange.response, (req, res) -> { });
            assertTrue(token.equals(exchange.attributes.get("csrfToken")), "POST error navigation needs the existing token.");
            assertSame(session, exchange.request.getSession(false));
        }
        for (String method : new String[]{"GET", "POST"}) {
            HttpExchange asset = new HttpExchange(); asset.path = "/assets/css/app.css"; asset.method = method;
            filter.doFilter(asset.request, asset.response, (req, res) -> { });
            assertNull(asset.session);
            assertNull(asset.attributes.get("csrfToken"));
        }
    }

    @Test void disabledIdentityIsInvalidatedAndAllRoleCrossingsAreDenied() throws Exception {
        UserDao disabled = new UserDao(Optional.empty()) {
            @Override public Optional<UserAccount> findById(long id) throws SQLException { return Optional.empty(); }
        };
        HttpExchange exchange = new HttpExchange(); exchange.session = new HttpExchange.Session();
        HttpExchange.Session previous = exchange.session;
        exchange.session.attributes.put(SessionIdentity.ATTRIBUTE, new SessionIdentity(7));
        new AuthenticationFilter(new AuthService(disabled, new PasswordHasher())).doFilter(exchange.request, exchange.response, (req, res) -> fail("Disabled identity reached action."));
        assertFalse(previous.valid); assertEquals("/different-context/login", exchange.headers.get("Location"));
        for (Role role : Role.values()) {
            for (Role target : Role.values()) {
                HttpExchange protectedRequest = new HttpExchange(); protectedRequest.path = target.getHomePath();
                protectedRequest.attributes.put("currentUser", new UserProfile(7, "a@example.test", "Name", role));
                AtomicBoolean called = new AtomicBoolean();
                new AuthorizationFilter().doFilter(protectedRequest.request, protectedRequest.response, (req, res) -> called.set(true));
                assertEquals(role == target, called.get());
                if (role != target) { assertEquals(403, protectedRequest.status); }
            }
        }
    }

    @Test void securityHeadersAndSessionSettingsHaveExplicitSafeDefaults() throws Exception {
        WebSettings defaults = WebSettings.fromEnvironment(Map.of());
        assertEquals(1200, defaults.sessionTimeoutSeconds()); assertFalse(defaults.secureCookies());
        assertTrue(WebSettings.fromEnvironment(Map.of("EVENT_SESSION_COOKIE_SECURE", "true")).secureCookies());
        for (String seconds : new String[]{"0", "-1", "86401", "bad"}) {
            assertThrows(IllegalArgumentException.class, () -> WebSettings.fromEnvironment(Map.of("EVENT_SESSION_TIMEOUT_SECONDS", seconds)));
        }
        assertThrows(IllegalArgumentException.class, () -> WebSettings.fromEnvironment(Map.of("EVENT_SESSION_COOKIE_SECURE", "yes")));
        HttpExchange exchange = new HttpExchange();
        new RequestSecurityFilter().doFilter(exchange.request, exchange.response, (req, res) -> { });
        assertEquals("no-store", exchange.headers.get("Cache-Control"));
        assertEquals("nosniff", exchange.headers.get("X-Content-Type-Options"));
    }
}

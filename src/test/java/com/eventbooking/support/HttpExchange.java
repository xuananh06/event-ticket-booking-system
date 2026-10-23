package com.eventbooking.support;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;

/** Small stateful servlet doubles for security behavior, not a simulated database. */
public final class HttpExchange {
    public final Map<String, Object> attributes = new HashMap<>();
    public final Map<String, String[]> parameters = new HashMap<>();
    public final Map<String, String> headers = new HashMap<>();
    public String path = "/login";
    public String method = "GET";
    public String query;
    public int status = 200;
    public boolean forwarded;
    public Session session;

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }

    public static final class Session {
        public final Map<String, Object> attributes = new HashMap<>();
        public boolean valid = true;
        public int timeout;
        public final HttpSession value = proxy(HttpSession.class, (p, m, a) -> {
            if (!valid) { throw new IllegalStateException("Expired session"); }
            return switch (m.getName()) {
                case "getAttribute" -> attributes.get(a[0]);
                case "setAttribute" -> { attributes.put((String) a[0], a[1]); yield null; }
                case "removeAttribute" -> { attributes.remove(a[0]); yield null; }
                case "setMaxInactiveInterval" -> { timeout = (Integer) a[0]; yield null; }
                case "invalidate" -> { valid = false; attributes.clear(); yield null; }
                default -> throw new UnsupportedOperationException(m.getName());
            };
        });
    }

    public final HttpServletRequest request = proxy(HttpServletRequest.class, (p, m, a) -> switch (m.getName()) {
        case "getServletPath" -> path;
        case "getMethod" -> method;
        case "getContextPath" -> "/different-context";
        case "getQueryString" -> query;
        case "getParameterValues" -> parameters.get(a[0]);
        case "getAttribute" -> attributes.get(a[0]);
        case "setAttribute" -> { attributes.put((String) a[0], a[1]); yield null; }
        case "setCharacterEncoding" -> null;
        case "getSession" -> {
            boolean create = a == null || a.length == 0 || Boolean.TRUE.equals(a[0]);
            if (session != null && !session.valid) { session = null; }
            if (session == null && create) { session = new Session(); }
            yield session == null ? null : session.value;
        }
        case "getRequestDispatcher" -> proxy(RequestDispatcher.class, (dp, dm, da) -> { forwarded = true; return null; });
        default -> throw new UnsupportedOperationException(m.getName());
    });

    public final HttpServletResponse response = proxy(HttpServletResponse.class, (p, m, a) -> switch (m.getName()) {
        case "setStatus" -> { status = (Integer) a[0]; yield null; }
        case "setHeader" -> { headers.put((String) a[0], (String) a[1]); yield null; }
        case "setCharacterEncoding" -> null;
        default -> throw new UnsupportedOperationException(m.getName());
    });
}

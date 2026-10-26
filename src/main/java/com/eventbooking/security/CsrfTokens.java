package com.eventbooking.security;

import jakarta.servlet.http.HttpSession;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public final class CsrfTokens {
    private static final String ATTRIBUTE = "csrfToken";
    private final SecureRandom random = new SecureRandom();
    private final int timeoutSeconds;

    public CsrfTokens(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }

    public String issue(HttpSession session) {
        synchronized (session) {
            session.setMaxInactiveInterval(timeoutSeconds);
            Object existing = session.getAttribute(ATTRIBUTE);
            if (existing instanceof String token) {
                return token;
            }
            return rotate(session);
        }
    }

    public String rotate(HttpSession session) {
        synchronized (session) {
            byte[] bytes = new byte[32];
            random.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            session.setAttribute(ATTRIBUTE, token);
            session.setMaxInactiveInterval(timeoutSeconds);
            return token;
        }
    }

    public boolean accepts(HttpSession session, String[] submitted) {
        if (session == null || submitted == null || submitted.length != 1 || submitted[0] == null || submitted[0].length() != 43) {
            return false;
        }
        try {
            Object saved = session.getAttribute(ATTRIBUTE);
            return saved instanceof String token && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), submitted[0].getBytes(StandardCharsets.UTF_8));
        } catch (IllegalStateException expiredSession) {
            return false;
        }
    }
}

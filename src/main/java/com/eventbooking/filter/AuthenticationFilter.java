package com.eventbooking.filter;

import com.eventbooking.controller.Web;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.SecurityPaths;
import com.eventbooking.security.SessionIdentity;
import com.eventbooking.service.AuthService;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Optional;

public final class AuthenticationFilter implements Filter {
    private final AuthService auth;
    public AuthenticationFilter(AuthService auth) { this.auth = auth; }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        HttpServletResponse output = (HttpServletResponse) response;
        String path = http.getServletPath();
        if (path.startsWith("/assets/") || path.equals("/health") || path.equals("/logout")) {
            // Logout can invalidate its own session even during a database outage.
            chain.doFilter(request, response);
            return;
        }
        boolean protectedPage = SecurityPaths.requiredRole(path).isPresent();
        HttpSession session = http.getSession(false);
        Object identity = session == null ? null : session.getAttribute(SessionIdentity.ATTRIBUTE);
        if (identity instanceof SessionIdentity account) {
            try {
                Optional<UserProfile> current = auth.currentUser(account.userId());
                if (current.isPresent()) { request.setAttribute("currentUser", current.orElseThrow()); }
                else {
                    session.invalidate();
                    Web.redirect(http, output, "/login");
                    return;
                }
            } catch (ServiceUnavailableException unavailable) {
                if (protectedPage) { Web.error(http, output, 503, Web.UNAVAILABLE); return; }
            }
        }
        if (protectedPage && request.getAttribute("currentUser") == null) {
            Web.redirect(http, output, "/login");
            return;
        }
        chain.doFilter(request, response);
    }
}

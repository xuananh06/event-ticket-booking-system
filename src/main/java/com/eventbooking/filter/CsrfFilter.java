package com.eventbooking.filter;

import com.eventbooking.controller.Web;
import com.eventbooking.security.CsrfTokens;
import com.eventbooking.security.SecurityPaths;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;

public final class CsrfFilter implements Filter {
    private final CsrfTokens tokens;
    public CsrfFilter(CsrfTokens tokens) { this.tokens = tokens; }

    @Override public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        String path = http.getServletPath();
        boolean action = SecurityPaths.authAction(path) || SecurityPaths.requiredRole(path).isPresent();
        boolean safe = Set.of("GET", "HEAD", "OPTIONS").contains(http.getMethod());
        if (action && !safe) {
            // Auth actions accept form-body tokens only; no credentials/tokens in query strings.
            if (http.getQueryString() != null || !tokens.accepts(http.getSession(false), http.getParameterValues("_csrf"))) {
                Web.error(http, (HttpServletResponse) response, 403, "This form has expired or is invalid. Reopen the page and try again.");
                return;
            }
            request.setAttribute("csrfToken", tokens.issue(http.getSession(false)));
        }
        boolean formPage = path.isEmpty() || path.equals("/register") || path.equals("/login") || SecurityPaths.requiredRole(path).isPresent();
        // Existing error navigation also needs its token on unsupported POST requests.
        boolean existingNavigation = !path.startsWith("/assets/") && !path.equals("/health") && http.getSession(false) != null;
        if ((safe && formPage) || existingNavigation) {
            request.setAttribute("csrfToken", tokens.issue(http.getSession(true)));
        }
        chain.doFilter(request, response);
    }
}

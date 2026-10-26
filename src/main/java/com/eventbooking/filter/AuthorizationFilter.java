package com.eventbooking.filter;

import com.eventbooking.controller.Web;
import com.eventbooking.model.Role;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.SecurityPaths;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Optional;

public final class AuthorizationFilter implements Filter {
    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain) throws IOException, ServletException {
        HttpServletRequest http = (HttpServletRequest) request;
        Optional<Role> required = SecurityPaths.requiredRole(http.getServletPath());
        if (required.isPresent()) {
            Object principal = request.getAttribute("currentUser");
            if (!(principal instanceof UserProfile user) || user.getRole() != required.orElseThrow()) {
                Web.error(http, (HttpServletResponse) response, 403, "You do not have permission to access this page.");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}

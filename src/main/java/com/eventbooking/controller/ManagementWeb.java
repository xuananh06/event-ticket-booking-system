package com.eventbooking.controller;

import com.eventbooking.exception.*;
import com.eventbooking.model.UserProfile;
import com.eventbooking.service.Inputs;
import jakarta.servlet.*;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.*;

/**
 * Presentation helpers shared by the concrete management screens.
 */
public final class ManagementWeb {
    private ManagementWeb() {
    }

    public static long actor(HttpServletRequest request) {
        if (!(request.getAttribute("currentUser") instanceof UserProfile user)) {
            throw DomainException.forbidden();
        }
        return user.getId();
    }

    public static Map<String, String> form(HttpServletRequest request, String... fields) {
        Map<String, String> form = new LinkedHashMap<>();
        for (String field : fields) {
            form.put(field, request.getParameter(field) == null ? "" : request.getParameter(field));
        }
        return form;
    }

    public static Boolean filterActive(String value) {
        return value == null || value.isEmpty() ? null : Inputs.active(value);
    }

    public static void success(HttpServletRequest request, HttpServletResponse response, String route) {
        request.getSession().setAttribute("managementSuccess", "Your changes have been saved.");
        Web.redirect(request, response, route);
    }

    public static void flash(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null && session.getAttribute("managementSuccess") instanceof String message) {
            request.setAttribute("successMessage", message);
            session.removeAttribute("managementSuccess");
        }
    }

    public static void failure(HttpServletRequest request, HttpServletResponse response, RuntimeException error) throws ServletException, IOException {
        if (error instanceof DomainException domain) {
            Web.error(request, response, domain.getStatus(), domain.getMessage());
        } else if (error instanceof ValidationException invalid) {
            Web.error(request, response, 400, invalid.getErrors().values().iterator().next());
        } else if (error instanceof ServiceUnavailableException) {
            Web.error(request, response, 503, "Services are temporarily unavailable. Please try again later.");
        } else {
            throw error;
        }
    }
}

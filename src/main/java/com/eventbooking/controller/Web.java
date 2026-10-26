package com.eventbooking.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class Web {
    public static final String UNAVAILABLE = "Account services are temporarily unavailable. Please try again later.";

    private Web() {
    }

    public static void view(HttpServletRequest request, HttpServletResponse response, String view) throws ServletException, IOException {
        request.getRequestDispatcher("/WEB-INF/views/" + view + ".jsp").forward(request, response);
    }

    /**
     * All callers pass fixed application routes, never request parameters.
     */
    public static void redirect(HttpServletRequest request, HttpServletResponse response, String path) {
        response.setStatus(HttpServletResponse.SC_SEE_OTHER);
        response.setHeader("Location", request.getContextPath() + path);
    }

    public static void error(HttpServletRequest request, HttpServletResponse response, int status, String message) throws ServletException, IOException {
        response.setStatus(status);
        request.setAttribute("errorMessage", message);
        view(request, response, "error");
    }

    public static String safeInput(String value, int maximum) {
        if (value == null) {
            return "";
        }
        return value.substring(0, Math.min(value.length(), maximum));
    }
}

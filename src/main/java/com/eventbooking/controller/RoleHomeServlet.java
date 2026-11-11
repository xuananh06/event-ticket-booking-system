package com.eventbooking.controller;

import com.eventbooking.model.Role;
import com.eventbooking.model.UserProfile;
import com.eventbooking.service.StatisticsService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class RoleHomeServlet extends HttpServlet {
    private final Role role;
    private final StatisticsService statistics;

    public RoleHomeServlet(Role role, StatisticsService statistics) {
        this.role = role;
        this.statistics = statistics;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!(request.getAttribute("currentUser") instanceof UserProfile user) || user.getRole() != role) {
            Web.error(request, response, 403, "You do not have permission to access this page.");
            return;
        }
        try {
            request.setAttribute("statistics", statistics.summary(user.getId(), role));
            request.setAttribute("pageTitle", "Your account");
            Web.view(request, response, "account/home");
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }
}

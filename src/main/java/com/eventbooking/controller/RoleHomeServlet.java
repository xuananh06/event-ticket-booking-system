package com.eventbooking.controller;

import com.eventbooking.model.Role;
import com.eventbooking.model.UserProfile;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

public final class RoleHomeServlet extends HttpServlet {
    private final Role role;
    public RoleHomeServlet(Role role) { this.role = role; }

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (!(request.getAttribute("currentUser") instanceof UserProfile user) || user.getRole() != role) {
            Web.error(request, response, 403, "You do not have permission to access this page."); return;
        }
        request.setAttribute("pageTitle", "Your account");
        Web.view(request, response, "account/home");
    }
}

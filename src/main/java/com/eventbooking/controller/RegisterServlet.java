package com.eventbooking.controller;

import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.UserProfile;
import com.eventbooking.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class RegisterServlet extends HttpServlet {
    private final AuthService auth;

    public RegisterServlet(AuthService auth) {
        this.auth = auth;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getAttribute("currentUser") instanceof UserProfile user) {
            Web.redirect(request, response, user.getRole().getHomePath());
            return;
        }
        request.setAttribute("pageTitle", "Create your account");
        Web.view(request, response, "auth/register");
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getAttribute("currentUser") instanceof UserProfile user) {
            Web.redirect(request, response, user.getRole().getHomePath());
            return;
        }
        try {
            auth.register(request.getParameter("fullName"), request.getParameter("email"),
                    request.getParameter("password"), request.getParameter("confirmation"));
            request.getSession().setAttribute("registrationSuccess", Boolean.TRUE);
            Web.redirect(request, response, "/login");
        } catch (ValidationException invalid) {
            response.setStatus(400);
            request.setAttribute("pageTitle", "Create your account");
            request.setAttribute("errors", invalid.getErrors());
            request.setAttribute("fullName", Web.safeInput(request.getParameter("fullName"), 150));
            request.setAttribute("email", Web.safeInput(request.getParameter("email"), 254));
            Web.view(request, response, "auth/register");
        } catch (ServiceUnavailableException unavailable) {
            Web.error(request, response, 503, Web.UNAVAILABLE);
        }
    }
}

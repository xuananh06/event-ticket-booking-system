package com.eventbooking.controller;

import com.eventbooking.exception.InvalidCredentialsException;
import com.eventbooking.exception.ServiceUnavailableException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.UserProfile;
import com.eventbooking.security.CsrfTokens;
import com.eventbooking.security.SessionIdentity;
import com.eventbooking.service.AuthService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import java.io.IOException;

public final class LoginServlet extends HttpServlet {
    private final AuthService auth;
    private final CsrfTokens tokens;

    public LoginServlet(AuthService auth, CsrfTokens tokens) {
        this.auth = auth;
        this.tokens = tokens;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getAttribute("currentUser") instanceof UserProfile user) {
            Web.redirect(request, response, user.getRole().getHomePath());
            return;
        }
        HttpSession session = request.getSession(false);
        if (session != null && Boolean.TRUE.equals(session.getAttribute("registrationSuccess"))) {
            session.removeAttribute("registrationSuccess");
            request.setAttribute("successMessage", "Your account is ready. Sign in to continue.");
        }
        show(request, response);
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getAttribute("currentUser") instanceof UserProfile user) {
            Web.redirect(request, response, user.getRole().getHomePath());
            return;
        }
        try {
            UserProfile user = auth.authenticate(request.getParameter("email"), request.getParameter("password"));
            HttpSession session = request.getSession(false);
            if (session == null) {
                Web.error(request, response, 403, "Your sign-in form has expired. Reopen the page and try again.");
                return;
            }
            request.changeSessionId();
            session.removeAttribute("registrationSuccess");
            session.setAttribute(SessionIdentity.ATTRIBUTE, new SessionIdentity(user.getId()));
            tokens.rotate(session);
            Web.redirect(request, response, user.getRole().getHomePath());
        } catch (ValidationException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", invalid.getErrors());
            request.setAttribute("email", Web.safeInput(request.getParameter("email"), 254));
            show(request, response);
        } catch (InvalidCredentialsException invalid) {
            response.setStatus(401);
            request.setAttribute("errorMessage", invalid.getMessage());
            request.setAttribute("email", Web.safeInput(request.getParameter("email"), 254));
            show(request, response);
        } catch (ServiceUnavailableException unavailable) {
            Web.error(request, response, 503, Web.UNAVAILABLE);
        }
    }

    private void show(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        request.setAttribute("pageTitle", "Welcome back");
        Web.view(request, response, "auth/login");
    }
}

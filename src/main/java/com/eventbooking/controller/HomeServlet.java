package com.eventbooking.controller;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;

public final class HomeServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/html;charset=UTF-8");
        request.setAttribute("pageTitle", "A place to connect");
        request.setAttribute("welcomeMessage", "Your next connection starts here.");
        request.setAttribute("capabilities", List.of("Your own account", "Protected sign-in sessions", "Access matched to your role", "Unicode names supported"));
        request.getRequestDispatcher("/WEB-INF/views/home.jsp").forward(request, response);
    }
}

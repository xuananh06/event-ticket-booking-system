package com.eventbooking.controller;

import com.eventbooking.service.DatabaseProbe.Status;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class HomeServlet extends HttpServlet {
    private final Status databaseStatus;

    public HomeServlet(Status databaseStatus) {
        this.databaseStatus = Objects.requireNonNull(databaseStatus);
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws ServletException, IOException {
        response.setContentType("text/html;charset=UTF-8");
        request.setAttribute("welcomeMessage", "Chào mừng đến với hệ thống đặt vé sự kiện");
        request.setAttribute("databaseStatus", databaseStatus.name());
        request.setAttribute("components", List.of("Java 17", "Jakarta Servlet", "JSP / JSTL / EL", "SQL Server 2022"));
        request.getRequestDispatcher("/WEB-INF/views/home.jsp").forward(request, response);
    }
}

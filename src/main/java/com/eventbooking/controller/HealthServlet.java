package com.eventbooking.controller;

import com.eventbooking.service.DatabaseProbe.Status;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** A generic startup snapshot for deployment checks, separate from product navigation. */
public final class HealthServlet extends HttpServlet {
    private final Status databaseStatus;
    public HealthServlet(Status databaseStatus) { this.databaseStatus = databaseStatus; }

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"database\":\"" + databaseStatus.name() + "\"}");
    }
}

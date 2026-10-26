package com.eventbooking.controller;

import com.eventbooking.model.EventFilter;
import com.eventbooking.service.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import java.io.IOException;

public final class PublicEventServlet extends HttpServlet {
    private final CatalogService catalog;
    private final boolean detail;

    public PublicEventServlet(CatalogService catalog, boolean detail) {
        this.catalog = catalog;
        this.detail = detail;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            if (detail) {
                request.setAttribute("event", catalog.detail(Inputs.id(request.getParameter("id"), "id")));
                request.setAttribute("pageTitle", "Event details");
                Web.view(request, response, "events/detail");
            } else {
                EventFilter filter = EventFilter.parse(request.getParameter("q"), request.getParameter("category"), request.getParameter("venue"), request.getParameter("from"), request.getParameter("to"), "", request.getParameter("page"), request.getParameter("size"));
                request.setAttribute("result", catalog.list(filter));
                request.setAttribute("categories", catalog.categories());
                request.setAttribute("venues", catalog.venues());
                request.setAttribute("pageTitle", "Upcoming events");
                request.setAttribute("pagingRoute", "/events");
                Web.view(request, response, "events/list");
            }
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }
}

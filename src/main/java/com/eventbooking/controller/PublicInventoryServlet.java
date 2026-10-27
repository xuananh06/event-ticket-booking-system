package com.eventbooking.controller;

import com.eventbooking.model.Page;
import com.eventbooking.model.SeatLayout;
import com.eventbooking.service.Inputs;
import com.eventbooking.service.PublicInventoryService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public final class PublicInventoryServlet extends HttpServlet {
    private final PublicInventoryService inventory;

    public PublicInventoryServlet(PublicInventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            var view = inventory.view(Inputs.id(request.getParameter("eventId"), "eventId"), Page.number(request.getParameter("page")), Page.size(request.getParameter("size")));
            request.setAttribute("event", view.event());
            request.setAttribute("result", view.page());
            request.setAttribute("rows", SeatLayout.rows(view.page().items()));
            request.setAttribute("summary", view.summary());
            request.setAttribute("editable", false);
            request.setAttribute("pagingRoute", "/events/seats");
            request.setAttribute("pageTitle", "Seat availability");
            Web.view(request, response, "events/seats");
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }
}

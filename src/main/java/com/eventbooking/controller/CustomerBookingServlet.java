package com.eventbooking.controller;

import com.eventbooking.exception.DomainException;
import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.Page;
import com.eventbooking.model.SeatLayout;
import com.eventbooking.service.BookingService;
import com.eventbooking.service.Inputs;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

public final class CustomerBookingServlet extends HttpServlet {
    private final BookingService bookings;

    public CustomerBookingServlet(BookingService bookings) { this.bookings = bookings; }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            if (request.getServletPath().equals("/customer/events/seats")) {
                selection(request, response, Inputs.id(request.getParameter("eventId"), "eventId"), Page.number(request.getParameter("page")), Page.size(request.getParameter("size")));
            } else if (request.getServletPath().equals("/customer/bookings/checkout")) {
                var summary = bookings.checkout(ManagementWeb.actor(request), Inputs.id(request.getParameter("bookingId"), "bookingId"));
                request.setAttribute("summary", summary);
                request.setAttribute("booking", summary.booking());
                request.setAttribute("pageTitle", "Reservation checkout");
                Web.view(request, response, "customer/checkout");
            } else {
                Web.error(request, response, 405, "Use the reservation form to submit this action.");
            }
        } catch (RuntimeException failure) { ManagementWeb.failure(request, response, failure); }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        if (request.getServletPath().equals("/customer/bookings/expire")) {
            try {
                long id = Inputs.id(request.getParameter("bookingId"), "bookingId");
                bookings.expire(ManagementWeb.actor(request), id);
                Web.redirect(request, response, "/customer/bookings/checkout?bookingId=" + id);
            } catch (RuntimeException failure) { ManagementWeb.failure(request, response, failure); }
            return;
        }
        if (!request.getServletPath().equals("/customer/bookings/hold")) {
            Web.error(request, response, 405, "This page does not accept that action.");
            return;
        }
        Long event = null;
        Integer page = null, size = null;
        try {
            event = Inputs.id(request.getParameter("eventId"), "eventId");
            page = Page.number(request.getParameter("page"));
            size = Page.size(request.getParameter("size"));
            long id = bookings.hold(ManagementWeb.actor(request), event, request.getParameterValues("eventSeatId"));
            Web.redirect(request, response, "/customer/bookings/checkout?bookingId=" + id);
        } catch (RuntimeException failure) {
            boolean validation = failure instanceof ValidationException;
            boolean conflict = failure instanceof DomainException domain && domain.getStatus() == 409;
            if (event == null || page == null || size == null || (!validation && !conflict)) {
                ManagementWeb.failure(request, response, failure);
                return;
            }
            response.setStatus(validation ? 400 : 409);
            if (failure instanceof ValidationException invalid) { request.setAttribute("errors", invalid.getErrors()); }
            else { request.setAttribute("errorMessage", failure.getMessage()); }
            try { selection(request, response, event, page, size); }
            catch (RuntimeException rendering) { ManagementWeb.failure(request, response, rendering); }
        }
    }

    private void selection(HttpServletRequest request, HttpServletResponse response, long eventId, int page, int size) throws ServletException, IOException {
        var view = bookings.selection(ManagementWeb.actor(request), eventId, page, size);
        Set<String> submitted = new HashSet<>();
        String[] values = request.getParameterValues("eventSeatId");
        if (values != null) {
            for (int i = 0; i < Math.min(values.length, 50); i++) {
                if (values[i] == null || values[i].length() > 20) { continue; }
                try { submitted.add(String.valueOf(Inputs.id(values[i], "eventSeatId"))); }
                catch (ValidationException ignored) { }
            }
        }
        Set<String> visible = new HashSet<>();
        BigDecimal provisional = new BigDecimal("0.00");
        int selectedCount = 0;
        for (var seat : view.page().items()) {
            if (submitted.contains(String.valueOf(seat.id()))) {
                visible.add(String.valueOf(seat.id()));
                if (seat.state().equals("AVAILABLE")) { provisional = provisional.add(seat.price()); selectedCount++; }
            }
        }
        request.setAttribute("event", view.event());
        request.setAttribute("result", view.page());
        request.setAttribute("rows", SeatLayout.rows(view.page().items()));
        request.setAttribute("selected", visible);
        request.setAttribute("selectedCount", selectedCount);
        request.setAttribute("provisional", provisional.toPlainString());
        request.setAttribute("pagingRoute", "/customer/events/seats");
        request.setAttribute("pageTitle", "Select seats");
        Web.view(request, response, "customer/seats");
    }
}

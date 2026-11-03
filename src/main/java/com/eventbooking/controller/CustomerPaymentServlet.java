package com.eventbooking.controller;

import com.eventbooking.exception.*;
import com.eventbooking.model.Page;
import com.eventbooking.service.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;

public final class CustomerPaymentServlet extends HttpServlet {
    private final BookingHistoryService history;
    private final PaymentService payments;

    public CustomerPaymentServlet(BookingHistoryService history, PaymentService payments) {
        this.history = history; this.payments = payments;
    }

    private record Context(int page, int size, String status, int paymentPage, int paymentSize) {
        String query() { return "&page=" + page + "&size=" + size + "&status=" + status + "&paymentPage=" + paymentPage + "&paymentSize=" + paymentSize; }
    }
    private record Feedback(long booking, long payment, boolean replay) { }
    private record CancelFeedback(long booking, boolean repeated) { }

    private static String optional(HttpServletRequest request, String field) {
        return request.getParameterValues(field) == null ? null : PaymentRules.single(request.getParameterValues(field), field);
    }

    private static Context context(HttpServletRequest request) {
        return new Context(Page.number(optional(request, "page")), Page.size(optional(request, "size")), PaymentRules.status(optional(request, "status")),
                Page.number(optional(request, "paymentPage")), Page.size(optional(request, "paymentSize")));
    }

    private static long booking(HttpServletRequest request) {
        return Inputs.id(PaymentRules.single(request.getParameterValues("bookingId"), "bookingId"), "bookingId");
    }

    @Override protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            Context state = context(request);
            attributes(request, state);
            if (request.getServletPath().equals("/customer/bookings")) {
                request.setAttribute("result", history.list(ManagementWeb.actor(request), state.status, state.page, state.size));
                request.setAttribute("pageTitle", "My bookings");
                Web.view(request, response, "customer/bookings");
            } else if (request.getServletPath().equals("/customer/bookings/pay")) {
                Web.error(request, response, 405, "Use a simulation button to submit a payment.");
            } else if (request.getServletPath().equals("/customer/bookings/cancel")) {
                cancellation(request, response, booking(request), state);
            } else {
                long id = booking(request);
                // Preparing a new attempt is read-only. POST never generates a replacement key.
                detail(request, response, id, state, UUID.randomUUID().toString());
            }
        } catch (RuntimeException failure) { ManagementWeb.failure(request, response, failure); }
    }

    @Override protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        boolean cancel = request.getServletPath().equals("/customer/bookings/cancel");
        if (!cancel && !request.getServletPath().equals("/customer/bookings/pay")) {
            Web.error(request, response, 405, "This page does not accept that action."); return;
        }
        Long id = null;
        Context state = null;
        String key = null;
        try {
            state = context(request); id = booking(request);
            if (cancel) {
                if (!"yes".equals(PaymentRules.single(request.getParameterValues("confirm"), "confirm"))) {
                    throw Inputs.invalid("confirm", "Confirm the whole-booking cancellation before submitting.");
                }
                var result = payments.cancel(ManagementWeb.actor(request), id);
                request.getSession().setAttribute("cancelFeedback", new CancelFeedback(id, result.repeated()));
                Web.redirect(request, response, "/customer/bookings/detail?bookingId=" + id + state.query());
                return;
            }
            key = PaymentRules.key(PaymentRules.single(request.getParameterValues("paymentKey"), "paymentKey"));
            String outcome = PaymentRules.outcome(PaymentRules.single(request.getParameterValues("outcome"), "outcome"));
            var result = payments.pay(ManagementWeb.actor(request), id, key, outcome);
            request.getSession().setAttribute("paymentFeedback", new Feedback(id, result.attempt().id(), result.replay()));
            Web.redirect(request, response, "/customer/bookings/detail?bookingId=" + id + state.query());
        } catch (RuntimeException failure) {
            if (id != null && state != null && (failure instanceof ValidationException || failure instanceof DomainException domain && domain.getStatus() == 409)) {
                response.setStatus(failure instanceof ValidationException ? 400 : 409);
                if (failure instanceof ValidationException invalid) { request.setAttribute("errors", invalid.getErrors()); }
                else { request.setAttribute("errorMessage", failure.getMessage()); }
                try {
                    if (cancel) { cancellation(request, response, id, state); }
                    else { detail(request, response, id, state, key); }
                }
                catch (RuntimeException rendering) { ManagementWeb.failure(request, response, rendering); }
            } else { ManagementWeb.failure(request, response, failure); }
        }
    }

    private void detail(HttpServletRequest request, HttpServletResponse response, long id, Context state, String key) throws ServletException, IOException {
        HttpSession session = request.getSession(false);
        Feedback feedback = session != null && session.getAttribute("paymentFeedback") instanceof Feedback f && f.booking == id ? f : null;
        var view = history.detail(ManagementWeb.actor(request), id, state.paymentPage, state.paymentSize, feedback == null ? null : feedback.payment);
        if (feedback != null) {
            session.removeAttribute("paymentFeedback");
            request.setAttribute("recorded", view.recorded());
            request.setAttribute("wasReplay", feedback.replay);
        }
        if (session != null && session.getAttribute("cancelFeedback") instanceof CancelFeedback cancelled && cancelled.booking == id) {
            session.removeAttribute("cancelFeedback");
            request.setAttribute("successMessage", cancelled.repeated
                    ? "This booking was already cancelled. No additional refund or seat release was performed."
                    : "The whole booking was cancelled. Any successful simulated payment was refunded in full.");
        }
        attributes(request, state);
        request.setAttribute("summary", view.summary());
        request.setAttribute("booking", view.summary().booking());
        request.setAttribute("attempts", view.attempts());
        request.setAttribute("canPay", view.canPay());
        request.setAttribute("canCancel", view.canCancel());
        request.setAttribute("paymentKey", key);
        request.setAttribute("pageTitle", request.getServletPath().endsWith("/checkout") ? "Reservation checkout" : "Booking details");
        Web.view(request, response, "customer/checkout");
    }

    private void cancellation(HttpServletRequest request, HttpServletResponse response, long id, Context state) throws ServletException, IOException {
        var view = history.detail(ManagementWeb.actor(request), id, state.paymentPage, state.paymentSize, null);
        attributes(request, state);
        request.setAttribute("summary", view.summary()); request.setAttribute("booking", view.summary().booking());
        request.setAttribute("canCancel", view.canCancel()); request.setAttribute("pageTitle", "Confirm cancellation");
        Web.view(request, response, "customer/cancel");
    }

    private static void attributes(HttpServletRequest request, Context state) {
        request.setAttribute("historyPage", state.page); request.setAttribute("historySize", state.size); request.setAttribute("historyStatus", state.status);
        request.setAttribute("paymentPage", state.paymentPage); request.setAttribute("paymentSize", state.paymentSize);
    }
}

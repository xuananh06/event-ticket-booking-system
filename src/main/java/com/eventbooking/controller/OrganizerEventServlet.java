package com.eventbooking.controller;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.*;
import com.eventbooking.service.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.*;

public final class OrganizerEventServlet extends HttpServlet {
    private final EventService service;

    public OrganizerEventServlet(EventService service) {
        this.service = service;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            Long id = Inputs.optionalId(request.getParameter("id"), "id");
            if (id != null) {
                Event event = service.owned(ManagementWeb.actor(request), id);
                request.setAttribute("event", event);
                form.put("id", String.valueOf(id));
                form.put("title", event.title());
                form.put("description", event.description());
                form.put("category", String.valueOf(event.categoryId()));
                form.put("venue", String.valueOf(event.venueId()));
                form.put("startsAt", event.getStartsInput());
                form.put("endsAt", event.getEndsInput());
            }
            request.setAttribute("form", form);
            render(request, response);
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Map<String, String> form = ManagementWeb.form(request, "id", "title", "description", "category", "venue", "startsAt", "endsAt");
        request.setAttribute("form", form);
        try {
            long actor = ManagementWeb.actor(request);
            String action = request.getParameter("action");
            if ("save".equals(action)) {
                service.save(actor, Inputs.optionalId(form.get("id"), "id"), Inputs.id(form.get("category"), "category"), Inputs.id(form.get("venue"), "venue"), form.get("title"), form.get("description"), form.get("startsAt"), form.get("endsAt"));
            } else if ("delete".equals(action)) {
                service.delete(actor, Inputs.id(form.get("id"), "id"));
            } else {
                throw Inputs.invalid("action", "Choose a supported action.");
            }
            ManagementWeb.success(request, response, "/organizer/events");
        } catch (ValidationException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", invalid.getErrors());
            form.replaceAll((key, value) -> Web.safeInput(value, 10000));
            try {
                render(request, response);
            } catch (RuntimeException failure) {
                ManagementWeb.failure(request, response, failure);
            }
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        long actor = ManagementWeb.actor(request);
        EventFilter filter = EventFilter.parse(request.getParameter("q"), request.getParameter("category"), request.getParameter("venue"), request.getParameter("from"), request.getParameter("to"), request.getParameter("status"), request.getParameter("page"), request.getParameter("size"));
        request.setAttribute("result", service.owned(actor, filter));
        request.setAttribute("categories", service.categories(actor));
        request.setAttribute("venues", service.venues(actor));
        request.setAttribute("pageTitle", "Your events");
        request.setAttribute("pagingRoute", "/organizer/events");
        ManagementWeb.flash(request);
        Web.view(request, response, "organizer/events");
    }
}

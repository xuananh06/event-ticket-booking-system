package com.eventbooking.controller;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.Page;
import com.eventbooking.model.SeatLayout;
import com.eventbooking.service.Inputs;
import com.eventbooking.service.InventoryService;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

public final class OrganizerInventoryServlet extends HttpServlet {
    private final InventoryService inventory;

    public OrganizerInventoryServlet(InventoryService inventory) {
        this.inventory = inventory;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            render(request, response, Inputs.id(request.getParameter("eventId"), "eventId"), Page.number(request.getParameter("page")), Page.size(request.getParameter("size")));
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Long eventId = null;
        Integer page = null, size = null;
        request.setAttribute("priceValue", Web.safeInput(request.getParameter("price"), 64));
        String[] submitted = request.getParameterValues("eventSeatId");
        request.setAttribute("selected", submitted == null ? Set.of() : Arrays.stream(submitted).limit(50)
                .map(value -> Web.safeInput(value, 20)).collect(Collectors.toSet()));
        try {
            eventId = Inputs.id(request.getParameter("eventId"), "eventId");
            page = Page.number(request.getParameter("page"));
            size = Page.size(request.getParameter("size"));
            long actor = ManagementWeb.actor(request);
            String message;
            if ("generate".equals(request.getParameter("action"))) {
                int added = inventory.generate(actor, eventId, request.getParameter("price"));
                message = added == 0 ? "Inventory is already up to date. Existing prices were preserved."
                        : "Added " + added + " inventory seats. Existing prices were preserved.";
            } else if ("price".equals(request.getParameter("action"))) {
                inventory.price(actor, eventId, submitted, request.getParameter("price"));
                message = "Selected seat prices have been updated.";
            } else if ("publish".equals(request.getParameter("action"))) {
                if (!"publish".equals(request.getParameter("confirmation"))) {
                    throw Inputs.invalid("confirmation", "Confirm that inventory, prices and event details are final.");
                }
                inventory.publish(actor, eventId);
                message = "The event has been published. Its details, inventory and prices are now read-only.";
            } else {
                throw Inputs.invalid("action", "Choose a supported inventory action.");
            }
            request.getSession().setAttribute("managementSuccess", message);
            Web.redirect(request, response, "/organizer/events/inventory?eventId=" + eventId);
        } catch (ValidationException invalid) {
            if (eventId == null || page == null || size == null) {
                ManagementWeb.failure(request, response, invalid);
                return;
            }
            response.setStatus(400);
            request.setAttribute("errors", invalid.getErrors());
            try {
                render(request, response, eventId, page, size);
            } catch (RuntimeException failure) {
                ManagementWeb.failure(request, response, failure);
            }
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }

    private void render(HttpServletRequest request, HttpServletResponse response, long eventId, int page, int size) throws ServletException, IOException {
        if (request.getAttribute("selected") == null) {
            request.setAttribute("selected", Set.of());
        }
        var view = inventory.view(ManagementWeb.actor(request), eventId, page, size);
        request.setAttribute("event", view.event());
        request.setAttribute("result", view.page());
        request.setAttribute("summary", view.summary());
        request.setAttribute("rows", SeatLayout.rows(view.page().items()));
        request.setAttribute("editable", view.editable());
        request.setAttribute("editReasons", view.editReasons());
        request.setAttribute("publicationReasons", view.publicationReasons());
        request.setAttribute("ready", view.ready());
        request.setAttribute("pagingRoute", "/organizer/events/inventory");
        request.setAttribute("pageTitle", "Event inventory");
        ManagementWeb.flash(request);
        Web.view(request, response, "organizer/inventory");
    }
}

package com.eventbooking.controller;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.*;
import com.eventbooking.service.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;

import java.io.IOException;
import java.util.*;

public final class AdminUserServlet extends HttpServlet {
    private final AdminUserService service;

    public AdminUserServlet(AdminUserService service) {
        this.service = service;
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("active", "true");
            form.put("role", "CUSTOMER");
            Long id = Inputs.optionalId(request.getParameter("id"), "id");
            if (id != null) {
                ManagedUser u = service.find(ManagementWeb.actor(request), id);
                form.put("id", String.valueOf(id));
                form.put("fullName", u.fullName());
                form.put("email", u.email());
                form.put("role", u.role().name());
                form.put("active", String.valueOf(u.active()));
            }
            request.setAttribute("form", form);
            render(request, response);
        } catch (RuntimeException failure) {
            ManagementWeb.failure(request, response, failure);
        }
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Map<String, String> form = ManagementWeb.form(request, "id", "fullName", "email", "role", "active");
        request.setAttribute("form", form);
        try {
            long actor = ManagementWeb.actor(request);
            String action = request.getParameter("action");
            if ("save".equals(action)) {
                Long id = Inputs.optionalId(form.get("id"), "id");
                Role role = AdminUserService.role(form.get("role"));
                boolean active = Inputs.active(form.get("active"));
                if (id == null) {
                    service.create(actor, form.get("fullName"), form.get("email"), request.getParameter("password"), request.getParameter("confirmation"), role, active);
                } else {
                    service.save(actor, id, form.get("fullName"), form.get("email"), role, active);
                }
            } else if ("status".equals(action)) {
                service.status(actor, Inputs.id(form.get("id"), "id"), Inputs.active(form.get("active")));
            } else if ("delete".equals(action)) {
                service.delete(actor, Inputs.id(form.get("id"), "id"));
            } else {
                throw Inputs.invalid("action", "Choose a supported action.");
            }
            ManagementWeb.success(request, response, "/admin/users");
        } catch (ValidationException invalid) {
            response.setStatus(400);
            request.setAttribute("errors", invalid.getErrors());
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
        request.setAttribute("result", service.list(ManagementWeb.actor(request), request.getParameter("q"), ManagementWeb.filterActive(request.getParameter("filterActive")), Page.number(request.getParameter("page")), Page.size(request.getParameter("size"))));
        request.setAttribute("roles", Role.values());
        request.setAttribute("pageTitle", "Accounts");
        request.setAttribute("pagingRoute", "/admin/users");
        ManagementWeb.flash(request);
        Web.view(request, response, "admin/users");
    }
}

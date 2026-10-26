package com.eventbooking.controller;

import com.eventbooking.exception.ValidationException;
import com.eventbooking.model.*;
import com.eventbooking.service.*;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.*;

/** Category, venue and physical-seat administration, using explicit service operations. */
public final class ReferenceServlet extends HttpServlet {
    public enum Kind { CATEGORIES, VENUES, SEATS }
    private final ReferenceService service;
    private final Kind kind;
    public ReferenceServlet(ReferenceService service,Kind kind) { this.service=service;this.kind=kind; }
    private String route() { return "/admin/"+kind.name().toLowerCase(Locale.ROOT); }
    @Override protected void doGet(HttpServletRequest request,HttpServletResponse response) throws ServletException,IOException {
        try {
            Map<String,String> form=new LinkedHashMap<>();
            form.put("active","true");
            Long id=Inputs.optionalId(request.getParameter("id"),"id");
            long actor=ManagementWeb.actor(request);
            if(id!=null) {
                form.put("id",id.toString());
                switch(kind) {
                    case CATEGORIES -> { Category item=service.category(actor,id); form.put("name",item.name());form.put("active",String.valueOf(item.active())); }
                    case VENUES -> { Venue item=service.venue(actor,id);form.put("name",item.name());form.put("address",item.address());form.put("active",String.valueOf(item.active())); }
                    case SEATS -> { Seat item=service.seat(actor,id);form.put("venueId",String.valueOf(item.venueId()));form.put("rowLabel",item.rowLabel());form.put("seatNumber",String.valueOf(item.seatNumber()));form.put("active",String.valueOf(item.active()));request.setAttribute("currentVenue",item.venueName()); }
                }
            }
            request.setAttribute("form",form);
            render(request,response);
        } catch(RuntimeException failure) { ManagementWeb.failure(request,response,failure); }
    }
    @Override protected void doPost(HttpServletRequest request,HttpServletResponse response) throws ServletException,IOException {
        Map<String,String> form=ManagementWeb.form(request,"id","name","address","venueId","rowLabel","seatNumber","active");
        request.setAttribute("form",form);
        try {
            long actor=ManagementWeb.actor(request);
            String action=request.getParameter("action");
            if("save".equals(action)) {
                Long id=Inputs.optionalId(form.get("id"),"id"); boolean active=Inputs.active(form.get("active"));
                switch(kind) {
                    case CATEGORIES -> service.saveCategory(actor,id,form.get("name"),active);
                    case VENUES -> service.saveVenue(actor,id,form.get("name"),form.get("address"),active);
                    case SEATS -> service.saveSeat(actor,id,Inputs.id(form.get("venueId"),"venueId"),form.get("rowLabel"),Inputs.positive(form.get("seatNumber"),"seatNumber",0,Integer.MAX_VALUE),active);
                }
            } else if("delete".equals(action) || "status".equals(action)) {
                long id=Inputs.id(form.get("id"),"id");
                if("delete".equals(action)) {
                    switch(kind) { case CATEGORIES -> service.deleteCategory(actor,id);case VENUES -> service.deleteVenue(actor,id);case SEATS -> service.deleteSeat(actor,id); }
                } else {
                    boolean active=Inputs.active(form.get("active"));
                    switch(kind) { case CATEGORIES -> service.categoryStatus(actor,id,active);case VENUES -> service.venueStatus(actor,id,active);case SEATS -> service.seatStatus(actor,id,active); }
                }
            } else { throw Inputs.invalid("action","Choose a supported action."); }
            ManagementWeb.success(request,response,route());
        } catch(ValidationException invalid) {
            response.setStatus(400); request.setAttribute("errors",invalid.getErrors());
            try { render(request,response); } catch(RuntimeException failure) { ManagementWeb.failure(request,response,failure); }
        } catch(RuntimeException failure) { ManagementWeb.failure(request,response,failure); }
    }
    private void render(HttpServletRequest request,HttpServletResponse response) throws ServletException,IOException {
        int page=Page.number(request.getParameter("page")),size=Page.size(request.getParameter("size"));
        Boolean active=ManagementWeb.filterActive(request.getParameter("filterActive"));
        long actor=ManagementWeb.actor(request);
        switch(kind) {
            case CATEGORIES -> request.setAttribute("result",service.categories(actor,active,page,size));
            case VENUES -> request.setAttribute("result",service.venues(actor,active,page,size));
            case SEATS -> {
                request.setAttribute("result",service.seats(actor,Inputs.optionalId(request.getParameter("filterVenue"),"filterVenue"),active,page,size));
                request.setAttribute("venues",service.venues(actor,null,1,50).items());
            }
        }
        request.setAttribute("kind",kind.name().toLowerCase(Locale.ROOT));
        request.setAttribute("pagingRoute",route());
        request.setAttribute("pageTitle",switch(kind){case CATEGORIES->"Categories";case VENUES->"Venues";case SEATS->"Physical seats";});
        ManagementWeb.flash(request);
        Web.view(request,response,"admin/references");
    }
}

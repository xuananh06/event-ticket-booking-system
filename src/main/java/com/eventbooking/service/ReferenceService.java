package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;
import java.sql.*;

public final class ReferenceService {
    private final JdbcAccess database;
    private final ReferenceDao references;
    public ReferenceService(JdbcAccess database, ReferenceDao references) { this.database=database; this.references=references; }
    public Page<Category> categories(long actor,Boolean active,int page,int size) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.categories(c,active,page,size); }); }
    public Page<Venue> venues(long actor,Boolean active,int page,int size) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.venues(c,active,page,size); }); }
    public Page<Seat> seats(long actor,Long venue,Boolean active,int page,int size) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.seats(c,venue,active,page,size); }); }
    public Category category(long actor,long id) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.category(c,id); }); }
    public Venue venue(long actor,long id) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.venue(c,id); }); }
    public Seat seat(long actor,long id) { return database.read(c->{ ActorDao.require(c,actor,Role.ADMIN); return references.seat(c,id); }); }
    public long saveCategory(long actor,Long id,String name,boolean active) {
        String validated=Inputs.text(name,"name",100);
        return database.write(c->{ ActorDao.require(c,actor,Role.ADMIN);
            if(id!=null) { references.category(c,id); if(!active) { references.categoryMayDisable(c,id); } }
            return references.saveCategory(c,id,validated,active);
        });
    }
    public long saveVenue(long actor,Long id,String name,String address,boolean active) {
        String validated=Inputs.text(name,"name",150), location=Inputs.text(address,"address",300);
        return database.write(c->{ ActorDao.require(c,actor,Role.ADMIN);
            if(id!=null) { references.venue(c,id); if(!active) { references.venueMayDisable(c,id); } }
            return references.saveVenue(c,id,validated,location,active);
        });
    }
    public long saveSeat(long actor,Long id,long venue,String row,int number,boolean active) {
        String label=Inputs.text(row,"rowLabel",10);
        if(venue<=0) { throw Inputs.invalid("venueId","Choose a venue."); }
        if(number<=0) { throw Inputs.invalid("seatNumber","Enter a positive seat number."); }
        return database.write(c->{ ActorDao.require(c,actor,Role.ADMIN);
            Seat previous=id==null?null:references.seat(c,id);
            Venue parent=references.venue(c,venue);
            if(!parent.isActive() && (previous==null || previous.venueId()!=venue)) { throw DomainException.conflict("Choose an active venue for a new seat assignment."); }
            if(previous!=null && references.seatReferenced(c,id) &&
                    (previous.venueId()!=venue || !previous.rowLabel().equals(label) || previous.seatNumber()!=number || previous.active()!=active)) {
                throw DomainException.conflict("Event inventory already uses this seat; its layout and active state cannot change.");
            }
            return references.saveSeat(c,id,venue,label,number,active);
        });
    }
    public void deleteCategory(long actor,long id) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);references.deleteCategory(c,id);return null;}); }
    public void deleteVenue(long actor,long id) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);references.deleteVenue(c,id);return null;}); }
    public void deleteSeat(long actor,long id) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);references.deleteSeat(c,id);return null;}); }
    public void categoryStatus(long actor,long id,boolean active) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);Category v=references.category(c,id);if(!active){references.categoryMayDisable(c,id);}references.saveCategory(c,id,v.name(),active);return null;}); }
    public void venueStatus(long actor,long id,boolean active) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);Venue v=references.venue(c,id);if(!active){references.venueMayDisable(c,id);}references.saveVenue(c,id,v.name(),v.address(),active);return null;}); }
    public void seatStatus(long actor,long id,boolean active) { database.write(c->{ActorDao.require(c,actor,Role.ADMIN);Seat v=references.seat(c,id);if(v.active()!=active && references.seatReferenced(c,id)){throw DomainException.conflict("Event inventory already uses this seat.");}references.saveSeat(c,id,v.venueId(),v.rowLabel(),v.seatNumber(),active);return null;}); }
}

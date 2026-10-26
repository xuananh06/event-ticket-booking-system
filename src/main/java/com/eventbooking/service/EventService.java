package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;
import java.time.LocalDateTime;
import java.util.List;

public final class EventService {
    private final JdbcAccess database;private final EventDao events;
    public EventService(JdbcAccess database,EventDao events){this.database=database;this.events=events;}
    public Page<Event> owned(long actor,EventFilter filter){return database.read(c->{ActorDao.require(c,actor,Role.ORGANIZER);return events.list(c,actor,filter,events.now(c));});}
    public Event owned(long actor,long id){return database.read(c->{ActorDao.require(c,actor,Role.ORGANIZER);return events.owned(c,actor,id);});}
    public List<Category> categories(long actor){return database.read(c->{ActorDao.require(c,actor,Role.ORGANIZER);return events.categories(c);});}
    public List<Venue> venues(long actor){return database.read(c->{ActorDao.require(c,actor,Role.ORGANIZER);return events.venues(c);});}
    public long save(long actor,Long id,long category,long venue,String title,String description,String starts,String ends) {
        String text=Inputs.text(title,"title",200),body=description==null?"":description.strip();
        if(body.isEmpty() || body.length()>10000 || body.codePoints().anyMatch(ch->Character.isISOControl(ch) && ch!='\n' && ch!='\r' && ch!='\t')){throw Inputs.invalid("description","Enter 1-10000 plain-text characters.");}
        LocalDateTime start=EventTimes.utc(starts,"startsAt"),end=EventTimes.utc(ends,"endsAt");
        if(!end.isAfter(start)){throw Inputs.invalid("endsAt","The end must be after the start.");}
        return database.write(c->{
            ActorDao.require(c,actor,Role.ORGANIZER);Event previous=id==null?null:events.owned(c,actor,id);
            if(previous!=null && !previous.isDraft()){throw DomainException.conflict("Only draft events can be changed.");}
            if(!start.isAfter(events.now(c))){throw Inputs.invalid("startsAt","The event must start in the future.");}
            Category parentCategory=events.category(c,category);Venue parentVenue=events.venue(c,venue);
            if(!parentCategory.active() && (previous==null || previous.categoryId()!=category)){throw DomainException.conflict("Choose an active category.");}
            if(!parentVenue.active() && (previous==null || previous.venueId()!=venue)){throw DomainException.conflict("Choose an active venue.");}
            if(previous!=null && previous.venueId()!=venue && events.dependent(c,id)){throw DomainException.conflict("Inventory or bookings already depend on this event venue.");}
            if(id==null){return events.create(c,actor,category,venue,text,body,start,end);}
            events.save(c,actor,id,category,venue,text,body,start,end);return id;
        });
    }
    public void delete(long actor,long id) {
        database.write(c->{ActorDao.require(c,actor,Role.ORGANIZER);Event previous=events.owned(c,actor,id);
            if(!previous.isDraft()){throw DomainException.conflict("Only draft events can be deleted.");}
            if(events.dependent(c,id)){throw DomainException.conflict("Inventory or bookings already depend on this event.");}
            events.delete(c,actor,id);return null;});
    }
}

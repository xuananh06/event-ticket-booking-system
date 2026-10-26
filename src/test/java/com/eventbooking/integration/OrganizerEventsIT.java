package com.eventbooking.integration;

import com.eventbooking.dao.*;
import com.eventbooking.exception.*;
import com.eventbooking.model.*;
import com.eventbooking.service.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OrganizerEventsIT {
    @Test void ownedDraftCrudUtcAndReferencePolicies() throws Exception {
        try(P3Fixture f=new P3Fixture()){
            EventService service=new EventService(f.access,new EventDao());
            long actor=f.user("ORGANIZER"),other=f.user("ORGANIZER"),category=f.category(),venue=f.venue();
            var start=f.access.read(c->new EventDao().now(c).plusDays(30).withNano(123000000));
            var end=start.plusHours(2);
            String startInput=EventTimes.input(start),endInput=EventTimes.input(end);
            long id=service.save(actor,null,category,venue,"Draft <b>Event</b>","Plain text\nSecond line",startInput,endInput);f.events.add(id);
            Event event=service.owned(actor,id);assertEquals(start,event.startsAt());assertEquals("DRAFT",event.status());
            assertEquals(404,assertThrows(DomainException.class,()->service.owned(other,id)).getStatus());
            assertEquals(404,assertThrows(DomainException.class,()->service.delete(other,id)).getStatus());
            assertEquals(403,assertThrows(DomainException.class,()->service.owned(f.admin,id)).getStatus());
            f.references.categoryStatus(f.admin,category,false);f.references.venueStatus(f.admin,venue,false);
            service.save(actor,id,category,venue,"Retained inactive references","Description",startInput,endInput);
            assertEquals(409,assertThrows(DomainException.class,()->service.save(actor,null,category,venue,"New","Description",startInput,endInput)).getStatus());
            f.references.venueStatus(f.admin,venue,true);long seat=f.seat(venue);f.inventory(id,seat,venue);
            long anotherVenue=f.venue();
            assertEquals(409,assertThrows(DomainException.class,()->service.save(actor,id,category,anotherVenue,"Moved","Description",startInput,endInput)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->service.delete(actor,id)).getStatus());
            long removable=service.save(actor,null,f.category(),anotherVenue,"Remove","Description",startInput,endInput);f.events.add(removable);
            service.delete(actor,removable);f.events.remove(removable);
            assertEquals(404,assertThrows(DomainException.class,()->service.owned(actor,removable)).getStatus());
        }
    }
    @Test void nonDraftAndExpiredWritesCannotBypassDatabasePredicates() throws Exception {
        try(P3Fixture f=new P3Fixture()){
            EventService service=new EventService(f.access,new EventDao());long actor=f.user("ORGANIZER"),category=f.category(),venue=f.venue();
            long published=f.event(actor,category,venue,f.prefix,"PUBLISHED",15);
            Event event=service.owned(actor,published);
            assertEquals(409,assertThrows(DomainException.class,()->service.save(actor,published,category,venue,"Changed","Text",event.getStartsInput(),event.getEndsInput())).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->service.delete(actor,published)).getStatus());
            var old=f.access.read(c->new EventDao().now(c).minusDays(2).withNano(0));
            assertThrows(ValidationException.class,()->service.save(actor,null,category,venue,"Past","Text",EventTimes.input(old),EventTimes.input(old.plusHours(2))));
        }
    }
}

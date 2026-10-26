package com.eventbooking.integration;

import com.eventbooking.exception.DomainException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ReferenceDataIT {
    @Test void errorsRollbackPartialWritesBeforeConnectionRestoration() throws Exception {
        try(P3Fixture f=new P3Fixture()) {
            long id=f.category();String original=f.references.category(f.admin,id).name();
            assertThrows(AssertionError.class,()->f.access.write(c->{
                com.eventbooking.dao.JdbcAccess.update(c,"UPDATE dbo.categories SET name=? WHERE id=?",f.prefix+"Rollback",id);
                throw new AssertionError("Injected transaction failure");
            }));
            assertEquals(original,f.references.category(f.admin,id).name());
        }
    }
    @Test void categoryVenueSeatCrudAndDatabaseUniqueness() throws Exception {
        try(P3Fixture f=new P3Fixture()) {
            long category=f.category(),venue=f.venue(),seat=f.seat(venue);
            assertEquals(409,assertThrows(DomainException.class,()->f.references.saveCategory(f.admin,null,f.prefix+"0",true)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.saveSeat(f.admin,null,venue,"A",1,true)).getStatus());
            // Dedicated Unicode and output-escaping fixture.
            f.references.saveVenue(f.admin,venue,"Địa điểm <b>Campus</b>","Unicode campus",false);
            assertEquals("Địa điểm <b>Campus</b>",f.references.venue(f.admin,venue).name());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.saveSeat(f.admin,null,venue,"B",2,true)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.deleteVenue(f.admin,venue)).getStatus());
            f.references.seatStatus(f.admin,seat,false);
            assertFalse(f.references.seat(f.admin,seat).active());
            assertTrue(f.references.seats(f.admin,venue,false,1,1).items().stream().anyMatch(v->v.id()==seat));
            assertTrue(f.references.seats(f.admin,venue,null,1000000,50).items().isEmpty());
            f.references.deleteSeat(f.admin,seat);
            f.references.deleteVenue(f.admin,venue);
            f.references.deleteCategory(f.admin,category);
        }
    }
    @Test void referenceGuardsAndServiceRoleChecksPreserveInventory() throws Exception {
        try(P3Fixture f=new P3Fixture()) {
            long category=f.category(),venue=f.venue(),seat=f.seat(venue),organizer=f.user("ORGANIZER"),customer=f.user("CUSTOMER");
            long event=f.event(organizer,category,venue,f.prefix,"PUBLISHED",10);f.inventory(event,seat,venue);
            assertEquals(403,assertThrows(DomainException.class,()->f.references.saveCategory(customer,null,"Denied",true)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.categoryStatus(f.admin,category,false)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.venueStatus(f.admin,venue,false)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.seatStatus(f.admin,seat,false)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.saveSeat(f.admin,seat,venue,"B",1,true)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.deleteSeat(f.admin,seat)).getStatus());
            assertEquals(409,assertThrows(DomainException.class,()->f.references.deleteCategory(f.admin,category)).getStatus());
            assertTrue(f.references.category(f.admin,category).active());
            assertTrue(f.references.venue(f.admin,venue).active());
        }
    }
}

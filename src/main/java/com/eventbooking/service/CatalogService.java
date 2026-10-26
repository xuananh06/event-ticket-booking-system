package com.eventbooking.service;

import com.eventbooking.dao.*;
import com.eventbooking.model.*;
import java.util.List;

/** Visibility is enforced in SQL using one captured database UTC time per query use case. */
public final class CatalogService {
    private final JdbcAccess database;
    private final EventDao events;
    public CatalogService(JdbcAccess database,EventDao events){this.database=database;this.events=events;}
    public Page<Event> list(EventFilter filter){return database.read(c->events.list(c,null,filter,events.now(c)));}
    public Event detail(long id){return database.read(c->events.published(c,id,events.now(c)));}
    public List<Category> categories(){return database.read(c->new ReferenceDao().categories(c,true,1,50).items());}
    public List<Venue> venues(){return database.read(c->new ReferenceDao().venues(c,true,1,50).items());}
}

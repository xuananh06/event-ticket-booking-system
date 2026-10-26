package com.eventbooking.dao;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.*;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import static com.eventbooking.dao.JdbcAccess.*;

public final class EventDao {
    private static final String FIELDS="e.id,e.organizer_id,e.category_id,e.venue_id,e.title,e.description,e.starts_at,e.ends_at,e.status,c.name AS category_name,v.name AS venue_name";
    private static final String JOIN=" FROM dbo.events e JOIN dbo.categories c ON c.id=e.category_id JOIN dbo.venues v ON v.id=e.venue_id JOIN dbo.users u ON u.id=e.organizer_id";
    public LocalDateTime now(Connection c) throws SQLException {
        try(PreparedStatement s=statement(c,"SELECT SYSUTCDATETIME()");ResultSet r=s.executeQuery()){r.next();return r.getObject(1,LocalDateTime.class);}
    }
    public Event owned(Connection c,long actor,long id) throws SQLException {
        try(PreparedStatement s=statement(c,"SELECT "+FIELDS+" FROM dbo.events e WITH (UPDLOCK,HOLDLOCK) JOIN dbo.categories c ON c.id=e.category_id JOIN dbo.venues v ON v.id=e.venue_id WHERE e.id=? AND e.organizer_id=?",id,actor);ResultSet r=s.executeQuery()){
            if(!r.next()){throw DomainException.missing();}return map(r);
        }
    }
    public Event published(Connection c,long id,LocalDateTime now) throws SQLException {
        try(PreparedStatement s=statement(c,"SELECT "+FIELDS+JOIN+" WHERE e.id=? AND "+eligible(),id,now);ResultSet r=s.executeQuery()){
            if(!r.next()){throw DomainException.missing();}return map(r);
        }
    }
    public Page<Event> list(Connection c,Long owner,EventFilter filter,LocalDateTime now) throws SQLException {
        List<Object> values=new ArrayList<>();String where=" WHERE ";
        if(owner==null){where+=eligible();values.add(now);}
        else{where+="e.organizer_id=?";values.add(owner);if(!filter.status().isEmpty()){where+=" AND e.status=?";values.add(filter.status());}}
        if(!filter.search().isEmpty()){where+=" AND e.title LIKE ? ESCAPE '~'";values.add(AdminUserDao.literal(filter.search()));}
        if(filter.category()!=null){where+=" AND e.category_id=?";values.add(filter.category());}
        if(filter.venue()!=null){where+=" AND e.venue_id=?";values.add(filter.venue());}
        if(filter.lower()!=null){where+=" AND e.starts_at>=?";values.add(filter.lower());}
        if(filter.upper()!=null){where+=" AND e.starts_at<?";values.add(filter.upper());}
        long total=scalar(c,"SELECT COUNT_BIG(*)"+JOIN+where,values.toArray());
        values.add(Page.offset(filter.page(),filter.size()));values.add(filter.size());List<Event> rows=new ArrayList<>();
        try(PreparedStatement s=statement(c,"SELECT "+FIELDS+JOIN+where+" ORDER BY e.starts_at,e.id OFFSET ? ROWS FETCH NEXT ? ROWS ONLY",values.toArray());ResultSet r=s.executeQuery()){while(r.next()){rows.add(map(r));}}
        return new Page<>(rows,total,filter.page(),filter.size());
    }
    public List<Category> categories(Connection c) throws SQLException {return new ReferenceDao().categories(c,null,1,50).items();}
    public List<Venue> venues(Connection c) throws SQLException {return new ReferenceDao().venues(c,null,1,50).items();}
    public Category category(Connection c,long id) throws SQLException {return new ReferenceDao().category(c,id);}
    public Venue venue(Connection c,long id) throws SQLException {return new ReferenceDao().venue(c,id);}
    public long create(Connection c,long actor,long category,long venue,String title,String description,LocalDateTime start,LocalDateTime end) throws SQLException {
        return scalar(c,"INSERT dbo.events(organizer_id,category_id,venue_id,title,description,starts_at,ends_at,status) OUTPUT INSERTED.id VALUES (?,?,?,?,?,?,?,'DRAFT')",actor,category,venue,title,description,start,end);
    }
    public void save(Connection c,long actor,long id,long category,long venue,String title,String description,LocalDateTime start,LocalDateTime end) throws SQLException {
        update(c,"UPDATE dbo.events SET category_id=?,venue_id=?,title=?,description=?,starts_at=?,ends_at=? WHERE id=? AND organizer_id=? AND status='DRAFT'",category,venue,title,description,start,end,id,actor);
    }
    public boolean dependent(Connection c,long id) throws SQLException {
        return scalar(c,"SELECT (SELECT COUNT_BIG(*) FROM dbo.event_seats WHERE event_id=?)+(SELECT COUNT_BIG(*) FROM dbo.bookings WHERE event_id=?)",id,id)>0;
    }
    public void delete(Connection c,long actor,long id) throws SQLException {update(c,"DELETE dbo.events WHERE id=? AND organizer_id=? AND status='DRAFT'",id,actor);}
    private static String eligible(){return "e.status='PUBLISHED' AND e.starts_at>? AND c.is_active=1 AND v.is_active=1 AND u.is_active=1 AND u.role='ORGANIZER'";}
    private Event map(ResultSet r) throws SQLException {return new Event(r.getLong("id"),r.getLong("organizer_id"),r.getLong("category_id"),r.getLong("venue_id"),r.getNString("title"),r.getNString("description"),r.getObject("starts_at",LocalDateTime.class),r.getObject("ends_at",LocalDateTime.class),r.getString("status"),r.getNString("category_name"),r.getNString("venue_name"));}
}

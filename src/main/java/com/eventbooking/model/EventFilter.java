package com.eventbooking.model;

import com.eventbooking.service.*;

import java.time.*;

public record EventFilter(String search, Long category, Long venue, LocalDate from, LocalDate to, String status,
                          int page, int size) {
    public static EventFilter parse(String search, String category, String venue, String from, String to, String status, String page, String size) {
        String q = search == null ? "" : search.strip();
        if (q.length() > 100 || q.codePoints().anyMatch(Character::isISOControl)) {
            throw Inputs.invalid("q", "Search must be at most 100 characters without controls.");
        }
        LocalDate lower = EventTimes.date(from, "from"), upper = EventTimes.date(to, "to");
        if (lower != null && upper != null && lower.isAfter(upper)) {
            throw Inputs.invalid("to", "The end date must not be before the start date.");
        }
        String state = status == null ? "" : status;
        if (!java.util.Set.of("", "DRAFT", "PUBLISHED", "CLOSED", "CANCELLED").contains(state)) {
            throw Inputs.invalid("status", "Choose a valid event status.");
        }
        return new EventFilter(q, Inputs.optionalId(category, "category"), Inputs.optionalId(venue, "venue"), lower, upper, state, Page.number(page), Page.size(size));
    }

    public LocalDateTime lower() {
        return EventTimes.boundary(from);
    }

    public LocalDateTime upper() {
        return EventTimes.boundary(to == null ? null : to.plusDays(1));
    }
}

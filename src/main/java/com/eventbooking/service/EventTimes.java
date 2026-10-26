package com.eventbooking.service;

import java.time.*;
import java.time.format.*;
import java.util.Locale;

public final class EventTimes {
    public static final ZoneId DISPLAY_ZONE = ZoneId.of("Asia/Bangkok");
    private static final DateTimeFormatter LABEL = DateTimeFormatter.ofPattern("dd MMM uuuu, HH:mm:ss", Locale.ENGLISH);

    private EventTimes() {
    }

    public static LocalDateTime utc(String value, String field) {
        try {
            LocalDateTime local = LocalDateTime.parse(value, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            LocalDateTime utc = local.atZone(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            if (local.getYear() < 1 || local.getYear() > 9999 || utc.getYear() < 1 || utc.getYear() > 9999 || utc.getNano() % 1000000 != 0) {
                throw new DateTimeException("Unsupported precision or year.");
            }
            return utc;
        } catch (DateTimeException | NullPointerException invalid) {
            throw Inputs.invalid(field, "Enter a valid local date/time with at most millisecond precision (Asia/Bangkok).");
        }
    }

    public static String input(LocalDateTime utc) {
        return DateTimeFormatter.ISO_LOCAL_DATE_TIME.format(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE));
    }

    public static String label(LocalDateTime utc) {
        return LABEL.format(utc.atOffset(ZoneOffset.UTC).atZoneSameInstant(DISPLAY_ZONE));
    }

    public static LocalDate date(String value, String field) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 2 || date.getYear() > 9998) {
                throw new DateTimeException("Unsupported year.");
            }
            return date;
        } catch (DateTimeException invalid) {
            throw Inputs.invalid(field, "Enter a valid date.");
        }
    }

    public static LocalDateTime boundary(LocalDate date) {
        return date == null ? null : date.atStartOfDay(DISPLAY_ZONE).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }
}

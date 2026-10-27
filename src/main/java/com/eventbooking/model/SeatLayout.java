package com.eventbooking.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups only a bounded page; sparse seat numbers never allocate empty grid positions.
 */
public final class SeatLayout {
    private SeatLayout() {
    }

    public record Row(String label, List<InventorySeat> seats) {
        public Row {
            seats = List.copyOf(seats);
        }

        public String getLabel() {
            return label;
        }

        public List<InventorySeat> getSeats() {
            return seats;
        }
    }

    public static List<Row> rows(List<InventorySeat> seats) {
        Map<String, List<InventorySeat>> rows = new LinkedHashMap<>();
        seats.stream().sorted(Comparator.comparing(InventorySeat::rowLabel)
                        .thenComparingInt(InventorySeat::seatNumber).thenComparingLong(InventorySeat::id))
                .forEach(seat -> rows.computeIfAbsent(seat.rowLabel(), ignored -> new ArrayList<>()).add(seat));
        return rows.entrySet().stream().map(row -> new Row(row.getKey(), row.getValue())).toList();
    }
}

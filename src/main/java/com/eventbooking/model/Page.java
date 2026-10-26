package com.eventbooking.model;

import com.eventbooking.service.Inputs;
import java.util.List;

public record Page<T>(List<T> items, long total, int number, int size) {
    public Page { items = List.copyOf(items); }
    public List<T> getItems() { return items; }
    public long getTotal() { return total; }
    public int getNumber() { return number; }
    public int getSize() { return size; }
    public long getPages() { return (total + size - 1) / size; }
    public boolean isPrevious() { return number > 1; }
    public boolean isNext() { return (long) number * size < total; }
    public static int number(String value) { return Inputs.positive(value, "page", 1, 1000000); }
    public static int size(String value) { return Inputs.positive(value, "size", 10, 50); }
    public static long offset(int number, int size) { return ((long) number - 1) * size; }
}

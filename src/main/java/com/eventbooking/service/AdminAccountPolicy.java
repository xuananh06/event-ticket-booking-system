package com.eventbooking.service;

import com.eventbooking.exception.DomainException;
import com.eventbooking.model.Role;

public final class AdminAccountPolicy {
    private AdminAccountPolicy() {
    }

    public static void change(long actor, long target, Role previous, boolean wasActive, Role next, boolean active, boolean deleting, long activeAdmins, long events, long bookings) {
        boolean removesAdmin = deleting || next != Role.ADMIN || !active;
        if (actor == target && removesAdmin) {
            throw DomainException.conflict("You cannot disable, delete or demote your own administrator account.");
        }
        if (previous == Role.ADMIN && wasActive && removesAdmin && activeAdmins <= 1) {
            throw DomainException.conflict("At least one active administrator must remain.");
        }
        if (!deleting && events > 0 && next != Role.ORGANIZER) {
            throw DomainException.conflict("An account owning events must remain an organizer.");
        }
        if (!deleting && bookings > 0 && next != Role.CUSTOMER) {
            throw DomainException.conflict("An account owning bookings must remain a customer.");
        }
    }
}

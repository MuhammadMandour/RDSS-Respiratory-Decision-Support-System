package com.rdss.appointments;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Pure slot generation: expands weekly availability windows into concrete slots for a date
 * range (interpreted in the clinic time zone) and removes slots that are in the past or
 * overlap an existing blocking appointment.
 */
public final class SlotCalculator {

    public record Slot(Instant startsAt, Instant endsAt) {
    }

    private SlotCalculator() {
    }

    public static List<Slot> freeSlots(Collection<ClinicianAvailability> windows, LocalDate fromDate,
                                       LocalDate toDateInclusive, ZoneId zone, Collection<Appointment> blocking,
                                       Instant now) {
        List<Slot> result = new ArrayList<>();
        for (LocalDate date = fromDate; !date.isAfter(toDateInclusive); date = date.plusDays(1)) {
            for (ClinicianAvailability w : windows) {
                if (!w.appliesTo(date)) {
                    continue;
                }
                LocalDateTime cursor = date.atTime(w.getStartTime());
                LocalDateTime windowEnd = date.atTime(w.getEndTime());
                while (!cursor.plusMinutes(w.getSlotMinutes()).isAfter(windowEnd)) {
                    Instant start = cursor.atZone(zone).toInstant();
                    Instant end = cursor.plusMinutes(w.getSlotMinutes()).atZone(zone).toInstant();
                    if (start.isAfter(now) && blocking.stream().noneMatch(a -> overlaps(a, start, end))) {
                        result.add(new Slot(start, end));
                    }
                    cursor = cursor.plusMinutes(w.getSlotMinutes());
                }
            }
        }
        result.sort((a, b) -> a.startsAt().compareTo(b.startsAt()));
        return result;
    }

    static boolean overlaps(Appointment a, Instant start, Instant end) {
        return a.getStartsAt().isBefore(end) && a.getEndsAt().isAfter(start);
    }
}

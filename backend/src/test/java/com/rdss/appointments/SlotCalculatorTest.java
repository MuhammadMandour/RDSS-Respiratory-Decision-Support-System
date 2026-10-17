package com.rdss.appointments;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;

import org.junit.jupiter.api.Test;

class SlotCalculatorTest {

    static final ZoneId ZONE = ZoneId.of("Europe/London");
    // Monday 2030-06-03.
    static final LocalDate MONDAY = LocalDate.of(2030, 6, 3);
    static final Instant LONG_AGO = Instant.parse("2020-01-01T00:00:00Z");

    private static ClinicianAvailability window(DayOfWeek day, int startH, int endH, int minutes) {
        return new ClinicianAvailability(1L, day, LocalTime.of(startH, 0), LocalTime.of(endH, 0), minutes,
                LocalDate.of(2020, 1, 1), null);
    }

    @Test
    void expandsWindowIntoSlotsInClinicTimeZone() {
        var slots = SlotCalculator.freeSlots(List.of(window(DayOfWeek.MONDAY, 9, 11, 30)), MONDAY, MONDAY, ZONE,
                List.of(), LONG_AGO);
        assertThat(slots).hasSize(4);
        // 09:00 BST == 08:00 UTC
        assertThat(slots.get(0).startsAt()).isEqualTo(Instant.parse("2030-06-03T08:00:00Z"));
        assertThat(slots.get(3).endsAt()).isEqualTo(Instant.parse("2030-06-03T10:00:00Z"));
    }

    @Test
    void partialTrailingSlotIsNotOffered() {
        var slots = SlotCalculator.freeSlots(List.of(window(DayOfWeek.MONDAY, 9, 10, 45)), MONDAY, MONDAY, ZONE,
                List.of(), LONG_AGO);
        assertThat(slots).hasSize(1);
    }

    @Test
    void removesSlotsOverlappingBookedAppointments() {
        Instant start = Instant.parse("2030-06-03T08:15:00Z");
        var booked = new Appointment(1L, 1L, start, start.plusSeconds(1800), Appointment.Status.SCHEDULED,
                Appointment.Type.CONSULTATION, null, 1L, null);
        var slots = SlotCalculator.freeSlots(List.of(window(DayOfWeek.MONDAY, 9, 11, 30)), MONDAY, MONDAY, ZONE,
                List.of(booked), LONG_AGO);
        // 09:00 and 09:30 local overlap the 09:15-09:45 booking.
        assertThat(slots).extracting(SlotCalculator.Slot::startsAt)
                .containsExactly(Instant.parse("2030-06-03T09:00:00Z"), Instant.parse("2030-06-03T09:30:00Z"));
    }

    @Test
    void skipsPastSlotsOtherDaysAndExpiredWindows() {
        var expired = new ClinicianAvailability(1L, DayOfWeek.MONDAY, LocalTime.of(14, 0), LocalTime.of(15, 0), 30,
                LocalDate.of(2020, 1, 1), LocalDate.of(2025, 1, 1));
        Instant now = Instant.parse("2030-06-03T08:40:00Z");
        var slots = SlotCalculator.freeSlots(
                List.of(window(DayOfWeek.MONDAY, 9, 11, 30), window(DayOfWeek.TUESDAY, 9, 10, 30), expired),
                MONDAY, MONDAY, ZONE, List.of(), now);
        assertThat(slots).extracting(SlotCalculator.Slot::startsAt)
                .containsExactly(Instant.parse("2030-06-03T09:00:00Z"), Instant.parse("2030-06-03T09:30:00Z"));
    }
}

package com.rdss.appointments;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A recurring weekly working window for a clinician, split into fixed-length slots. */
@Entity
@Table(name = "clinician_availability")
public class ClinicianAvailability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "clinician_id", nullable = false)
    private Long clinicianId;

    /** ISO day of week, 1 = Monday. */
    @Column(name = "day_of_week", nullable = false)
    private short dayOfWeek;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;

    @Column(name = "slot_minutes", nullable = false)
    private short slotMinutes;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ClinicianAvailability() {
    }

    public ClinicianAvailability(Long clinicianId, DayOfWeek day, LocalTime startTime, LocalTime endTime,
                                 int slotMinutes, LocalDate validFrom, LocalDate validTo) {
        this.clinicianId = clinicianId;
        this.dayOfWeek = (short) day.getValue();
        this.startTime = startTime;
        this.endTime = endTime;
        this.slotMinutes = (short) slotMinutes;
        this.validFrom = validFrom;
        this.validTo = validTo;
        this.createdAt = Instant.now();
    }

    public boolean appliesTo(LocalDate date) {
        return active && date.getDayOfWeek().getValue() == dayOfWeek
                && !date.isBefore(validFrom) && (validTo == null || !date.isAfter(validTo));
    }

    public void deactivate() {
        active = false;
    }

    public Long getId() { return id; }
    public Long getClinicianId() { return clinicianId; }
    public DayOfWeek getDayOfWeek() { return DayOfWeek.of(dayOfWeek); }
    public LocalTime getStartTime() { return startTime; }
    public LocalTime getEndTime() { return endTime; }
    public int getSlotMinutes() { return slotMinutes; }
    public LocalDate getValidFrom() { return validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public boolean isActive() { return active; }
}

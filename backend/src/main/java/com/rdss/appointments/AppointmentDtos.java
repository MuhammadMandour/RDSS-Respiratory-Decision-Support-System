package com.rdss.appointments;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class AppointmentDtos {

    private AppointmentDtos() {
    }

    public record AvailabilityWindowRequest(@NotNull DayOfWeek dayOfWeek, @NotNull LocalTime startTime,
                                            @NotNull LocalTime endTime, @Min(5) @Max(240) int slotMinutes,
                                            LocalDate validFrom, LocalDate validTo) {
        @AssertTrue(message = "start time must be before end time")
        public boolean isTimeRangeValid() {
            return startTime == null || endTime == null || startTime.isBefore(endTime);
        }
    }

    public record AvailabilityWindowResponse(Long id, DayOfWeek dayOfWeek, LocalTime startTime, LocalTime endTime,
                                             int slotMinutes, LocalDate validFrom, LocalDate validTo) {
        static AvailabilityWindowResponse from(ClinicianAvailability a) {
            return new AvailabilityWindowResponse(a.getId(), a.getDayOfWeek(), a.getStartTime(), a.getEndTime(),
                    a.getSlotMinutes(), a.getValidFrom(), a.getValidTo());
        }
    }

    public record SlotResponse(Instant startsAt, Instant endsAt) {
    }

    public record BookAppointmentRequest(
            /** Ignored for patient users (always their own record). */
            Long patientId,
            @NotNull Long clinicianId,
            @NotNull Instant startsAt,
            /** Staff only; patients get the slot length from the clinician's availability. */
            @Min(5) @Max(480) Integer durationMinutes,
            @NotNull Appointment.Type type,
            @Size(max = 500) String reason) {
    }

    public record RescheduleRequest(@NotNull Instant startsAt, @Min(5) @Max(480) Integer durationMinutes) {
    }

    public record CancelRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record StatusChangeRequest(@NotNull Appointment.Status status) {
    }

    public record AppointmentResponse(Long id, Long patientId, String patientName, String patientMrn,
                                      Long clinicianId, String clinicianName, Instant startsAt, Instant endsAt,
                                      Appointment.Status status, Appointment.Type type, String reason,
                                      Long encounterId, Long rescheduledFromId, Instant cancelledAt,
                                      String cancellationReason, Instant createdAt) {
    }
}

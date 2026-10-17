package com.rdss.appointments;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import com.rdss.appointments.AppointmentDtos.AppointmentResponse;
import com.rdss.appointments.AppointmentDtos.AvailabilityWindowRequest;
import com.rdss.appointments.AppointmentDtos.AvailabilityWindowResponse;
import com.rdss.appointments.AppointmentDtos.BookAppointmentRequest;
import com.rdss.appointments.AppointmentDtos.CancelRequest;
import com.rdss.appointments.AppointmentDtos.RescheduleRequest;
import com.rdss.appointments.AppointmentDtos.SlotResponse;
import com.rdss.appointments.AppointmentDtos.StatusChangeRequest;
import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.encounters.EncounterDtos.EncounterResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Appointments")
public class AppointmentController {

    private final AppointmentService service;

    public AppointmentController(AppointmentService service) {
        this.service = service;
    }

    @GetMapping("/clinicians/{clinicianId}/availability")
    public List<AvailabilityWindowResponse> availability(@PathVariable Long clinicianId) {
        return service.getAvailability(clinicianId);
    }

    @PutMapping("/clinicians/{clinicianId}/availability")
    @Operation(summary = "Replace the clinician's weekly availability (admin or the clinician)")
    public List<AvailabilityWindowResponse> replaceAvailability(
            @PathVariable Long clinicianId, @Valid @RequestBody List<@Valid AvailabilityWindowRequest> windows) {
        return service.replaceAvailability(clinicianId, windows);
    }

    @GetMapping("/clinicians/{clinicianId}/slots")
    @Operation(summary = "Free bookable slots between two dates (inclusive, clinic time zone)")
    public List<SlotResponse> slots(@PathVariable Long clinicianId,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                    @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.freeSlots(clinicianId, from, to);
    }

    @GetMapping("/appointments")
    public PageResponse<AppointmentResponse> search(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Long clinicianId,
            @RequestParam(required = false) Appointment.Status status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(defaultValue = "asc") String sort) {
        Sort.Direction dir = "desc".equalsIgnoreCase(sort) ? Sort.Direction.DESC : Sort.Direction.ASC;
        return service.search(patientId, clinicianId, status, from, to, Pages.of(page, size, Sort.by(dir, "startsAt")));
    }

    @GetMapping("/appointments/{id}")
    public AppointmentResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping("/appointments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Book an appointment (patients: own record, offered slots only)")
    public AppointmentResponse book(@Valid @RequestBody BookAppointmentRequest request) {
        return service.book(request);
    }

    @PostMapping("/appointments/{id}/reschedule")
    @ResponseStatus(HttpStatus.CREATED)
    public AppointmentResponse reschedule(@PathVariable Long id, @Valid @RequestBody RescheduleRequest request) {
        return service.reschedule(id, request.startsAt(), request.durationMinutes());
    }

    @PostMapping("/appointments/{id}/cancel")
    public AppointmentResponse cancel(@PathVariable Long id, @Valid @RequestBody CancelRequest request) {
        return service.cancel(id, request.reason());
    }

    @PostMapping("/appointments/{id}/status")
    public AppointmentResponse changeStatus(@PathVariable Long id, @Valid @RequestBody StatusChangeRequest request) {
        return service.changeStatus(id, request.status());
    }

    @PostMapping("/appointments/{id}/encounter")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse startEncounter(@PathVariable Long id) {
        return service.startEncounter(id);
    }
}

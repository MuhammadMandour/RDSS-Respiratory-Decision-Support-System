package com.rdss.patients;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.patients.PatientDtos.AssignClinicianRequest;
import com.rdss.patients.PatientDtos.CareTeamMemberResponse;
import com.rdss.patients.PatientDtos.PatientContactUpdateRequest;
import com.rdss.patients.PatientDtos.PatientRequest;
import com.rdss.patients.PatientDtos.PatientResponse;
import com.rdss.patients.PatientDtos.PatientSummary;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/patients")
@Tag(name = "Patients")
public class PatientController {

    private final PatientService service;

    public PatientController(PatientService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "Search the patient directory (admins: all, clinicians: own care team)")
    public PageResponse<PatientSummary> search(@RequestParam(required = false) String q,
                                               @RequestParam(defaultValue = "false") boolean includeInactive,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return service.search(q, includeInactive, Pages.of(page, size, Sort.by("lastName", "firstName")));
    }

    @GetMapping("/me")
    @Operation(summary = "The current patient user's own record")
    public PatientResponse me() {
        return service.get(service.requireOwnPatientId());
    }

    @GetMapping("/{id}")
    public PatientResponse get(@PathVariable Long id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PatientResponse create(@Valid @RequestBody PatientRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public PatientResponse update(@PathVariable Long id, @Valid @RequestBody PatientRequest request) {
        return service.update(id, request);
    }

    @PatchMapping("/{id}/contact")
    @Operation(summary = "Update contact details (patients may update their own)")
    public PatientResponse updateContact(@PathVariable Long id, @Valid @RequestBody PatientContactUpdateRequest request) {
        return service.updateContact(id, request);
    }

    @PostMapping("/{id}/deactivate")
    public PatientResponse deactivate(@PathVariable Long id) {
        return service.setActive(id, false);
    }

    @PostMapping("/{id}/activate")
    public PatientResponse activate(@PathVariable Long id) {
        return service.setActive(id, true);
    }

    @GetMapping("/{id}/care-team")
    public List<CareTeamMemberResponse> careTeam(@PathVariable Long id) {
        return service.careTeam(id);
    }

    @PostMapping("/{id}/care-team")
    public List<CareTeamMemberResponse> assign(@PathVariable Long id, @Valid @RequestBody AssignClinicianRequest request) {
        return service.assignClinician(id, request.clinicianId());
    }

    @DeleteMapping("/{id}/care-team/{clinicianId}")
    public List<CareTeamMemberResponse> unassign(@PathVariable Long id, @PathVariable Long clinicianId) {
        return service.removeClinician(id, clinicianId);
    }
}

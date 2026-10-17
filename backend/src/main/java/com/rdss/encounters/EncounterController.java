package com.rdss.encounters;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.encounters.EncounterDtos.AmendEncounterRequest;
import com.rdss.encounters.EncounterDtos.CreateEncounterRequest;
import com.rdss.encounters.EncounterDtos.EncounterResponse;
import com.rdss.encounters.EncounterDtos.EncounterSummary;
import com.rdss.encounters.EncounterDtos.UpdateEncounterRequest;
import com.rdss.encounters.TreatmentDtos.DiscontinueRequest;
import com.rdss.encounters.TreatmentDtos.PrescriptionRequest;
import com.rdss.encounters.TreatmentDtos.PrescriptionResponse;
import com.rdss.encounters.TreatmentDtos.TreatmentPlanRequest;
import com.rdss.encounters.TreatmentDtos.TreatmentPlanResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
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
@Tag(name = "Encounters & treatment")
public class EncounterController {

    private final EncounterService encounters;
    private final TreatmentService treatment;

    public EncounterController(EncounterService encounters, TreatmentService treatment) {
        this.encounters = encounters;
        this.treatment = treatment;
    }

    @GetMapping("/patients/{patientId}/encounters")
    public PageResponse<EncounterSummary> list(@PathVariable Long patientId,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        return encounters.listForPatient(patientId, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")));
    }

    @PostMapping("/patients/{patientId}/encounters")
    @ResponseStatus(HttpStatus.CREATED)
    public EncounterResponse create(@PathVariable Long patientId, @Valid @RequestBody CreateEncounterRequest request) {
        return encounters.create(patientId, request);
    }

    @GetMapping("/encounters/mine")
    @Operation(summary = "The current clinician's encounters by status (default: open drafts)")
    public PageResponse<EncounterSummary> mine(
            @RequestParam(defaultValue = "IN_PROGRESS") ClinicalEncounter.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return encounters.listMine(status, Pages.of(page, size, Sort.by(Sort.Direction.DESC, "startedAt")));
    }

    @GetMapping("/encounters/{id}")
    public EncounterResponse get(@PathVariable Long id) {
        return encounters.get(id);
    }

    @PutMapping("/encounters/{id}")
    public EncounterResponse update(@PathVariable Long id, @Valid @RequestBody UpdateEncounterRequest request) {
        return encounters.update(id, request);
    }

    @PostMapping("/encounters/{id}/finalize")
    public EncounterResponse finalizeEncounter(@PathVariable Long id) {
        return encounters.finalizeEncounter(id);
    }

    @PostMapping("/encounters/{id}/amend")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Start an amendment of a finalized encounter (original is superseded when it is finalized)")
    public EncounterResponse amend(@PathVariable Long id, @Valid @RequestBody AmendEncounterRequest request) {
        return encounters.amend(id, request.reason());
    }

    @GetMapping("/patients/{patientId}/treatment-plans")
    public List<TreatmentPlanResponse> plans(@PathVariable Long patientId) {
        return treatment.listPlans(patientId);
    }

    @PostMapping("/patients/{patientId}/treatment-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public TreatmentPlanResponse createPlan(@PathVariable Long patientId,
                                            @Valid @RequestBody TreatmentPlanRequest request) {
        return treatment.createPlan(patientId, request);
    }

    @PutMapping("/treatment-plans/{id}")
    public TreatmentPlanResponse updatePlan(@PathVariable Long id, @Valid @RequestBody TreatmentPlanRequest request) {
        return treatment.updatePlan(id, request);
    }

    @GetMapping("/patients/{patientId}/prescriptions")
    public List<PrescriptionResponse> prescriptions(@PathVariable Long patientId) {
        return treatment.listPrescriptions(patientId);
    }

    @PostMapping("/patients/{patientId}/prescriptions")
    @ResponseStatus(HttpStatus.CREATED)
    public PrescriptionResponse prescribe(@PathVariable Long patientId, @Valid @RequestBody PrescriptionRequest request) {
        return treatment.prescribe(patientId, request);
    }

    @PostMapping("/prescriptions/{id}/discontinue")
    public PrescriptionResponse discontinue(@PathVariable Long id, @Valid @RequestBody DiscontinueRequest request) {
        return treatment.discontinue(id, request.reason());
    }

    @PostMapping("/prescriptions/{id}/complete")
    public PrescriptionResponse complete(@PathVariable Long id) {
        return treatment.complete(id);
    }
}

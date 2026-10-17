package com.rdss.respiratory;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.common.PageResponse;
import com.rdss.common.Pages;
import com.rdss.respiratory.RespiratoryDtos.EnteredInErrorRequest;
import com.rdss.respiratory.RespiratoryDtos.ObservationRequest;
import com.rdss.respiratory.RespiratoryDtos.ObservationResponse;
import com.rdss.respiratory.RespiratoryDtos.PulmonaryFunctionTestRequest;
import com.rdss.respiratory.RespiratoryDtos.PulmonaryFunctionTestResponse;
import com.rdss.respiratory.RespiratoryDtos.RespiratoryHistoryRequest;
import com.rdss.respiratory.RespiratoryDtos.RespiratoryHistoryResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Respiratory record")
public class RespiratoryController {

    private final RespiratoryService service;

    public RespiratoryController(RespiratoryService service) {
        this.service = service;
    }

    @GetMapping("/patients/{patientId}/respiratory-history")
    @Operation(summary = "Current respiratory history version (204 if none recorded)")
    public ResponseEntity<RespiratoryHistoryResponse> currentHistory(@PathVariable Long patientId) {
        return service.currentHistory(patientId).map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @GetMapping("/patients/{patientId}/respiratory-history/versions")
    public List<RespiratoryHistoryResponse> historyVersions(@PathVariable Long patientId) {
        return service.historyVersions(patientId);
    }

    @PostMapping("/patients/{patientId}/respiratory-history")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a new respiratory history version")
    public RespiratoryHistoryResponse recordHistory(@PathVariable Long patientId,
                                                    @Valid @RequestBody RespiratoryHistoryRequest request) {
        return service.recordHistory(patientId, request);
    }

    @GetMapping("/patients/{patientId}/observations")
    public PageResponse<ObservationResponse> observations(@PathVariable Long patientId,
                                                          @RequestParam(required = false) Long encounterId,
                                                          @RequestParam(defaultValue = "0") int page,
                                                          @RequestParam(defaultValue = "50") int size) {
        return service.listObservations(patientId, encounterId,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "observedAt")));
    }

    @PostMapping("/patients/{patientId}/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public ObservationResponse recordObservation(@PathVariable Long patientId,
                                                 @Valid @RequestBody ObservationRequest request) {
        return service.recordObservation(patientId, request);
    }

    @PostMapping("/observations/{id}/entered-in-error")
    public ObservationResponse observationInError(@PathVariable Long id,
                                                  @Valid @RequestBody EnteredInErrorRequest request) {
        return service.markObservationInError(id, request.reason());
    }

    @GetMapping("/patients/{patientId}/pulmonary-function-tests")
    public List<PulmonaryFunctionTestResponse> pfts(@PathVariable Long patientId) {
        return service.listPfts(patientId);
    }

    @PostMapping("/patients/{patientId}/pulmonary-function-tests")
    @ResponseStatus(HttpStatus.CREATED)
    public PulmonaryFunctionTestResponse recordPft(@PathVariable Long patientId,
                                                   @Valid @RequestBody PulmonaryFunctionTestRequest request) {
        return service.recordPft(patientId, request);
    }

    @PostMapping("/pulmonary-function-tests/{id}/entered-in-error")
    public PulmonaryFunctionTestResponse pftInError(@PathVariable Long id,
                                                    @Valid @RequestBody EnteredInErrorRequest request) {
        return service.markPftInError(id, request.reason());
    }
}

package com.rdss.cdss;

import java.util.List;

import jakarta.validation.Valid;

import com.rdss.cdss.CdssDtos.AssessmentResponse;
import com.rdss.cdss.CdssDtos.AssessmentSummary;
import com.rdss.cdss.CdssDtos.CreateAssessmentRequest;
import com.rdss.cdss.CdssDtos.GuidelineResponse;
import com.rdss.cdss.CdssDtos.ReviewRequest;
import com.rdss.cdss.CdssDtos.RuleResponse;
import com.rdss.cdss.CdssDtos.UpdateRuleRequest;
import com.rdss.common.PageResponse;
import com.rdss.common.Pages;

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
@Tag(name = "Clinical decision support")
public class CdssController {

    private final AssessmentService assessments;
    private final RuleRegistryService registry;

    public CdssController(AssessmentService assessments, RuleRegistryService registry) {
        this.assessments = assessments;
        this.registry = registry;
    }

    @PostMapping("/patients/{patientId}/cdss/assessments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Run the respiratory CDSS rules against the patient's current record")
    public AssessmentResponse assess(@PathVariable Long patientId,
                                     @Valid @RequestBody(required = false) CreateAssessmentRequest request) {
        return assessments.assess(patientId, request);
    }

    @GetMapping("/patients/{patientId}/cdss/assessments")
    public PageResponse<AssessmentSummary> history(@PathVariable Long patientId,
                                                   @RequestParam(required = false) Long encounterId,
                                                   @RequestParam(defaultValue = "0") int page,
                                                   @RequestParam(defaultValue = "20") int size) {
        return assessments.history(patientId, encounterId,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "assessedAt")));
    }

    @GetMapping("/cdss/assessments/{id}")
    public AssessmentResponse get(@PathVariable Long id) {
        return assessments.get(id);
    }

    @PostMapping("/cdss/assessments/{id}/review")
    @Operation(summary = "Record the clinician's review: accept or dismiss (with reason) every alert")
    public AssessmentResponse review(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return assessments.review(id, request);
    }

    @GetMapping("/cdss/rules")
    public List<RuleResponse> rules() {
        return registry.list();
    }

    @PutMapping("/cdss/rules/{code}")
    @Operation(summary = "Enable/disable a rule or tune its declared parameters (administrators)")
    public RuleResponse updateRule(@PathVariable String code, @Valid @RequestBody UpdateRuleRequest request) {
        return registry.update(code, request.enabled(), request.parameters());
    }

    @GetMapping("/cdss/guidelines")
    public List<GuidelineResponse> guidelines() {
        return registry.guidelines();
    }
}

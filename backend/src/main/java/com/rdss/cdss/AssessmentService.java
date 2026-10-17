package com.rdss.cdss;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.cdss.CdssDtos.AlertDispositionRequest;
import com.rdss.cdss.CdssDtos.AlertResponse;
import com.rdss.cdss.CdssDtos.AssessmentResponse;
import com.rdss.cdss.CdssDtos.AssessmentSummary;
import com.rdss.cdss.CdssDtos.CreateAssessmentRequest;
import com.rdss.cdss.CdssDtos.GuidelineResponse;
import com.rdss.cdss.CdssDtos.News2Summary;
import com.rdss.cdss.CdssDtos.ReviewInfo;
import com.rdss.cdss.CdssDtos.ReviewRequest;
import com.rdss.cdss.engine.CdssEngine;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.inference.InferenceProvider;
import com.rdss.cdss.model.CdssAlert;
import com.rdss.cdss.model.CdssAlertRepository;
import com.rdss.cdss.model.ClinicalAssessment;
import com.rdss.cdss.model.ClinicalAssessmentRepository;
import com.rdss.cdss.model.GuidelineReferenceRepository;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs, stores and reviews CDSS assessments. Available to clinicians on the patient's care team.
 * Every assessment stores its full input snapshot and the rule versions used.
 */
@Service
@PreAuthorize("hasRole('CLINICIAN')")
public class AssessmentService {

    private static final Logger log = LoggerFactory.getLogger(AssessmentService.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ClinicalAssessmentRepository assessments;
    private final CdssAlertRepository alerts;
    private final GuidelineReferenceRepository guidelines;
    private final AssessmentInputCollector collector;
    private final CdssEngine engine;
    private final RuleRegistryService registry;
    private final List<InferenceProvider> inferenceProviders;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public AssessmentService(ClinicalAssessmentRepository assessments, CdssAlertRepository alerts,
                             GuidelineReferenceRepository guidelines, AssessmentInputCollector collector,
                             CdssEngine engine, RuleRegistryService registry,
                             ObjectProvider<InferenceProvider> inferenceProviders, AppUserRepository users,
                             PatientAccessPolicy access, AuditService audit, ObjectMapper objectMapper) {
        this.assessments = assessments;
        this.alerts = alerts;
        this.guidelines = guidelines;
        this.collector = collector;
        this.engine = engine;
        this.registry = registry;
        this.inferenceProviders = inferenceProviders.orderedStream().toList();
        this.users = users;
        this.access = access;
        this.audit = audit;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public AssessmentResponse assess(Long patientId, CreateAssessmentRequest req) {
        Patient patient = access.require(patientId, Access.CLINICAL_WRITE);
        AuthenticatedUser user = CurrentUser.require();
        Instant now = Instant.now();
        RuleContext ctx = collector.collect(patient, req == null ? null : req.encounterId(),
                req == null ? null : req.asthmaControl(), now);
        CdssEngine.Result result = engine.run(ctx, registry.currentConfig());

        Map<String, Object> inference = null;
        if (!inferenceProviders.isEmpty()) {
            InferenceProvider provider = inferenceProviders.get(0);
            try {
                inference = provider.infer(ctx);
            } catch (RuntimeException ex) {
                log.warn("Inference provider {} failed", provider.name(), ex);
                inference = Map.of("provider", provider.name(), "label", "Inference unavailable", "error", true);
            }
        }

        List<Map<String, Object>> applied = result.appliedRules().stream()
                .map(r -> Map.<String, Object>of("code", r.code(), "version", r.version(), "outcome", r.outcome()))
                .toList();
        var news2 = result.news2();
        ClinicalAssessment a = assessments.save(new ClinicalAssessment(patientId, ctx.encounter() == null ? null
                : ctx.encounter().id(), user.id(), now, CdssEngine.VERSION, objectMapper.convertValue(ctx, MAP), applied,
                result.missingData(), news2 == null ? null : news2.total(), news2 == null ? null : news2.risk().name(),
                news2 == null ? null : (short) news2.scale(), result.highestSeverity(), inference));
        for (Finding f : result.findings()) {
            alerts.save(new CdssAlert(a.getId(), result.ruleVersions().getOrDefault(f.ruleCode(), "unknown"), f,
                    objectMapper.convertValue(f.evidence(), MAP)));
        }
        audit.record("ASSESS", "ClinicalAssessment", a.getId(), patientId, Outcome.SUCCESS,
                "findings=" + result.findings().size() + " highest=" + result.highestSeverity());
        return toResponse(a);
    }

    @Transactional(readOnly = true)
    public PageResponse<AssessmentSummary> history(Long patientId, Long encounterId, Pageable pageable) {
        access.require(patientId, Access.CLINICAL_READ);
        Page<ClinicalAssessment> page = encounterId == null
                ? assessments.findByPatientId(patientId, pageable)
                : assessments.findByPatientIdAndEncounterId(patientId, encounterId, pageable);
        Map<Long, Long> counts = alerts.findByAssessmentIdIn(page.getContent().stream().map(ClinicalAssessment::getId).toList())
                .stream().collect(Collectors.groupingBy(CdssAlert::getAssessmentId, Collectors.counting()));
        Map<Long, AppUser> byId = names(page.getContent().stream().map(ClinicalAssessment::getRequestedBy));
        audit.success("LIST", "ClinicalAssessment", null, patientId);
        return PageResponse.of(page, a -> new AssessmentSummary(a.getId(), a.getEncounterId(), a.getAssessedAt(),
                nameOf(byId, a.getRequestedBy()), news2(a), a.getHighestSeverity(),
                counts.getOrDefault(a.getId(), 0L).intValue(), a.getReviewStatus()));
    }

    @Transactional(readOnly = true)
    public AssessmentResponse get(Long id) {
        ClinicalAssessment a = assessments.findById(id).orElseThrow(() -> NotFoundException.of("Assessment", id));
        access.require(a.getPatientId(), Access.CLINICAL_READ);
        audit.success("READ", "ClinicalAssessment", id, a.getPatientId());
        return toResponse(a);
    }

    /** Clinician review: every alert must be accepted or dismissed (dismissal requires a reason). */
    @Transactional
    public AssessmentResponse review(Long id, ReviewRequest req) {
        ClinicalAssessment a = assessments.findById(id).orElseThrow(() -> NotFoundException.of("Assessment", id));
        access.require(a.getPatientId(), Access.CLINICAL_WRITE);
        Long userId = CurrentUser.require().id();
        Instant now = Instant.now();
        Map<Long, CdssAlert> byId = alerts.findByAssessmentIdOrderByIdAsc(id).stream()
                .collect(Collectors.toMap(CdssAlert::getId, Function.identity()));
        Set<Long> seen = new HashSet<>();
        for (AlertDispositionRequest d : req.alerts()) {
            CdssAlert alert = byId.get(d.alertId());
            if (alert == null) {
                throw new BusinessRuleException("Alert " + d.alertId() + " does not belong to assessment " + id);
            }
            if (!seen.add(d.alertId())) {
                throw new BusinessRuleException("Alert " + d.alertId() + " listed more than once");
            }
            alert.dispose(d.disposition(), d.reason(), userId, now);
        }
        if (!seen.containsAll(byId.keySet())) {
            throw new BusinessRuleException("Every alert must be accepted or dismissed before completing the review");
        }
        a.review(userId, req.note() == null || req.note().isBlank() ? null : req.note().trim(), now);
        long dismissed = byId.values().stream().filter(x -> x.getDisposition() == CdssAlert.Disposition.DISMISSED).count();
        audit.record("REVIEW", "ClinicalAssessment", id, a.getPatientId(), Outcome.SUCCESS,
                "alerts=" + byId.size() + " dismissed=" + dismissed);
        return toResponse(a);
    }

    private static News2Summary news2(ClinicalAssessment a) {
        return a.getNews2Score() == null ? null : new News2Summary(a.getNews2Score(), a.getNews2Risk(), a.getNews2Scale());
    }

    private AssessmentResponse toResponse(ClinicalAssessment a) {
        List<CdssAlert> list = alerts.findByAssessmentIdOrderByIdAsc(a.getId());
        Map<String, GuidelineResponse> refs = new HashMap<>();
        guidelines.findAll().forEach(g -> refs.put(g.getCode(), RuleRegistryService.toGuideline(g)));
        Map<Long, AppUser> byId = names(Stream.concat(
                Stream.of(a.getRequestedBy(), a.getReviewedBy()),
                list.stream().map(CdssAlert::getDispositionBy)));
        List<AlertResponse> alertResponses = new ArrayList<>();
        for (CdssAlert x : list) {
            alertResponses.add(new AlertResponse(x.getId(), x.getRuleCode(), x.getRuleVersion(), x.getCategory(),
                    x.getSeverity(), x.getTitle(), x.getExplanation(), x.getSuggestion(), x.getEvidence(),
                    x.getGuidelineCodes().stream().map(refs::get).filter(java.util.Objects::nonNull).toList(),
                    x.getDisposition(), x.getDispositionReason(), nameOf(byId, x.getDispositionBy()),
                    x.getDispositionAt()));
        }
        // Severity-ordered for display; ties keep generation order.
        alertResponses.sort((p, q) -> q.severity().compareTo(p.severity()));
        return new AssessmentResponse(a.getId(), a.getPatientId(), a.getEncounterId(), a.getRequestedBy(),
                nameOf(byId, a.getRequestedBy()), a.getAssessedAt(), a.getEngineVersion(), news2(a),
                a.getHighestSeverity(), a.getMissingData(), a.getInputSnapshot(), a.getRulesApplied(), alertResponses,
                a.getInferenceOutput(),
                new ReviewInfo(a.getReviewStatus(), a.getReviewedBy(), nameOf(byId, a.getReviewedBy()), a.getReviewedAt(),
                        a.getReviewNote()),
                CdssDtos.DISCLAIMER);
    }

    private Map<Long, AppUser> names(Stream<Long> ids) {
        List<Long> distinct = ids.filter(java.util.Objects::nonNull).distinct().toList();
        return users.findAllById(distinct).stream().collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static String nameOf(Map<Long, AppUser> byId, Long id) {
        AppUser u = id == null ? null : byId.get(id);
        return u == null ? null : u.getFullName();
    }
}

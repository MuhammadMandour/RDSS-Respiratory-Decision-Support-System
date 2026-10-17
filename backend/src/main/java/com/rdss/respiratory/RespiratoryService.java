package com.rdss.respiratory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.common.PageResponse;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;
import com.rdss.encounters.EncounterService;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.respiratory.RespiratoryDtos.ObservationRequest;
import com.rdss.respiratory.RespiratoryDtos.ObservationResponse;
import com.rdss.respiratory.RespiratoryDtos.PulmonaryFunctionTestRequest;
import com.rdss.respiratory.RespiratoryDtos.PulmonaryFunctionTestResponse;
import com.rdss.respiratory.RespiratoryDtos.RespiratoryHistoryRequest;
import com.rdss.respiratory.RespiratoryDtos.RespiratoryHistoryResponse;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Respiratory history (versioned), observations and pulmonary function tests (append-only). */
@Service
public class RespiratoryService {

    private final RespiratoryHistoryRepository histories;
    private final ObservationRepository observations;
    private final PulmonaryFunctionTestRepository pfts;
    private final EncounterService encounterService;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final AuditService audit;

    public RespiratoryService(RespiratoryHistoryRepository histories, ObservationRepository observations,
                              PulmonaryFunctionTestRepository pfts, EncounterService encounterService,
                              AppUserRepository users, PatientAccessPolicy access, AuditService audit) {
        this.histories = histories;
        this.observations = observations;
        this.pfts = pfts;
        this.encounterService = encounterService;
        this.users = users;
        this.access = access;
        this.audit = audit;
    }

    // ---- Respiratory history ----

    @Transactional(readOnly = true)
    public Optional<RespiratoryHistoryResponse> currentHistory(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        audit.success("READ", "RespiratoryHistory", null, patientId);
        return histories.findFirstByPatientIdOrderByVersionNoDesc(patientId)
                .map(h -> toResponse(h, names(Stream.of(h.getRecordedBy()))));
    }

    @Transactional(readOnly = true)
    public List<RespiratoryHistoryResponse> historyVersions(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<RespiratoryHistory> list = histories.findByPatientIdOrderByVersionNoDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(RespiratoryHistory::getRecordedBy));
        audit.success("LIST", "RespiratoryHistory", null, patientId);
        return list.stream().map(h -> toResponse(h, byId)).toList();
    }

    /** Records a new version; previous versions are kept unchanged. */
    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public RespiratoryHistoryResponse recordHistory(Long patientId, RespiratoryHistoryRequest r) {
        access.require(patientId, Access.CLINICAL_WRITE);
        Optional<RespiratoryHistory> previous = histories.findFirstByPatientIdOrderByVersionNoDesc(patientId);
        int nextVersion = previous.map(h -> h.getVersionNo() + 1).orElse(1);
        var h = histories.save(new RespiratoryHistory(patientId, nextVersion,
                previous.map(RespiratoryHistory::getId).orElse(null), r.asthma(), r.copd(), r.bronchiectasis(),
                r.interstitialLungDisease(), r.obstructiveSleepApnea(), r.previousTuberculosis(),
                r.hypercapnicRespiratoryFailureRisk(), r.targetSpo2Low(), r.targetSpo2High(), r.homeOxygen(),
                r.exacerbationsLast12m(), r.hospitalizationsLast12m(), r.previousIntubation(),
                trim(r.inhalerTherapy()), trim(r.occupationalExposures()), trim(r.familyHistory()),
                trim(r.vaccinations()), trim(r.comorbidities()), trim(r.notes()), CurrentUser.require().id()));
        audit.record("CREATE", "RespiratoryHistory", h.getId(), patientId, Outcome.SUCCESS, "version=" + nextVersion);
        return toResponse(h, names(Stream.of(h.getRecordedBy())));
    }

    // ---- Observations ----

    @Transactional(readOnly = true)
    public PageResponse<ObservationResponse> listObservations(Long patientId, Long encounterId, Pageable pageable) {
        access.require(patientId, Access.CLINICAL_READ);
        Page<Observation> page = encounterId == null
                ? observations.findByPatientId(patientId, pageable)
                : observations.findByPatientIdAndEncounterId(patientId, encounterId, pageable);
        Map<Long, AppUser> byId = names(page.getContent().stream().map(Observation::getRecordedBy));
        audit.success("LIST", "Observation", null, patientId);
        return PageResponse.of(page, o -> toResponse(o, byId));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public ObservationResponse recordObservation(Long patientId, ObservationRequest r) {
        access.require(patientId, Access.CLINICAL_WRITE);
        if (r.encounterId() != null) {
            encounterService.requireEditableForPatient(r.encounterId(), patientId);
        }
        var o = observations.save(new Observation(patientId, r.encounterId(), r.observedAt(), r.respiratoryRate(),
                r.heartRate(), r.systolicBp(), r.diastolicBp(), r.temperatureC(), r.spo2(),
                r.onSupplementalOxygen(), r.oxygenFlowLpm(), r.fio2Percent(), r.consciousness(), r.peakFlowLpm(),
                trim(r.notes()), CurrentUser.require().id()));
        audit.success("CREATE", "Observation", o.getId(), patientId);
        return toResponse(o, names(Stream.of(o.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public ObservationResponse markObservationInError(Long id, String reason) {
        Observation o = observations.findById(id).orElseThrow(() -> NotFoundException.of("Observation", id));
        access.require(o.getPatientId(), Access.CLINICAL_WRITE);
        o.markEnteredInError(reason.trim());
        audit.success("ENTERED_IN_ERROR", "Observation", id, o.getPatientId());
        return toResponse(o, names(Stream.of(o.getRecordedBy())));
    }

    // ---- Pulmonary function tests ----

    @Transactional(readOnly = true)
    public List<PulmonaryFunctionTestResponse> listPfts(Long patientId) {
        access.require(patientId, Access.CLINICAL_READ);
        List<PulmonaryFunctionTest> list = pfts.findByPatientIdOrderByPerformedAtDesc(patientId);
        Map<Long, AppUser> byId = names(list.stream().map(PulmonaryFunctionTest::getRecordedBy));
        audit.success("LIST", "PulmonaryFunctionTest", null, patientId);
        return list.stream().map(p -> toResponse(p, byId)).toList();
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public PulmonaryFunctionTestResponse recordPft(Long patientId, PulmonaryFunctionTestRequest r) {
        access.require(patientId, Access.CLINICAL_WRITE);
        if (r.encounterId() != null) {
            encounterService.requireEditableForPatient(r.encounterId(), patientId);
        }
        var p = pfts.save(new PulmonaryFunctionTest(patientId, r.encounterId(), r.performedAt(),
                r.postBronchodilator(), r.fev1L(), r.fvcL(), r.fev1PercentPredicted(), r.fvcPercentPredicted(),
                r.dlcoPercentPredicted(), r.bronchodilatorResponsePercent(), r.bronchodilatorResponseMl(),
                trim(r.qualityGrade()), trim(r.interpretation()), CurrentUser.require().id()));
        audit.success("CREATE", "PulmonaryFunctionTest", p.getId(), patientId);
        return toResponse(p, names(Stream.of(p.getRecordedBy())));
    }

    @PreAuthorize("hasRole('CLINICIAN')")
    @Transactional
    public PulmonaryFunctionTestResponse markPftInError(Long id, String reason) {
        PulmonaryFunctionTest p = pfts.findById(id)
                .orElseThrow(() -> NotFoundException.of("Pulmonary function test", id));
        access.require(p.getPatientId(), Access.CLINICAL_WRITE);
        p.markEnteredInError(reason.trim());
        audit.success("ENTERED_IN_ERROR", "PulmonaryFunctionTest", id, p.getPatientId());
        return toResponse(p, names(Stream.of(p.getRecordedBy())));
    }

    private Map<Long, AppUser> names(Stream<Long> ids) {
        return users.findAllById(ids.distinct().toList()).stream()
                .collect(Collectors.toMap(AppUser::getId, Function.identity()));
    }

    private static String nameOf(Map<Long, AppUser> byId, Long id) {
        AppUser u = byId.get(id);
        return u == null ? null : u.getFullName();
    }

    private static RespiratoryHistoryResponse toResponse(RespiratoryHistory h, Map<Long, AppUser> byId) {
        return new RespiratoryHistoryResponse(h.getId(), h.getPatientId(), h.getVersionNo(), h.getSupersedesId(),
                h.isAsthma(), h.isCopd(), h.isBronchiectasis(), h.isInterstitialLungDisease(),
                h.isObstructiveSleepApnea(), h.isPreviousTuberculosis(), h.isHypercapnicRespiratoryFailureRisk(),
                h.getTargetSpo2Low(), h.getTargetSpo2High(), h.isHomeOxygen(), h.getExacerbationsLast12m(),
                h.getHospitalizationsLast12m(), h.isPreviousIntubation(), h.getInhalerTherapy(),
                h.getOccupationalExposures(), h.getFamilyHistory(), h.getVaccinations(), h.getComorbidities(),
                h.getNotes(), h.getRecordedBy(), nameOf(byId, h.getRecordedBy()), h.getRecordedAt());
    }

    static ObservationResponse toResponse(Observation o, Map<Long, AppUser> byId) {
        return new ObservationResponse(o.getId(), o.getPatientId(), o.getEncounterId(), o.getObservedAt(),
                o.getRespiratoryRate(), o.getHeartRate(), o.getSystolicBp(), o.getDiastolicBp(), o.getTemperatureC(),
                o.getSpo2(), o.getOnSupplementalOxygen(), o.getOxygenFlowLpm(), o.getFio2Percent(),
                o.getConsciousness(), o.getPeakFlowLpm(), o.getNotes(), o.isEnteredInError(), o.getErrorReason(),
                o.getRecordedBy(), nameOf(byId, o.getRecordedBy()), o.getCreatedAt());
    }

    private static PulmonaryFunctionTestResponse toResponse(PulmonaryFunctionTest p, Map<Long, AppUser> byId) {
        return new PulmonaryFunctionTestResponse(p.getId(), p.getPatientId(), p.getEncounterId(), p.getPerformedAt(),
                p.isPostBronchodilator(), p.getFev1L(), p.getFvcL(), p.fev1FvcRatio(), p.getFev1PercentPredicted(),
                p.getFvcPercentPredicted(), p.getDlcoPercentPredicted(), p.getBronchodilatorResponsePercent(),
                p.getBronchodilatorResponseMl(), p.getQualityGrade(), p.getInterpretation(), p.isEnteredInError(),
                p.getErrorReason(), p.getRecordedBy(), nameOf(byId, p.getRecordedBy()), p.getCreatedAt());
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

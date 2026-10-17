package com.rdss.cdss;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.AsthmaControlAnswers;
import com.rdss.cdss.engine.RuleContext.EncounterFacts;
import com.rdss.cdss.engine.RuleContext.HistoryFacts;
import com.rdss.cdss.engine.RuleContext.ImagingFindingFacts;
import com.rdss.cdss.engine.RuleContext.ObservationFacts;
import com.rdss.cdss.engine.RuleContext.PatientFacts;
import com.rdss.cdss.engine.RuleContext.PftFacts;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.encounters.ClinicalEncounter;
import com.rdss.encounters.ClinicalEncounterRepository;
import com.rdss.imaging.model.ImagingFinding;
import com.rdss.imaging.model.ImagingFindingRepository;
import com.rdss.imaging.model.ImagingStudy;
import com.rdss.imaging.model.ImagingStudyRepository;
import com.rdss.patients.Patient;
import com.rdss.respiratory.Observation;
import com.rdss.respiratory.ObservationRepository;
import com.rdss.respiratory.PulmonaryFunctionTest;
import com.rdss.respiratory.PulmonaryFunctionTestRepository;
import com.rdss.respiratory.RespiratoryHistoryRepository;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Reads the clinical record and freezes it into a {@link RuleContext}. Entries marked "entered in error" are excluded. */
@Component
public class AssessmentInputCollector {

    private final RespiratoryHistoryRepository histories;
    private final ObservationRepository observations;
    private final PulmonaryFunctionTestRepository pfts;
    private final ClinicalEncounterRepository encounters;
    private final ImagingFindingRepository imagingFindings;
    private final ImagingStudyRepository imagingStudies;

    public AssessmentInputCollector(RespiratoryHistoryRepository histories, ObservationRepository observations,
                                    PulmonaryFunctionTestRepository pfts, ClinicalEncounterRepository encounters,
                                    ImagingFindingRepository imagingFindings, ImagingStudyRepository imagingStudies) {
        this.histories = histories;
        this.observations = observations;
        this.pfts = pfts;
        this.encounters = encounters;
        this.imagingFindings = imagingFindings;
        this.imagingStudies = imagingStudies;
    }

    /** Upper bound for imaging context; the IMAGING_FINDINGS rule applies its own (shorter) window. */
    static final Duration IMAGING_MAX_LOOKBACK = Duration.ofDays(730);

    @Transactional(readOnly = true)
    public RuleContext collect(Patient p, Long encounterId, AsthmaControlAnswers asthma, Instant now) {
        var patient = new PatientFacts(p.getId(), p.ageOn(LocalDate.ofInstant(now, ZoneOffset.UTC)), p.getSex().name(),
                p.getSmokingStatus().name(), p.getPackYears());

        HistoryFacts history = histories.findFirstByPatientIdOrderByVersionNoDesc(p.getId())
                .map(h -> new HistoryFacts(h.getVersionNo(), h.isAsthma(), h.isCopd(),
                        h.isHypercapnicRespiratoryFailureRisk(), h.getTargetSpo2Low(), h.getTargetSpo2High(),
                        h.getExacerbationsLast12m(), h.getHospitalizationsLast12m()))
                .orElse(null);

        List<Observation> recent = observations.findTop2ByPatientIdAndEnteredInErrorFalseOrderByObservedAtDescIdDesc(p.getId());
        ObservationFacts latest = recent.isEmpty() ? null : obs(recent.get(0));
        ObservationFacts previous = recent.size() < 2 ? null : obs(recent.get(1));

        PftFacts latestPft = pfts.findFirstByPatientIdAndEnteredInErrorFalseOrderByPerformedAtDesc(p.getId())
                .map(AssessmentInputCollector::pft).orElse(null);
        PftFacts postBd = pfts.findFirstByPatientIdAndEnteredInErrorFalseAndPostBronchodilatorTrueOrderByPerformedAtDesc(p.getId())
                .map(AssessmentInputCollector::pft).orElse(null);

        EncounterFacts encounter = null;
        if (encounterId != null) {
            ClinicalEncounter e = encounters.findById(encounterId)
                    .orElseThrow(() -> new BusinessRuleException("Encounter " + encounterId + " not found"));
            if (!e.getPatientId().equals(p.getId())) {
                throw new BusinessRuleException("Encounter " + encounterId + " does not belong to this patient");
            }
            encounter = new EncounterFacts(e.getId(), e.getStatus().name(), e.getChiefComplaint(), e.getCough(),
                    e.getWheezing(), e.getDyspnea(), e.getFever(), e.getHemoptysis(), e.getMmrcGrade(), e.getCatScore());
        }
        var findings = imagingFindings.findCurrentForPatient(p.getId(), now.minus(IMAGING_MAX_LOOKBACK));
        Map<Long, ImagingStudy> studies = imagingStudies.findAllById(
                findings.stream().map(ImagingFinding::getStudyId).distinct().toList()).stream()
                .collect(Collectors.toMap(ImagingStudy::getId, Function.identity()));
        List<ImagingFindingFacts> imaging = findings.stream().map(f -> {
            ImagingStudy s = studies.get(f.getStudyId());
            return new ImagingFindingFacts(f.getId(), f.getStudyId(), s == null ? null : s.getStudyDate(),
                    s == null ? null : s.getModalities(), f.getType().name(), f.getLaterality().name(), f.getSizeMm(),
                    f.getDescription(), f.getSource().name(), f.getStatus().name(), f.getRecordedAt());
        }).toList();
        return new RuleContext(now, patient, history, latest, previous, postBd, latestPft, encounter, asthma, imaging);
    }

    private static ObservationFacts obs(Observation o) {
        return new ObservationFacts(o.getId(), o.getObservedAt(), o.getRespiratoryRate(), o.getSpo2(),
                o.getOnSupplementalOxygen(), o.getOxygenFlowLpm(), o.getFio2Percent(), o.getSystolicBp(),
                o.getHeartRate(), o.getTemperatureC(), o.getConsciousness() == null ? null : o.getConsciousness().name());
    }

    private static PftFacts pft(PulmonaryFunctionTest t) {
        return new PftFacts(t.getId(), t.getPerformedAt(), t.isPostBronchodilator(), t.getFev1L(), t.getFvcL(),
                t.fev1FvcRatio(), t.getFev1PercentPredicted(), t.getBronchodilatorResponsePercent(),
                t.getBronchodilatorResponseMl());
    }
}

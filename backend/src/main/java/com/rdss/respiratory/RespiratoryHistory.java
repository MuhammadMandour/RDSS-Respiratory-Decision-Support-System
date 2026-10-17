package com.rdss.respiratory;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

/** One immutable version of a patient's respiratory history. Updates insert a new version. */
@Entity
@Immutable
@Table(name = "respiratory_history")
public class RespiratoryHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false)
    private Long patientId;

    @Column(name = "version_no", nullable = false)
    private int versionNo;

    @Column(name = "supersedes_id")
    private Long supersedesId;

    private boolean asthma;
    private boolean copd;
    private boolean bronchiectasis;

    @Column(name = "interstitial_lung_disease")
    private boolean interstitialLungDisease;

    @Column(name = "obstructive_sleep_apnea")
    private boolean obstructiveSleepApnea;

    @Column(name = "previous_tuberculosis")
    private boolean previousTuberculosis;

    @Column(name = "hypercapnic_respiratory_failure_risk")
    private boolean hypercapnicRespiratoryFailureRisk;

    @Column(name = "target_spo2_low")
    private Integer targetSpo2Low;

    @Column(name = "target_spo2_high")
    private Integer targetSpo2High;

    @Column(name = "home_oxygen")
    private boolean homeOxygen;

    @Column(name = "exacerbations_last_12m")
    private Integer exacerbationsLast12m;

    @Column(name = "hospitalizations_last_12m")
    private Integer hospitalizationsLast12m;

    @Column(name = "previous_intubation")
    private boolean previousIntubation;

    @Column(name = "inhaler_therapy")
    private String inhalerTherapy;

    @Column(name = "occupational_exposures")
    private String occupationalExposures;

    @Column(name = "family_history")
    private String familyHistory;

    private String vaccinations;
    private String comorbidities;
    private String notes;

    @Column(name = "recorded_by", nullable = false)
    private Long recordedBy;

    @Column(name = "recorded_at", nullable = false)
    private Instant recordedAt;

    protected RespiratoryHistory() {
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    public RespiratoryHistory(Long patientId, int versionNo, Long supersedesId, boolean asthma, boolean copd,
                              boolean bronchiectasis, boolean interstitialLungDisease, boolean obstructiveSleepApnea,
                              boolean previousTuberculosis, boolean hypercapnicRespiratoryFailureRisk,
                              Integer targetSpo2Low, Integer targetSpo2High, boolean homeOxygen,
                              Integer exacerbationsLast12m, Integer hospitalizationsLast12m,
                              boolean previousIntubation, String inhalerTherapy, String occupationalExposures,
                              String familyHistory, String vaccinations, String comorbidities, String notes,
                              Long recordedBy) {
        this.patientId = patientId;
        this.versionNo = versionNo;
        this.supersedesId = supersedesId;
        this.asthma = asthma;
        this.copd = copd;
        this.bronchiectasis = bronchiectasis;
        this.interstitialLungDisease = interstitialLungDisease;
        this.obstructiveSleepApnea = obstructiveSleepApnea;
        this.previousTuberculosis = previousTuberculosis;
        this.hypercapnicRespiratoryFailureRisk = hypercapnicRespiratoryFailureRisk;
        this.targetSpo2Low = targetSpo2Low;
        this.targetSpo2High = targetSpo2High;
        this.homeOxygen = homeOxygen;
        this.exacerbationsLast12m = exacerbationsLast12m;
        this.hospitalizationsLast12m = hospitalizationsLast12m;
        this.previousIntubation = previousIntubation;
        this.inhalerTherapy = inhalerTherapy;
        this.occupationalExposures = occupationalExposures;
        this.familyHistory = familyHistory;
        this.vaccinations = vaccinations;
        this.comorbidities = comorbidities;
        this.notes = notes;
        this.recordedBy = recordedBy;
        this.recordedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public int getVersionNo() { return versionNo; }
    public Long getSupersedesId() { return supersedesId; }
    public boolean isAsthma() { return asthma; }
    public boolean isCopd() { return copd; }
    public boolean isBronchiectasis() { return bronchiectasis; }
    public boolean isInterstitialLungDisease() { return interstitialLungDisease; }
    public boolean isObstructiveSleepApnea() { return obstructiveSleepApnea; }
    public boolean isPreviousTuberculosis() { return previousTuberculosis; }
    public boolean isHypercapnicRespiratoryFailureRisk() { return hypercapnicRespiratoryFailureRisk; }
    public Integer getTargetSpo2Low() { return targetSpo2Low; }
    public Integer getTargetSpo2High() { return targetSpo2High; }
    public boolean isHomeOxygen() { return homeOxygen; }
    public Integer getExacerbationsLast12m() { return exacerbationsLast12m; }
    public Integer getHospitalizationsLast12m() { return hospitalizationsLast12m; }
    public boolean isPreviousIntubation() { return previousIntubation; }
    public String getInhalerTherapy() { return inhalerTherapy; }
    public String getOccupationalExposures() { return occupationalExposures; }
    public String getFamilyHistory() { return familyHistory; }
    public String getVaccinations() { return vaccinations; }
    public String getComorbidities() { return comorbidities; }
    public String getNotes() { return notes; }
    public Long getRecordedBy() { return recordedBy; }
    public Instant getRecordedAt() { return recordedAt; }
}

package com.rdss.encounters;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.rdss.common.error.ConflictException;

@Entity
@Table(name = "clinical_encounter")
public class ClinicalEncounter {

    public enum Type { OUTPATIENT, INPATIENT, EMERGENCY, TELEHEALTH }

    public enum Status { IN_PROGRESS, FINALIZED, SUPERSEDED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "clinician_id", nullable = false)
    private Long clinicianId;

    @Enumerated(EnumType.STRING)
    @Column(name = "encounter_type", nullable = false)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finalized_at")
    private Instant finalizedAt;

    @Column(name = "finalized_by")
    private Long finalizedBy;

    @Column(name = "amends_encounter_id", updatable = false)
    private Long amendsEncounterId;

    @Column(name = "amendment_reason")
    private String amendmentReason;

    @Column(name = "chief_complaint")
    private String chiefComplaint;

    @Column(name = "history_of_present_illness")
    private String historyOfPresentIllness;

    private Boolean cough;

    @Column(name = "cough_duration_days")
    private Integer coughDurationDays;

    private Boolean sputum;

    @Column(name = "sputum_description")
    private String sputumDescription;

    private Boolean wheezing;
    private Boolean dyspnea;

    @Column(name = "mmrc_grade")
    private Integer mmrcGrade;

    @Column(name = "cat_score")
    private Integer catScore;

    @Column(name = "chest_pain")
    private Boolean chestPain;

    private Boolean fever;
    private Boolean hemoptysis;

    @Column(name = "symptom_duration_days")
    private Integer symptomDurationDays;

    @Column(name = "physical_exam")
    private String physicalExam;

    @Column(name = "lab_results")
    private String labResults;

    @Column(name = "assessment_notes")
    private String assessmentNotes;

    @Column(name = "plan_notes")
    private String planNotes;

    @Column(name = "follow_up")
    private String followUp;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected ClinicalEncounter() {
    }

    public ClinicalEncounter(Long patientId, Long clinicianId, Type type, Instant startedAt) {
        this.patientId = patientId;
        this.clinicianId = clinicianId;
        this.type = type;
        this.startedAt = startedAt;
    }

    /** Creates an in-progress amendment pre-populated with this encounter's documentation. */
    public ClinicalEncounter amendment(Long clinicianId, String reason) {
        var a = new ClinicalEncounter(patientId, clinicianId, type, startedAt);
        a.amendsEncounterId = id;
        a.amendmentReason = reason;
        a.chiefComplaint = chiefComplaint;
        a.historyOfPresentIllness = historyOfPresentIllness;
        a.cough = cough;
        a.coughDurationDays = coughDurationDays;
        a.sputum = sputum;
        a.sputumDescription = sputumDescription;
        a.wheezing = wheezing;
        a.dyspnea = dyspnea;
        a.mmrcGrade = mmrcGrade;
        a.catScore = catScore;
        a.chestPain = chestPain;
        a.fever = fever;
        a.hemoptysis = hemoptysis;
        a.symptomDurationDays = symptomDurationDays;
        a.physicalExam = physicalExam;
        a.labResults = labResults;
        a.assessmentNotes = assessmentNotes;
        a.planNotes = planNotes;
        a.followUp = followUp;
        return a;
    }

    public void requireEditable() {
        if (status != Status.IN_PROGRESS) {
            throw new ConflictException("Encounter " + id + " is " + status + " and can no longer be edited");
        }
    }

    public void finalizeBy(Long userId, Instant at) {
        requireEditable();
        status = Status.FINALIZED;
        finalizedAt = at;
        finalizedBy = userId;
    }

    public void supersede() {
        if (status != Status.FINALIZED) {
            throw new ConflictException("Only finalized encounters can be superseded");
        }
        status = Status.SUPERSEDED;
    }

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getPatientId() { return patientId; }
    public Long getClinicianId() { return clinicianId; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Status getStatus() { return status; }
    public Instant getStartedAt() { return startedAt; }
    public void setStartedAt(Instant startedAt) { this.startedAt = startedAt; }
    public Instant getFinalizedAt() { return finalizedAt; }
    public Long getFinalizedBy() { return finalizedBy; }
    public Long getAmendsEncounterId() { return amendsEncounterId; }
    public String getAmendmentReason() { return amendmentReason; }
    public String getChiefComplaint() { return chiefComplaint; }
    public void setChiefComplaint(String v) { this.chiefComplaint = v; }
    public String getHistoryOfPresentIllness() { return historyOfPresentIllness; }
    public void setHistoryOfPresentIllness(String v) { this.historyOfPresentIllness = v; }
    public Boolean getCough() { return cough; }
    public void setCough(Boolean v) { this.cough = v; }
    public Integer getCoughDurationDays() { return coughDurationDays; }
    public void setCoughDurationDays(Integer v) { this.coughDurationDays = v; }
    public Boolean getSputum() { return sputum; }
    public void setSputum(Boolean v) { this.sputum = v; }
    public String getSputumDescription() { return sputumDescription; }
    public void setSputumDescription(String v) { this.sputumDescription = v; }
    public Boolean getWheezing() { return wheezing; }
    public void setWheezing(Boolean v) { this.wheezing = v; }
    public Boolean getDyspnea() { return dyspnea; }
    public void setDyspnea(Boolean v) { this.dyspnea = v; }
    public Integer getMmrcGrade() { return mmrcGrade; }
    public void setMmrcGrade(Integer v) { this.mmrcGrade = v; }
    public Integer getCatScore() { return catScore; }
    public void setCatScore(Integer v) { this.catScore = v; }
    public Boolean getChestPain() { return chestPain; }
    public void setChestPain(Boolean v) { this.chestPain = v; }
    public Boolean getFever() { return fever; }
    public void setFever(Boolean v) { this.fever = v; }
    public Boolean getHemoptysis() { return hemoptysis; }
    public void setHemoptysis(Boolean v) { this.hemoptysis = v; }
    public Integer getSymptomDurationDays() { return symptomDurationDays; }
    public void setSymptomDurationDays(Integer v) { this.symptomDurationDays = v; }
    public String getPhysicalExam() { return physicalExam; }
    public void setPhysicalExam(String v) { this.physicalExam = v; }
    public String getLabResults() { return labResults; }
    public void setLabResults(String v) { this.labResults = v; }
    public String getAssessmentNotes() { return assessmentNotes; }
    public void setAssessmentNotes(String v) { this.assessmentNotes = v; }
    public String getPlanNotes() { return planNotes; }
    public void setPlanNotes(String v) { this.planNotes = v; }
    public String getFollowUp() { return followUp; }
    public void setFollowUp(String v) { this.followUp = v; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

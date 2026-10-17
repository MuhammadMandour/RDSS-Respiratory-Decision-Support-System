package com.rdss.encounters;

import java.time.Instant;
import java.time.LocalDate;

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

@Entity
@Table(name = "treatment_plan")
public class TreatmentPlan {

    public enum Status { ACTIVE, COMPLETED, DISCONTINUED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id")
    private Long encounterId;

    @Column(nullable = false)
    private String title;

    private String goals;
    private String interventions;

    @Column(name = "follow_up_instructions")
    private String followUpInstructions;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "review_date")
    private LocalDate reviewDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ACTIVE;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TreatmentPlan() {
    }

    public TreatmentPlan(Long patientId, Long encounterId, String title, LocalDate startDate, Long createdBy) {
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.title = title;
        this.startDate = startDate;
        this.createdBy = createdBy;
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
    public Long getEncounterId() { return encounterId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getGoals() { return goals; }
    public void setGoals(String goals) { this.goals = goals; }
    public String getInterventions() { return interventions; }
    public void setInterventions(String interventions) { this.interventions = interventions; }
    public String getFollowUpInstructions() { return followUpInstructions; }
    public void setFollowUpInstructions(String v) { this.followUpInstructions = v; }
    public LocalDate getStartDate() { return startDate; }
    public void setStartDate(LocalDate startDate) { this.startDate = startDate; }
    public LocalDate getReviewDate() { return reviewDate; }
    public void setReviewDate(LocalDate reviewDate) { this.reviewDate = reviewDate; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}

package com.rdss.patients;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/** A clinician authorized to access a patient's clinical record, and why. */
@Entity
@Table(name = "patient_care_team")
public class PatientCareTeamMember {

    public enum Reason { PRIMARY, ASSIGNED, REGISTERED, APPOINTMENT }

    @Embeddable
    public static class Key implements Serializable {
        @Column(name = "patient_id")
        private Long patientId;

        @Column(name = "clinician_user_id")
        private Long clinicianUserId;

        protected Key() {
        }

        public Key(Long patientId, Long clinicianUserId) {
            this.patientId = patientId;
            this.clinicianUserId = clinicianUserId;
        }

        public Long getPatientId() { return patientId; }
        public Long getClinicianUserId() { return clinicianUserId; }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k && Objects.equals(patientId, k.patientId)
                    && Objects.equals(clinicianUserId, k.clinicianUserId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(patientId, clinicianUserId);
        }
    }

    @EmbeddedId
    private Key id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Reason reason;

    @Column(name = "added_at", nullable = false)
    private Instant addedAt;

    protected PatientCareTeamMember() {
    }

    public PatientCareTeamMember(Long patientId, Long clinicianUserId, Reason reason) {
        this.id = new Key(patientId, clinicianUserId);
        this.reason = reason;
        this.addedAt = Instant.now();
    }

    public Key getId() { return id; }
    public Reason getReason() { return reason; }
    public Instant getAddedAt() { return addedAt; }
}

package com.rdss.patients;

/** Published after patient demographics are created or changed (consumed after commit, e.g. by HL7 outbound). */
public record PatientChangedEvent(Long patientId, boolean created) {
}

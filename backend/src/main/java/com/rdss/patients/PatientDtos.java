package com.rdss.patients;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request/response contracts for the patient registry. */
public final class PatientDtos {

    private PatientDtos() {
    }

    static final String PHONE_REGEX = "^[0-9+()\\-. ]{5,50}$";

    /** Full registration/update by staff. */
    public record PatientRequest(
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Patient.Sex sex,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Email @Size(max = 255) String email,
            @Size(max = 255) String addressLine,
            @Size(max = 100) String city,
            @Size(max = 20) String postalCode,
            @Size(max = 100) String country,
            @Size(max = 200) String emergencyContactName,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String emergencyContactPhone,
            @Size(max = 50) String emergencyContactRelation,
            @Size(max = 10000) String medicalHistory,
            @Size(max = 5000) String allergies,
            @Size(max = 5000) String currentMedications,
            Patient.SmokingStatus smokingStatus,
            @DecimalMin("0.0") @DecimalMax("300.0") BigDecimal packYears,
            @Size(max = 5000) String environmentalExposures,
            @Size(max = 5000) String knownRespiratoryConditions,
            Long primaryClinicianId) {
    }

    /** Contact details a patient may maintain themselves. Clinical fields stay staff-managed. */
    public record PatientContactUpdateRequest(
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String phone,
            @Email @Size(max = 255) String email,
            @Size(max = 255) String addressLine,
            @Size(max = 100) String city,
            @Size(max = 20) String postalCode,
            @Size(max = 100) String country,
            @Size(max = 200) String emergencyContactName,
            @Pattern(regexp = PHONE_REGEX, message = "invalid phone number") String emergencyContactPhone,
            @Size(max = 50) String emergencyContactRelation) {
    }

    public record ClinicianRef(Long id, String fullName) {
    }

    public record PatientSummary(
            Long id, String mrn, String firstName, String lastName, LocalDate dateOfBirth, int age,
            Patient.Sex sex, Patient.SmokingStatus smokingStatus, ClinicianRef primaryClinician, boolean active) {
    }

    public record PatientResponse(
            Long id, String mrn, String firstName, String lastName, LocalDate dateOfBirth, int age,
            Patient.Sex sex, String phone, String email, String addressLine, String city, String postalCode,
            String country, String emergencyContactName, String emergencyContactPhone,
            String emergencyContactRelation, String medicalHistory, String allergies, String currentMedications,
            Patient.SmokingStatus smokingStatus, BigDecimal packYears, String environmentalExposures,
            String knownRespiratoryConditions, ClinicianRef primaryClinician, boolean hasPortalAccount,
            boolean active, Instant createdAt, Instant updatedAt) {
    }

    public record CareTeamMemberResponse(Long clinicianId, String fullName, PatientCareTeamMember.Reason reason,
                                         Instant addedAt) {
    }

    public record AssignClinicianRequest(@NotNull Long clinicianId) {
    }
}

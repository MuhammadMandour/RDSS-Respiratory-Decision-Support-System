package com.rdss.users;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.rdss.auth.AuthDtos;
import com.rdss.common.security.Role;

public final class UserDtos {

    private UserDtos() {
    }

    public record ClinicianProfileRequest(Long departmentId, @Size(max = 50) String title,
                                          @Size(max = 150) String specialty, @Size(max = 100) String licenseNumber,
                                          @Size(max = 50) String phone, Boolean acceptingAppointments) {
    }

    public record CreateUserRequest(
            @NotBlank @Pattern(regexp = AuthDtos.USERNAME_REGEX, message = "3-100 letters, digits, '.', '_' or '-'") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 200) String fullName,
            @NotNull Role role,
            @NotBlank @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String initialPassword,
            /** For PATIENT accounts: the existing patient record to link. */
            Long patientId,
            ClinicianProfileRequest clinicianProfile) {

        @Override
        public String toString() {
            return "CreateUserRequest[username=" + username + ", role=" + role + "]";
        }
    }

    public record UpdateUserRequest(@NotBlank @Email @Size(max = 255) String email,
                                    @NotBlank @Size(max = 200) String fullName,
                                    @NotNull Role role,
                                    ClinicianProfileRequest clinicianProfile) {
    }

    public record ResetPasswordRequest(
            @NotBlank @Pattern(regexp = AuthDtos.PASSWORD_REGEX, message = AuthDtos.PASSWORD_MESSAGE) String newPassword) {

        @Override
        public String toString() {
            return "ResetPasswordRequest[***]";
        }
    }

    public record ClinicianProfileResponse(Long departmentId, String departmentName, String title, String specialty,
                                           String licenseNumber, String phone, boolean acceptingAppointments) {
    }

    public record UserResponse(Long id, String username, String email, String fullName, Role role, boolean enabled,
                               Instant lastLoginAt, Instant createdAt, Long patientId,
                               ClinicianProfileResponse clinicianProfile) {
    }

    /** Public clinician directory entry (no contact or licence details). */
    public record ClinicianDirectoryEntry(Long id, String fullName, String title, String specialty,
                                          Long departmentId, String departmentName, boolean acceptingAppointments) {
    }

    public record DepartmentRequest(@NotBlank @Size(max = 150) String name, @Size(max = 500) String description,
                                    Boolean active) {
    }

    public record DepartmentResponse(Long id, String name, String description, boolean active) {
        static DepartmentResponse from(Department d) {
            return new DepartmentResponse(d.getId(), d.getName(), d.getDescription(), d.isActive());
        }
    }
}

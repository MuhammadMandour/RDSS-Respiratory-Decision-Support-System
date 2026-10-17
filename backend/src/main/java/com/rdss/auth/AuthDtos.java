package com.rdss.auth;

import java.time.Instant;
import java.time.LocalDate;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.rdss.common.security.Role;
import com.rdss.patients.Patient;

public final class AuthDtos {

    private AuthDtos() {
    }

    /** At least 10 characters with at least one letter and one digit. */
    public static final String PASSWORD_REGEX = "^(?=.*[A-Za-z])(?=.*\\d).{10,128}$";
    public static final String PASSWORD_MESSAGE = "must be 10-128 characters and contain a letter and a digit";
    public static final String USERNAME_REGEX = "^[A-Za-z0-9._-]{3,100}$";

    public record LoginRequest(@NotBlank @Size(max = 100) String username,
                               @NotBlank @Size(max = 128) String password) {
    }

    public record RegisterPatientRequest(
            @NotBlank @Pattern(regexp = USERNAME_REGEX, message = "3-100 letters, digits, '.', '_' or '-'") String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String password,
            @NotBlank @Size(max = 100) String firstName,
            @NotBlank @Size(max = 100) String lastName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotNull Patient.Sex sex,
            @Pattern(regexp = "^[0-9+()\\-. ]{5,50}$", message = "invalid phone number") String phone) {

        @Override
        public String toString() {
            return "RegisterPatientRequest[username=" + username + "]";
        }
    }

    public record ChangePasswordRequest(
            @NotBlank String currentPassword,
            @NotBlank @Pattern(regexp = PASSWORD_REGEX, message = PASSWORD_MESSAGE) String newPassword) {

        @Override
        public String toString() {
            return "ChangePasswordRequest[***]";
        }
    }

    public record UserInfo(Long id, String username, String fullName, String email, Role role, Long patientId) {
    }

    public record AuthResponse(String accessToken, String tokenType, Instant expiresAt, UserInfo user) {
    }
}

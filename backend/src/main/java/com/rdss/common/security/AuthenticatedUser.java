package com.rdss.common.security;

/**
 * Principal stored in the security context for an authenticated request.
 *
 * @param patientId the linked patient record for PATIENT users, otherwise {@code null}
 */
public record AuthenticatedUser(Long id, String username, String fullName, Role role, Long patientId) {

    public boolean is(Role r) {
        return role == r;
    }
}

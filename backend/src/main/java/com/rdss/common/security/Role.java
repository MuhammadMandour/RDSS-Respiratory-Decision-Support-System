package com.rdss.common.security;

/** Application roles. Codes match rows in the {@code role} table. */
public enum Role {
    ADMIN,
    CLINICIAN,
    PATIENT;

    public String authority() {
        return "ROLE_" + name();
    }
}

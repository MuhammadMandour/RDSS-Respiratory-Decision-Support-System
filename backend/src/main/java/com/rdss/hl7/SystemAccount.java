package com.rdss.hl7;

import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.Role;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.stereotype.Component;

/** The disabled "system.hl7" account (created by migration V9) that authors interface records. */
@Component
public class SystemAccount {

    static final String USERNAME = "system.hl7";

    private final AppUserRepository users;
    private volatile AuthenticatedUser principal;

    public SystemAccount(AppUserRepository users) {
        this.users = users;
    }

    public AuthenticatedUser principal() {
        AuthenticatedUser p = principal;
        if (p == null) {
            AppUser u = users.findByUsernameIgnoreCase(USERNAME)
                    .orElseThrow(() -> new IllegalStateException("System account " + USERNAME + " is missing"));
            p = new AuthenticatedUser(u.getId(), u.getUsername(), u.getFullName(), Role.CLINICIAN, null);
            principal = p;
        }
        return p;
    }

    public Long id() {
        return principal().id();
    }
}

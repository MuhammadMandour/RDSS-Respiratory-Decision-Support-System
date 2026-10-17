package com.rdss.system;

import java.time.Instant;
import java.util.List;

/**
 * Public, non-sensitive status summary. Contains no hostnames, credentials or patient data.
 */
public record SystemStatusResponse(
        String application,
        String version,
        String status,
        Instant checkedAt,
        List<DependencyStatus> dependencies) {

    public record DependencyStatus(String name, String status, String detail) {
    }
}

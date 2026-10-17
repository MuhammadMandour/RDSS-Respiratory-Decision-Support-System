package com.rdss.common.error;

import java.time.Instant;
import java.util.List;

/**
 * Uniform error body returned by every endpoint.
 * Messages are safe for display and never contain stack traces or PHI.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(Instant.now(), status, error, message, path, List.of());
    }
}

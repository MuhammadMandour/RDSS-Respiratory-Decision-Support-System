package com.rdss.common.error;

/** An external dependency (Orthanc, HL7 endpoint, ...) failed or is unreachable. Mapped to 502. */
public class IntegrationException extends RuntimeException {

    public IntegrationException(String message, Throwable cause) {
        super(message, cause);
    }

    public IntegrationException(String message) {
        super(message);
    }
}

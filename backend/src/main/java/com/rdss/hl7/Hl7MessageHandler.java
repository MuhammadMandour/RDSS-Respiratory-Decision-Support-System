package com.rdss.hl7;

import java.util.Set;

import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.util.Terser;

/**
 * Applies one family of HL7 v2 messages to RDSS. Implementations run inside a transaction that
 * is rolled back on any exception; they must throw {@link Hl7ProcessingException} for business
 * errors (AE, not retried). Any other exception is treated as transient and retried.
 */
public interface Hl7MessageHandler {

    /** Supported MSH-9 values as "TYPE^TRIGGER", e.g. "ADT^A08". */
    Set<String> messageTypes();

    Result handle(Message message, Terser terser) throws HL7Exception;

    record Result(Long patientId, String resourceType, String resourceId, String note) {
    }
}

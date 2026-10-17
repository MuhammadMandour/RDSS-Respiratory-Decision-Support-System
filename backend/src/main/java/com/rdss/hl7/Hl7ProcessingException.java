package com.rdss.hl7;

/**
 * A message that is well-formed but cannot be applied (unknown patient, unknown clinician,
 * scheduling conflict...). Acknowledged with AE and never retried automatically.
 */
public class Hl7ProcessingException extends RuntimeException {

    public Hl7ProcessingException(String message) {
        super(message);
    }
}

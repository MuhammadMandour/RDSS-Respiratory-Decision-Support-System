package com.rdss.cdss.engine;

/** Ordered from least to most urgent. NONE is only used as an assessment-level summary. */
public enum Severity {
    NONE, INFO, LOW, MEDIUM, HIGH, CRITICAL;

    public static Severity max(Severity a, Severity b) {
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}

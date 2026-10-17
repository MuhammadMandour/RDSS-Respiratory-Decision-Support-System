package com.rdss.cdss.inference;

import java.util.Map;

import com.rdss.cdss.engine.RuleContext;

/**
 * Optional machine-learning inference, kept strictly separate from deterministic rules.
 * Output is stored separately on the assessment and must always carry a {@code label}
 * describing its validation status. No validated model is shipped with RDSS.
 */
public interface InferenceProvider {

    String name();

    Map<String, Object> infer(RuleContext context);
}

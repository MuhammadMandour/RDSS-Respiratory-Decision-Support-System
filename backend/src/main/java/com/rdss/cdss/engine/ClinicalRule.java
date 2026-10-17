package com.rdss.cdss.engine;

import java.util.List;
import java.util.Map;

/**
 * A deterministic, explainable decision-support rule. Implementations must be pure functions
 * of the {@link RuleContext} and parameters (no I/O), so they are trivially unit-testable and
 * reproducible from a stored input snapshot.
 */
public interface ClinicalRule {

    /** Matches {@code cdss_rule.code}. */
    String code();

    /** Implementation version; bump on any behavioural change. */
    String version();

    String category();

    List<String> guidelineCodes();

    RuleOutcome evaluate(RuleContext ctx, Map<String, Object> parameters);

    /** Reads a numeric parameter with a fallback. */
    static int intParam(Map<String, Object> params, String key, int defaultValue) {
        Object v = params == null ? null : params.get(key);
        return v instanceof Number n ? n.intValue() : defaultValue;
    }
}

package com.rdss.cdss.engine;

import java.util.List;
import java.util.Map;

/**
 * One system-generated observation about the patient.
 *
 * @param explanation why the rule fired, citing the input values used
 * @param suggestion  next step for clinician consideration, phrased as in the source guideline (never an order)
 * @param evidence    the exact input values that triggered the rule
 */
public record Finding(String ruleCode, String category, Severity severity, String title, String explanation,
                      String suggestion, Map<String, Object> evidence, List<String> guidelineCodes) {
}

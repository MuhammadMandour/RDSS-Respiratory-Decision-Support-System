package com.rdss.cdss.inference;

import java.util.LinkedHashMap;
import java.util.Map;

import com.rdss.cdss.engine.RuleContext;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * DEMONSTRATION ONLY. Enabled with {@code RDSS_CDSS_MOCK_INFERENCE=true}. Produces a fixed,
 * clearly labelled placeholder that shows where model output would appear in the UI. It
 * performs no analysis and must never be presented as a clinical result.
 */
@Component
@ConditionalOnProperty(prefix = "rdss.cdss.mock-inference", name = "enabled", havingValue = "true")
public class MockInferenceProvider implements InferenceProvider {

    @Override
    public String name() {
        return "mock-demo";
    }

    @Override
    public Map<String, Object> infer(RuleContext context) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", name());
        out.put("label", "DEMONSTRATION ONLY - mock provider, no model, no analysis performed. Not a clinical result.");
        out.put("validated", false);
        out.put("modelVersion", "none");
        out.put("inputsConsidered", context.latestObservation() == null ? 0 : 1);
        out.put("message", "This placeholder shows where outputs from a separately validated inference service "
                + "would be displayed. No prediction is made.");
        return out;
    }
}

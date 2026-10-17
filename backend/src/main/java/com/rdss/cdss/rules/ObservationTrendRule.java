package com.rdss.cdss.rules;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/** Non-validated heuristic: notable change between the two most recent observation sets. */
@Component
public class ObservationTrendRule implements ClinicalRule {

    public static final String CODE = "OBS_TREND";

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "TREND"; }
    @Override public List<String> guidelineCodes() { return List.of(); }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        var cur = ctx.latestObservation();
        var prev = ctx.previousObservation();
        if (cur == null || prev == null) {
            return out;
        }
        int windowHours = ClinicalRule.intParam(params, "windowHours", 48);
        Duration gap = Duration.between(prev.observedAt(), cur.observedAt());
        if (gap.isNegative() || gap.toHours() > windowHours) {
            return out;
        }
        int spo2Drop = ClinicalRule.intParam(params, "spo2DropThreshold", 3);
        int rrRise = ClinicalRule.intParam(params, "respRateRiseThreshold", 5);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("previousObservationId", prev.id());
        evidence.put("latestObservationId", cur.id());
        evidence.put("intervalMinutes", gap.toMinutes());
        StringBuilder changes = new StringBuilder();
        if (cur.spo2() != null && prev.spo2() != null && prev.spo2() - cur.spo2() >= spo2Drop) {
            changes.append("SpO2 fell from ").append(prev.spo2()).append("% to ").append(cur.spo2()).append("%. ");
            evidence.put("spo2", List.of(prev.spo2(), cur.spo2()));
        }
        if (cur.respiratoryRate() != null && prev.respiratoryRate() != null
                && cur.respiratoryRate() - prev.respiratoryRate() >= rrRise) {
            changes.append("Respiratory rate rose from ").append(prev.respiratoryRate()).append(" to ")
                    .append(cur.respiratoryRate()).append("/min. ");
            evidence.put("respiratoryRate", List.of(prev.respiratoryRate(), cur.respiratoryRate()));
        }
        if (!changes.isEmpty()) {
            out.add(new Finding(CODE, category(), Severity.MEDIUM, "Worsening respiratory observations",
                    changes + "Change occurred over " + gap.toMinutes() + " minutes. This is a configurable heuristic "
                            + "(thresholds: SpO2 fall >= " + spo2Drop + " points, respiratory-rate rise >= " + rrRise
                            + "/min within " + windowHours + " h), not a validated deterioration score.",
                    "Consider repeating observations and reviewing the clinical trajectory.",
                    evidence, List.of()));
        }
        return out;
    }
}

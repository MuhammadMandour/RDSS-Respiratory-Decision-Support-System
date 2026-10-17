package com.rdss.cdss.rules;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.HistoryFacts;
import com.rdss.cdss.engine.RuleContext.ObservationFacts;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/**
 * Compares SpO2 with the target range. Target precedence: clinician-documented range, else BTS
 * defaults (88-92% when hypercapnic risk is documented, otherwise the configured 94-98%).
 */
@Component
public class HypoxaemiaRule implements ClinicalRule {

    public static final String CODE = "HYPOXAEMIA";
    static final List<String> GUIDELINES = List.of("BTS-O2-2017");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "OXYGENATION"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    record Target(int low, int high, String source) {
    }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        ObservationFacts o = ctx.latestObservation();
        if (o == null || o.spo2() == null) {
            return out.missing("SpO2 not available: oxygenation could not be assessed");
        }
        Target t = target(ctx.history(), params);
        int margin = ClinicalRule.intParam(params, "severeMarginBelowTarget", 4);
        boolean onO2 = Boolean.TRUE.equals(o.onSupplementalOxygen());

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("observationId", o.id());
        evidence.put("spo2", o.spo2());
        evidence.put("onSupplementalOxygen", o.onSupplementalOxygen());
        evidence.put("targetLow", t.low());
        evidence.put("targetHigh", t.high());
        evidence.put("targetSource", t.source());

        if (o.spo2() < t.low()) {
            Severity sev = o.spo2() <= t.low() - margin ? Severity.HIGH : Severity.MEDIUM;
            out.add(new Finding(CODE, category(), sev, "SpO2 " + o.spo2() + "% below target range",
                    "Latest SpO2 is " + o.spo2() + "% " + (onO2 ? "on supplemental oxygen" : "on air")
                            + ", below the target range of " + t.low() + "-" + t.high() + "% (" + t.source() + ").",
                    "BTS 2017: assess the cause of hypoxaemia and consider prescribing/titrating oxygen to the "
                            + "target range, with repeat observations.",
                    evidence, GUIDELINES));
        } else if (o.spo2() > t.high() && onO2) {
            boolean hypercapnicRisk = ctx.history() != null && ctx.history().hypercapnicRespiratoryFailureRisk();
            out.add(new Finding(CODE, category(), hypercapnicRisk ? Severity.MEDIUM : Severity.LOW,
                    "SpO2 " + o.spo2() + "% above target range on oxygen",
                    "Latest SpO2 is " + o.spo2() + "% on supplemental oxygen, above the target range of " + t.low()
                            + "-" + t.high() + "% (" + t.source() + ")."
                            + (hypercapnicRisk ? " Hyperoxaemia may worsen hypercapnia in patients at risk." : ""),
                    "BTS 2017: consider weaning oxygen to keep SpO2 within the target range.",
                    evidence, GUIDELINES));
        }
        return out;
    }

    static Target target(HistoryFacts h, Map<String, Object> params) {
        if (h != null && h.targetSpo2Low() != null && h.targetSpo2High() != null) {
            return new Target(h.targetSpo2Low(), h.targetSpo2High(), "clinician-documented target");
        }
        if (h != null && h.hypercapnicRespiratoryFailureRisk()) {
            return new Target(88, 92, "BTS default for patients at risk of hypercapnic respiratory failure");
        }
        return new Target(ClinicalRule.intParam(params, "defaultTargetLow", 94),
                ClinicalRule.intParam(params, "defaultTargetHigh", 98), "BTS default target");
    }
}

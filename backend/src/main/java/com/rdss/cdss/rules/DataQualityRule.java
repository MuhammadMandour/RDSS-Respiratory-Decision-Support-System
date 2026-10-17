package com.rdss.cdss.rules;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/** Surfaces missing or inconsistent data that limits the other rules. */
@Component
public class DataQualityRule implements ClinicalRule {

    public static final String CODE = "DATA_QUALITY";

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "DATA_QUALITY"; }
    @Override public List<String> guidelineCodes() { return List.of("RCP-NEWS2", "BTS-O2-2017"); }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        var o = ctx.latestObservation();
        if (o == null) {
            out.missing("No observations recorded for this patient");
        } else {
            long hours = Duration.between(o.observedAt(), ctx.assessedAt()).toHours();
            if (hours >= 24) {
                out.missing("Latest observations are " + hours + " hours old");
            }
            if (Boolean.TRUE.equals(o.onSupplementalOxygen()) && o.oxygenFlowLpm() == null && o.fio2Percent() == null) {
                out.add(new Finding(CODE, category(), Severity.INFO, "Oxygen delivery not documented",
                        "Observation #" + o.id() + " records supplemental oxygen without a flow rate or FiO2.",
                        "Document the oxygen device and flow/FiO2 so SpO2 can be interpreted.",
                        Map.of("observationId", o.id()), List.of("BTS-O2-2017")));
            }
            if (o.onSupplementalOxygen() == null && o.spo2() != null) {
                out.missing("SpO2 recorded without stating air or supplemental oxygen");
            }
        }
        if (ctx.history() == null) {
            out.missing("No respiratory history recorded");
        } else if (ctx.history().copd() && !ctx.history().hypercapnicRespiratoryFailureRisk()
                && ctx.history().targetSpo2Low() == null) {
            out.add(new Finding(CODE, category(), Severity.LOW, "Hypercapnic risk status not documented",
                    "The patient has documented COPD but no documented assessment of hypercapnic respiratory failure "
                            + "risk or target SpO2 range. NEWS2 SpO2 scale 1 and a 94-98% default target were used.",
                    "BTS 2017: document a target SpO2 range for patients with COPD (88-92% if at risk of hypercapnic "
                            + "respiratory failure). NEWS2 scale 2 should only be used when that target is prescribed.",
                    Map.of("copd", true), List.of("BTS-O2-2017", "RCP-NEWS2")));
        }
        var e = ctx.encounter();
        if (e != null && Boolean.TRUE.equals(e.dyspnea()) && e.mmrcGrade() == null && e.catScore() == null) {
            out.missing("Dyspnoea documented without an mMRC grade or CAT score");
        }
        return out;
    }
}

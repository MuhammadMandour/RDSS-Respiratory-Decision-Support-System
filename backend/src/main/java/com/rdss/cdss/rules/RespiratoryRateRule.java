package com.rdss.cdss.rules;

import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/** Flags respiratory rates outside the NEWS2 zero-score band (12-20/min). */
@Component
public class RespiratoryRateRule implements ClinicalRule {

    public static final String CODE = "RESP_RATE";
    static final List<String> GUIDELINES = List.of("RCP-NEWS2");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "OBSERVATIONS"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        var o = ctx.latestObservation();
        if (o == null || o.respiratoryRate() == null) {
            return out.missing("Respiratory rate not recorded");
        }
        int rr = o.respiratoryRate();
        Severity sev;
        String band;
        if (rr <= 8 || rr >= 25) {
            sev = Severity.HIGH;
            band = rr <= 8 ? "<= 8/min (NEWS2 parameter score 3)" : ">= 25/min (NEWS2 parameter score 3)";
        } else if (rr >= 21) {
            sev = Severity.MEDIUM;
            band = "21-24/min (NEWS2 parameter score 2)";
        } else if (rr <= 11) {
            sev = Severity.LOW;
            band = "9-11/min (NEWS2 parameter score 1)";
        } else {
            return out;
        }
        out.add(new Finding(CODE, category(), sev, "Respiratory rate " + rr + "/min",
                "Latest respiratory rate is " + rr + "/min, in the NEWS2 band " + band
                        + ". The normal (score 0) band is 12-20/min.",
                "Confirm with a repeat count over a full minute and interpret with the overall NEWS2 score.",
                Map.of("observationId", o.id(), "respiratoryRate", rr), GUIDELINES));
        return out;
    }
}

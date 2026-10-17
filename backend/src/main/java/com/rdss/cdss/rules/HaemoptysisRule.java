package com.rdss.cdss.rules;

import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/** NICE NG12 1.1.2: unexplained haemoptysis at age >= 40 -> suspected cancer pathway referral. */
@Component
public class HaemoptysisRule implements ClinicalRule {

    public static final String CODE = "HAEMOPTYSIS";
    static final List<String> GUIDELINES = List.of("NICE-NG12");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "RED_FLAG"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        var e = ctx.encounter();
        if (e == null || !Boolean.TRUE.equals(e.hemoptysis())) {
            return out;
        }
        int age = ctx.patient().ageYears();
        if (age >= 40) {
            out.add(new Finding(CODE, category(), Severity.HIGH, "Haemoptysis at age " + age,
                    "Haemoptysis is documented in encounter #" + e.id() + " for a patient aged " + age + ".",
                    "NICE NG12: refer people aged 40 and over with unexplained haemoptysis using a suspected cancer "
                            + "pathway referral for lung cancer, if no other explanation is established.",
                    Map.of("encounterId", e.id(), "ageYears", age, "hemoptysis", true), GUIDELINES));
        } else {
            out.add(new Finding(CODE, category(), Severity.MEDIUM, "Haemoptysis documented",
                    "Haemoptysis is documented in encounter #" + e.id() + ". The NICE NG12 age-based referral "
                            + "criterion (40 and over) does not apply; clinical evaluation of the cause is still required.",
                    null, Map.of("encounterId", e.id(), "ageYears", age, "hemoptysis", true), GUIDELINES));
        }
        return out;
    }
}

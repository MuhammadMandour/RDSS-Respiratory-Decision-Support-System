package com.rdss.cdss.rules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.AsthmaControlAnswers;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/**
 * GINA 2024 symptom control (four yes/no questions over the past 4 weeks) and the GINA
 * bronchodilator-responsiveness criterion (FEV1 increase > 12% and > 200 mL from baseline).
 */
@Component
public class AsthmaControlRule implements ClinicalRule {

    public static final String CODE = "ASTHMA_CONTROL";
    static final List<String> GUIDELINES = List.of("GINA-2024");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "ASTHMA"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        boolean asthmaDocumented = ctx.history() != null && ctx.history().asthma();
        AsthmaControlAnswers a = ctx.asthmaControl();

        if (a != null && a.complete()) {
            int score = count(a.daytimeSymptomsMoreThanTwiceWeekly()) + count(a.nightWakingDueToAsthma())
                    + count(a.relieverNeededMoreThanTwiceWeekly()) + count(a.activityLimitation());
            String level = score == 0 ? "well controlled" : score <= 2 ? "partly controlled" : "uncontrolled";
            Severity sev = score == 0 ? Severity.INFO : score <= 2 ? Severity.LOW : Severity.MEDIUM;
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("daytimeSymptomsMoreThanTwiceWeekly", a.daytimeSymptomsMoreThanTwiceWeekly());
            evidence.put("nightWakingDueToAsthma", a.nightWakingDueToAsthma());
            evidence.put("relieverNeededMoreThanTwiceWeekly", a.relieverNeededMoreThanTwiceWeekly());
            evidence.put("activityLimitation", a.activityLimitation());
            evidence.put("score", score);
            out.add(new Finding(CODE, category(), sev, "Asthma symptoms " + level + " (GINA " + score + "/4)",
                    "GINA symptom-control assessment over the past 4 weeks: " + score
                            + " of 4 criteria present (0 = well controlled, 1-2 = partly controlled, 3-4 = uncontrolled)."
                            + " Symptom control is one part of GINA assessment; future risk factors are assessed separately.",
                    score == 0 ? "GINA 2024: continue current treatment and review regularly."
                            : "GINA 2024: before stepping up treatment, check inhaler technique, adherence, "
                                + "comorbidities and modifiable risk factors, and confirm the diagnosis.",
                    evidence, GUIDELINES));
        } else if (asthmaDocumented) {
            out.missing("Asthma is documented but the GINA symptom-control questions were not answered");
        }

        var pft = ctx.latestPft();
        if (pft != null && pft.bronchodilatorResponsePercent() != null && pft.bronchodilatorResponseMl() != null) {
            boolean meets = pft.bronchodilatorResponsePercent().compareTo(new BigDecimal("12")) > 0
                    && pft.bronchodilatorResponseMl() > 200;
            if (meets) {
                out.add(new Finding(CODE, category(), Severity.INFO, "Significant bronchodilator responsiveness",
                        "FEV1 increased by " + pft.bronchodilatorResponsePercent() + "% and "
                                + pft.bronchodilatorResponseMl()
                                + " mL after bronchodilator (GINA criterion: > 12% and > 200 mL). This indicates variable "
                                + "expiratory airflow, which supports but does not by itself establish asthma. "
                                + "The ERS/ATS 2022 standard uses a different criterion (> 10% of predicted value).",
                        asthmaDocumented ? null
                                : "Asthma is not documented. GINA 2024: interpret alongside the history of variable "
                                    + "respiratory symptoms when evaluating for asthma.",
                        Map.of("pftId", pft.id(), "responsePercent", pft.bronchodilatorResponsePercent(),
                                "responseMl", pft.bronchodilatorResponseMl()),
                        List.of("GINA-2024", "ERS-ATS-2022")));
            }
        }
        return out;
    }

    private static int count(Boolean b) {
        return Boolean.TRUE.equals(b) ? 1 : 0;
    }
}

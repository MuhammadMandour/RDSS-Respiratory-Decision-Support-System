package com.rdss.cdss.rules;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.PftFacts;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/**
 * GOLD 2025: post-bronchodilator FEV1/FVC < 0.70 confirms persistent airflow obstruction;
 * severity grade from FEV1 % predicted; ABE group from exacerbation history and symptoms.
 */
@Component
public class CopdSpirometryRule implements ClinicalRule {

    public static final String CODE = "COPD_SPIROMETRY";
    static final List<String> GUIDELINES = List.of("GOLD-2025");
    static final BigDecimal FIXED_RATIO = new BigDecimal("0.70");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "COPD"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        boolean copdDocumented = ctx.history() != null && ctx.history().copd();
        PftFacts pft = ctx.latestPostBronchodilatorPft();

        if (pft == null || pft.fev1FvcRatio() == null) {
            if (copdDocumented) {
                out.missing("COPD is documented but no post-bronchodilator spirometry (FEV1 and FVC) is recorded; "
                        + "GOLD requires spirometry to confirm the diagnosis");
            }
            return out;
        }

        boolean obstructed = pft.fev1FvcRatio().compareTo(FIXED_RATIO) < 0;
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("pftId", pft.id());
        evidence.put("performedAt", pft.performedAt().toString());
        evidence.put("fev1FvcRatio", pft.fev1FvcRatio());
        evidence.put("fev1PercentPredicted", pft.fev1PercentPredicted());

        if (!obstructed) {
            if (copdDocumented) {
                out.add(new Finding(CODE, category(), Severity.LOW,
                        "Post-bronchodilator FEV1/FVC " + pft.fev1FvcRatio() + " is not below 0.70",
                        "COPD is documented, but the latest post-bronchodilator FEV1/FVC is " + pft.fev1FvcRatio()
                                + " (GOLD threshold < 0.70).",
                        "GOLD 2025: review the basis of the COPD diagnosis; consider repeat spirometry.",
                        evidence, GUIDELINES));
            }
            return out;
        }

        String grade = grade(pft.fev1PercentPredicted());
        evidence.put("goldGrade", grade);
        String group = group(ctx);
        evidence.put("goldGroup", group);

        String explanation = "Post-bronchodilator FEV1/FVC " + pft.fev1FvcRatio()
                + " is below 0.70, indicating persistent airflow obstruction (GOLD spirometric criterion)."
                + (grade == null ? " FEV1 % predicted is not recorded, so the GOLD grade cannot be assigned."
                    : " FEV1 " + pft.fev1PercentPredicted() + "% predicted corresponds to " + grade + ".")
                + (group == null ? " GOLD group not assigned: exacerbation history or symptom score (mMRC/CAT) missing."
                    : " GOLD group " + group + " from documented exacerbation history and symptom burden.")
                + " The fixed ratio may over-diagnose obstruction in older adults.";
        String suggestion = copdDocumented
                ? "GOLD 2025: use the GOLD grade and group to inform the review of pharmacological and "
                    + "non-pharmacological management."
                : "COPD is not documented for this patient. GOLD 2025: obstruction with relevant symptoms or exposures "
                    + "warrants clinical evaluation for COPD and alternative diagnoses.";
        out.add(new Finding(CODE, category(), copdDocumented ? Severity.INFO : Severity.MEDIUM,
                "Airflow obstruction" + (grade != null ? " — " + grade : "") + (group != null ? ", group " + group : ""),
                explanation, suggestion, evidence, GUIDELINES));
        if (group == null) {
            out.missing("GOLD group needs exacerbations in the last 12 months and an mMRC grade or CAT score");
        }
        return out;
    }

    static String grade(Integer fev1Pct) {
        if (fev1Pct == null) return null;
        if (fev1Pct >= 80) return "GOLD 1 (mild)";
        if (fev1Pct >= 50) return "GOLD 2 (moderate)";
        if (fev1Pct >= 30) return "GOLD 3 (severe)";
        return "GOLD 4 (very severe)";
    }

    /**
     * GOLD ABE: E if >= 2 moderate exacerbations or >= 1 leading to hospitalisation in the past
     * year; otherwise B if mMRC >= 2 or CAT >= 10; otherwise A.
     */
    static String group(RuleContext ctx) {
        var h = ctx.history();
        if (h == null || h.exacerbationsLast12m() == null || h.hospitalizationsLast12m() == null) {
            return null;
        }
        if (h.exacerbationsLast12m() >= 2 || h.hospitalizationsLast12m() >= 1) {
            return "E";
        }
        var e = ctx.encounter();
        Integer mmrc = e == null ? null : e.mmrcGrade();
        Integer cat = e == null ? null : e.catScore();
        if (mmrc == null && cat == null) {
            return null;
        }
        boolean highSymptoms = (mmrc != null && mmrc >= 2) || (cat != null && cat >= 10);
        return highSymptoms ? "B" : "A";
    }
}

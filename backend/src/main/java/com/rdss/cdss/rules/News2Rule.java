package com.rdss.cdss.rules;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.News2Calculator;
import com.rdss.cdss.engine.News2Calculator.Risk;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.ObservationFacts;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.springframework.stereotype.Component;

/**
 * RCP NEWS2. Eligibility: age >= 16, all seven parameters present, observations recent.
 * The clinical-response text reproduces the RCP NEWS2 escalation table and should be
 * adapted to local escalation policy.
 */
@Component
public class News2Rule implements ClinicalRule {

    public static final String CODE = "NEWS2";
    static final List<String> GUIDELINES = List.of("RCP-NEWS2");

    @Override public String code() { return CODE; }
    @Override public String version() { return "1.0.0"; }
    @Override public String category() { return "DETERIORATION"; }
    @Override public List<String> guidelineCodes() { return GUIDELINES; }

    @Override
    public RuleOutcome evaluate(RuleContext ctx, Map<String, Object> params) {
        RuleOutcome out = RuleOutcome.empty();
        if (ctx.patient().ageYears() < 16) {
            return out.missing("NEWS2 not calculated: validated for adults aged 16 and over only");
        }
        ObservationFacts o = ctx.latestObservation();
        if (o == null) {
            return out.missing("NEWS2 not calculated: no observation set recorded");
        }
        int maxAge = ClinicalRule.intParam(params, "maxObservationAgeHours", 24);
        Duration age = Duration.between(o.observedAt(), ctx.assessedAt());
        if (age.toHours() >= maxAge) {
            return out.missing("NEWS2 not calculated: latest observations are " + age.toHours()
                    + " hours old (limit " + maxAge + " h)");
        }
        boolean scale2 = ctx.history() != null && ctx.history().hypercapnicRespiratoryFailureRisk();
        var input = new News2Calculator.Input(o.respiratoryRate(), o.spo2(), o.onSupplementalOxygen(),
                o.systolicBp(), o.heartRate(), o.consciousness(), o.temperatureC(), scale2);
        List<String> missing = News2Calculator.missingParameters(input);
        if (!missing.isEmpty()) {
            return out.missing("NEWS2 not calculated: missing " + String.join(", ", missing)
                    + " in observation set #" + o.id());
        }
        News2Calculator.Result r = News2Calculator.calculate(input);
        out.news2(r);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("observationId", o.id());
        evidence.put("observedAt", o.observedAt().toString());
        evidence.put("spo2Scale", r.scale());
        evidence.put("parameterScores", r.parameterScores());
        evidence.put("total", r.total());

        String scaleNote = scale2
                ? " SpO2 scale 2 was used because risk of hypercapnic respiratory failure is documented."
                : " SpO2 scale 1 was used (no documented hypercapnic respiratory failure risk).";
        String confusionNote = "CONFUSION".equals(o.consciousness())
                ? " Confusion is scored as new confusion (3) per NEWS2; verify it is new." : "";
        String explanation = "NEWS2 aggregate score " + r.total() + " (" + describe(r.risk()) + ") from observations at "
                + o.observedAt() + ". Component scores: " + r.parameterScores() + "." + scaleNote + confusionNote
                + " NEWS2 is not validated in pregnancy and was developed for acute-illness assessment.";

        out.add(new Finding(CODE, category(), severity(r), "NEWS2 score " + r.total() + " — " + describe(r.risk()),
                explanation, response(r), evidence, GUIDELINES));
        return out;
    }

    static Severity severity(News2Calculator.Result r) {
        return switch (r.risk()) {
            case HIGH -> Severity.CRITICAL;
            case MEDIUM -> Severity.HIGH;
            case LOW_MEDIUM -> Severity.MEDIUM;
            case LOW -> r.total() == 0 ? Severity.INFO : Severity.LOW;
        };
    }

    static String describe(Risk risk) {
        return switch (risk) {
            case HIGH -> "high clinical risk";
            case MEDIUM -> "medium clinical risk";
            case LOW_MEDIUM -> "low-medium clinical risk (single parameter scoring 3)";
            case LOW -> "low clinical risk";
        };
    }

    /** RCP NEWS2 recommended clinical response, by threshold. */
    static String response(News2Calculator.Result r) {
        String prefix = "RCP NEWS2 recommended response (apply local escalation policy): ";
        if (r.total() >= 7) {
            return prefix + "continuous monitoring of vital signs; emergency assessment by a team with critical-care "
                    + "competencies, with immediate escalation to a senior clinician.";
        }
        if (r.total() >= 5) {
            return prefix + "minimum 1-hourly observations; urgent assessment by a clinician with competencies in "
                    + "the assessment of acutely ill patients.";
        }
        if (r.anySingleParameterThree()) {
            return prefix + "minimum 1-hourly observations; registered nurse to inform the medical team, who will "
                    + "review and decide whether escalation is needed.";
        }
        if (r.total() >= 1) {
            return prefix + "minimum 4-6 hourly observations; assessment by a registered nurse, who decides "
                    + "whether to increase monitoring frequency or escalate.";
        }
        return prefix + "minimum 12-hourly observations (inpatient setting).";
    }
}

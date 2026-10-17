package com.rdss.cdss.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.rdss.cdss.engine.CdssEngine;
import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.engine.Finding;
import com.rdss.cdss.engine.RuleContext;
import com.rdss.cdss.engine.RuleContext.AsthmaControlAnswers;
import com.rdss.cdss.engine.RuleContext.EncounterFacts;
import com.rdss.cdss.engine.RuleContext.HistoryFacts;
import com.rdss.cdss.engine.RuleContext.ObservationFacts;
import com.rdss.cdss.engine.RuleContext.PatientFacts;
import com.rdss.cdss.engine.RuleContext.PftFacts;
import com.rdss.cdss.engine.RuleOutcome;
import com.rdss.cdss.engine.Severity;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Documented input -> output examples for every rule. Each test name states the scenario;
 * docs/CDSS_RULES.md mirrors these examples.
 */
class CdssRulesTest {

    static final Instant NOW = Instant.parse("2030-03-01T10:00:00Z");

    static PatientFacts adult(int age) {
        return new PatientFacts(1L, age, "FEMALE", "NEVER", null);
    }

    static ObservationFacts obs(long id, Instant at, Integer rr, Integer spo2, Boolean o2, Integer sbp, Integer hr,
                                String temp, String acvpu) {
        return new ObservationFacts(id, at, rr, spo2, o2, null, null, sbp, hr, temp == null ? null : new BigDecimal(temp),
                acvpu);
    }

    static ObservationFacts normalObs(Instant at) {
        return obs(1, at, 16, 97, false, 125, 75, "37.0", "ALERT");
    }

    static HistoryFacts history(boolean asthma, boolean copd, boolean hypercapnic, Integer lo, Integer hi,
                                Integer exac, Integer hosp) {
        return new HistoryFacts(1, asthma, copd, hypercapnic, lo, hi, exac, hosp);
    }

    static RuleContext ctx(PatientFacts p, HistoryFacts h, ObservationFacts latest, ObservationFacts prev,
                           PftFacts postBd, PftFacts latestPft, EncounterFacts e, AsthmaControlAnswers a) {
        return new RuleContext(NOW, p, h, latest, prev, postBd, latestPft, e, a, List.of());
    }

    static RuleOutcome run(ClinicalRule rule, RuleContext c, Map<String, Object> params) {
        return rule.evaluate(c, params);
    }

    static Finding only(RuleOutcome out) {
        assertThat(out.findings()).hasSize(1);
        return out.findings().get(0);
    }

    @Nested
    class News2 {
        final News2Rule rule = new News2Rule();
        final Map<String, Object> params = Map.of("maxObservationAgeHours", 24);

        @Test
        void abnormalVitalsGiveCriticalSeverityWithEscalationText() {
            var o = obs(5, NOW.minusSeconds(600), 24, 92, false, 102, 112, "38.6", "ALERT");
            var f = only(run(rule, ctx(adult(54), null, o, null, null, null, null, null), params));
            assertThat(f.severity()).isEqualTo(Severity.CRITICAL);
            assertThat(f.title()).contains("NEWS2 score 8");
            assertThat(f.suggestion()).contains("emergency assessment");
            assertThat(f.evidence()).containsEntry("spo2Scale", 1).containsEntry("observationId", 5L);
        }

        @Test
        void usesScale2OnlyWhenHypercapnicRiskDocumented() {
            var o = obs(5, NOW.minusSeconds(600), 18, 90, true, 130, 80, "37.0", "ALERT");
            var withRisk = run(rule, ctx(adult(70), history(false, true, true, 88, 92, 1, 0), o, null, null, null, null, null), params);
            var withoutRisk = run(rule, ctx(adult(70), history(false, true, false, null, null, 1, 0), o, null, null, null, null, null), params);
            assertThat(withRisk.news2().total()).isEqualTo(2);
            assertThat(withRisk.news2().scale()).isEqualTo(2);
            assertThat(withoutRisk.news2().total()).isEqualTo(5);
        }

        @Test
        void notCalculatedForUnder16() {
            var out = run(rule, ctx(adult(15), null, normalObs(NOW), null, null, null, null, null), params);
            assertThat(out.findings()).isEmpty();
            assertThat(out.missingData()).anyMatch(m -> m.contains("16 and over"));
        }

        @Test
        void notCalculatedWithMissingParameter() {
            var o = obs(5, NOW.minusSeconds(60), 18, 97, false, 125, 75, null, "ALERT");
            var out = run(rule, ctx(adult(40), null, o, null, null, null, null, null), params);
            assertThat(out.findings()).isEmpty();
            assertThat(out.missingData()).anyMatch(m -> m.contains("temperature"));
        }

        @Test
        void notCalculatedFromStaleObservations() {
            var out = run(rule, ctx(adult(40), null, normalObs(NOW.minus(Duration.ofHours(30))), null, null, null, null, null), params);
            assertThat(out.news2()).isNull();
            assertThat(out.missingData()).anyMatch(m -> m.contains("30 hours old"));
        }
    }

    @Nested
    class Hypoxaemia {
        final HypoxaemiaRule rule = new HypoxaemiaRule();
        final Map<String, Object> params = Map.of("defaultTargetLow", 94, "defaultTargetHigh", 98, "severeMarginBelowTarget", 4);

        @Test
        void belowDefaultTargetIsMedium() {
            var o = obs(1, NOW, 18, 92, false, 120, 80, "37.0", "ALERT");
            var f = only(run(rule, ctx(adult(50), null, o, null, null, null, null, null), params));
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.evidence()).containsEntry("targetLow", 94).containsEntry("targetSource", "BTS default target");
        }

        @Test
        void wellBelowTargetIsHigh() {
            var o = obs(1, NOW, 18, 89, false, 120, 80, "37.0", "ALERT");
            assertThat(only(run(rule, ctx(adult(50), null, o, null, null, null, null, null), params)).severity())
                    .isEqualTo(Severity.HIGH);
        }

        @Test
        void hypercapnicRiskUses88To92AndFlagsHyperoxaemiaOnOxygen() {
            var h = history(false, true, true, null, null, 0, 0);
            var ok = obs(1, NOW, 18, 90, true, 120, 80, "37.0", "ALERT");
            assertThat(run(rule, ctx(adult(70), h, ok, null, null, null, null, null), params).findings()).isEmpty();
            var high = obs(1, NOW, 18, 97, true, 120, 80, "37.0", "ALERT");
            var f = only(run(rule, ctx(adult(70), h, high, null, null, null, null, null), params));
            assertThat(f.title()).contains("above target");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        void documentedTargetTakesPrecedence() {
            var h = history(false, false, false, 92, 96, null, null);
            var o = obs(1, NOW, 18, 93, false, 120, 80, "37.0", "ALERT");
            assertThat(run(rule, ctx(adult(50), h, o, null, null, null, null, null), params).findings()).isEmpty();
        }
    }

    @Nested
    class RespRate {
        final RespiratoryRateRule rule = new RespiratoryRateRule();

        @Test
        void bandsMapToSeverity() {
            assertThat(only(run(rule, ctx(adult(50), null, obs(1, NOW, 26, 97, false, 120, 80, "37.0", "ALERT"), null, null, null, null, null), Map.of())).severity()).isEqualTo(Severity.HIGH);
            assertThat(only(run(rule, ctx(adult(50), null, obs(1, NOW, 22, 97, false, 120, 80, "37.0", "ALERT"), null, null, null, null, null), Map.of())).severity()).isEqualTo(Severity.MEDIUM);
            assertThat(only(run(rule, ctx(adult(50), null, obs(1, NOW, 10, 97, false, 120, 80, "37.0", "ALERT"), null, null, null, null, null), Map.of())).severity()).isEqualTo(Severity.LOW);
            assertThat(run(rule, ctx(adult(50), null, normalObs(NOW), null, null, null, null, null), Map.of()).findings()).isEmpty();
        }
    }

    @Nested
    class Copd {
        final CopdSpirometryRule rule = new CopdSpirometryRule();

        PftFacts postBd(String fev1, String fvc, Integer pct) {
            var f1 = new BigDecimal(fev1);
            var fv = new BigDecimal(fvc);
            return new PftFacts(9L, NOW.minus(Duration.ofDays(30)), true, f1, fv,
                    f1.divide(fv, 3, java.math.RoundingMode.HALF_UP), pct, null, null);
        }

        /** Demo patient "Samuel Okafor": FEV1 1.45 / FVC 2.90 = 0.50, 52% predicted, 2 exacerbations -> GOLD 2, group E. */
        @Test
        void obstructionGradeAndGroup() {
            var h = history(false, true, true, 88, 92, 2, 1);
            var e = new EncounterFacts(3L, "IN_PROGRESS", null, true, false, true, false, false, 2, 18);
            var f = only(run(rule, ctx(adult(70), h, null, null, postBd("1.45", "2.90", 52), null, e, null), Map.of()));
            assertThat(f.title()).contains("GOLD 2 (moderate)").contains("group E");
            assertThat(f.severity()).isEqualTo(Severity.INFO);
        }

        @Test
        void groupBFromSymptomsWithoutExacerbations() {
            var h = history(false, true, false, null, null, 0, 0);
            var e = new EncounterFacts(3L, "IN_PROGRESS", null, null, null, true, null, null, 1, 15);
            var f = only(run(rule, ctx(adult(65), h, null, null, postBd("2.00", "3.20", 72), null, e, null), Map.of()));
            assertThat(f.evidence()).containsEntry("goldGroup", "B");
        }

        @Test
        void undocumentedObstructionIsFlaggedForEvaluation() {
            var f = only(run(rule, ctx(adult(60), history(false, false, false, null, null, null, null), null, null,
                    postBd("1.80", "3.00", 65), null, null, null), Map.of()));
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(f.suggestion()).contains("not documented");
        }

        @Test
        void documentedCopdWithoutSpirometryIsMissingData() {
            var out = run(rule, ctx(adult(60), history(false, true, false, null, null, null, null), null, null, null, null, null, null), Map.of());
            assertThat(out.findings()).isEmpty();
            assertThat(out.missingData()).anyMatch(m -> m.contains("post-bronchodilator spirometry"));
        }

        @Test
        void gradeBoundaries() {
            assertThat(CopdSpirometryRule.grade(80)).startsWith("GOLD 1");
            assertThat(CopdSpirometryRule.grade(79)).startsWith("GOLD 2");
            assertThat(CopdSpirometryRule.grade(50)).startsWith("GOLD 2");
            assertThat(CopdSpirometryRule.grade(49)).startsWith("GOLD 3");
            assertThat(CopdSpirometryRule.grade(30)).startsWith("GOLD 3");
            assertThat(CopdSpirometryRule.grade(29)).startsWith("GOLD 4");
        }
    }

    @Nested
    class Asthma {
        final AsthmaControlRule rule = new AsthmaControlRule();

        @Test
        void ginaControlLevels() {
            var h = history(true, false, false, null, null, null, null);
            var well = new AsthmaControlAnswers(false, false, false, false);
            var partly = new AsthmaControlAnswers(true, false, true, false);
            var uncontrolled = new AsthmaControlAnswers(true, true, true, false);
            assertThat(only(run(rule, ctx(adult(30), h, null, null, null, null, null, well), Map.of())).title()).contains("well controlled");
            assertThat(only(run(rule, ctx(adult(30), h, null, null, null, null, null, partly), Map.of())).title()).contains("partly controlled");
            var f = only(run(rule, ctx(adult(30), h, null, null, null, null, null, uncontrolled), Map.of()));
            assertThat(f.title()).contains("uncontrolled");
            assertThat(f.severity()).isEqualTo(Severity.MEDIUM);
        }

        @Test
        void unansweredQuestionsReportedAsMissingWhenAsthmaDocumented() {
            var out = run(rule, ctx(adult(30), history(true, false, false, null, null, null, null), null, null, null, null, null,
                    new AsthmaControlAnswers(true, null, null, null)), Map.of());
            assertThat(out.findings()).isEmpty();
            assertThat(out.missingData()).anyMatch(m -> m.contains("GINA"));
        }

        @Test
        void bronchodilatorResponseMustExceedBothThresholds() {
            var meets = new PftFacts(1L, NOW, true, null, null, null, null, new BigDecimal("15.0"), 250);
            var percentOnly = new PftFacts(1L, NOW, true, null, null, null, null, new BigDecimal("15.0"), 150);
            assertThat(only(run(rule, ctx(adult(30), null, null, null, null, meets, null, null), Map.of())).title())
                    .isEqualTo("Significant bronchodilator responsiveness");
            assertThat(run(rule, ctx(adult(30), null, null, null, null, percentOnly, null, null), Map.of()).findings()).isEmpty();
        }
    }

    @Nested
    class TrendHaemoptysisAndDataQuality {

        @Test
        void trendDetectsSpo2FallWithinWindow() {
            var prev = obs(1, NOW.minus(Duration.ofHours(6)), 18, 96, false, 120, 80, "37.0", "ALERT");
            var cur = obs(2, NOW, 24, 91, false, 120, 80, "37.0", "ALERT");
            var f = only(run(new ObservationTrendRule(), ctx(adult(50), null, cur, prev, null, null, null, null),
                    Map.of("windowHours", 48, "spo2DropThreshold", 3, "respRateRiseThreshold", 5)));
            assertThat(f.explanation()).contains("SpO2 fell from 96% to 91%").contains("Respiratory rate rose")
                    .contains("not a validated deterioration score");
        }

        @Test
        void trendIgnoresObservationsOutsideWindow() {
            var prev = obs(1, NOW.minus(Duration.ofDays(5)), 18, 99, false, 120, 80, "37.0", "ALERT");
            var cur = obs(2, NOW, 18, 90, false, 120, 80, "37.0", "ALERT");
            assertThat(run(new ObservationTrendRule(), ctx(adult(50), null, cur, prev, null, null, null, null),
                    Map.of("windowHours", 48)).findings()).isEmpty();
        }

        @Test
        void haemoptysisAgeThresholdFollowsNg12() {
            var e = new EncounterFacts(7L, "IN_PROGRESS", "Coughing blood", true, null, null, null, true, null, null);
            var older = only(run(new HaemoptysisRule(), ctx(adult(40), null, null, null, null, null, e, null), Map.of()));
            assertThat(older.severity()).isEqualTo(Severity.HIGH);
            assertThat(older.suggestion()).contains("suspected cancer pathway");
            var younger = only(run(new HaemoptysisRule(), ctx(adult(39), null, null, null, null, null, e, null), Map.of()));
            assertThat(younger.severity()).isEqualTo(Severity.MEDIUM);
            assertThat(younger.suggestion()).isNull();
        }

        @Test
        void dataQualityFlagsCopdWithoutHypercapnicAssessmentAndOxygenWithoutFlow() {
            var o = obs(3, NOW, 18, 93, true, 120, 80, "37.0", "ALERT");
            var out = run(new DataQualityRule(), ctx(adult(70), history(false, true, false, null, null, null, null), o,
                    null, null, null, null, null), Map.of());
            assertThat(out.findings()).extracting(Finding::title)
                    .containsExactlyInAnyOrder("Oxygen delivery not documented", "Hypercapnic risk status not documented");
        }
    }

    @Nested
    class Engine {

        @Test
        void disabledRulesAreSkippedAndFailingRulesDoNotStopOthers() {
            ClinicalRule broken = new ClinicalRule() {
                public String code() { return "BROKEN"; }
                public String version() { return "0"; }
                public String category() { return "DATA_QUALITY"; }
                public List<String> guidelineCodes() { return List.of(); }
                public RuleOutcome evaluate(RuleContext c, Map<String, Object> p) { throw new IllegalStateException("boom"); }
            };
            var engine = new CdssEngine(List.of(new RespiratoryRateRule(), new HypoxaemiaRule(), broken));
            var o = obs(1, NOW, 26, 90, false, 120, 80, "37.0", "ALERT");
            var result = engine.run(ctx(adult(50), null, o, null, null, null, null, null), Map.of(
                    "RESP_RATE", new CdssEngine.RuleConfig(true, Map.of()),
                    "HYPOXAEMIA", new CdssEngine.RuleConfig(false, Map.of()),
                    "BROKEN", new CdssEngine.RuleConfig(true, Map.of())));
            assertThat(result.findings()).extracting(Finding::ruleCode).containsExactly("RESP_RATE");
            assertThat(result.appliedRules()).extracting(CdssEngine.AppliedRule::outcome)
                    .containsExactly("FINDINGS:1", "DISABLED", "ERROR");
            assertThat(result.missingData()).anyMatch(m -> m.contains("BROKEN"));
            assertThat(result.highestSeverity()).isEqualTo(Severity.HIGH);
        }
    }
}

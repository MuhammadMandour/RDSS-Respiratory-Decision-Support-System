package com.rdss.cdss.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Runs every enabled deterministic rule against a context. Pure orchestration: rule enablement
 * and parameters are passed in, so the engine itself has no database dependency.
 */
@Component
public class CdssEngine {

    public static final String VERSION = "1.1.0";
    private static final Logger log = LoggerFactory.getLogger(CdssEngine.class);

    /** Enablement and parameters for one rule, from the registry. */
    public record RuleConfig(boolean enabled, Map<String, Object> parameters) {
    }

    public record AppliedRule(String code, String version, String outcome) {
    }

    public record Result(List<Finding> findings, List<String> missingData, List<AppliedRule> appliedRules,
                         News2Calculator.Result news2, Severity highestSeverity, Map<String, String> ruleVersions) {
    }

    private final Map<String, ClinicalRule> rules;

    public CdssEngine(List<ClinicalRule> rules) {
        this.rules = rules.stream().collect(Collectors.toMap(ClinicalRule::code, Function.identity(),
                (a, b) -> { throw new IllegalStateException("Duplicate CDSS rule code " + a.code()); },
                LinkedHashMap::new));
    }

    public Map<String, ClinicalRule> rules() {
        return rules;
    }

    public Result run(RuleContext ctx, Map<String, RuleConfig> config) {
        List<Finding> findings = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        List<AppliedRule> applied = new ArrayList<>();
        Map<String, String> versions = new LinkedHashMap<>();
        News2Calculator.Result news2 = null;

        for (ClinicalRule rule : rules.values()) {
            RuleConfig cfg = config.get(rule.code());
            if (cfg == null) {
                log.warn("CDSS rule {} has no registry entry; skipped", rule.code());
                continue;
            }
            if (!cfg.enabled()) {
                applied.add(new AppliedRule(rule.code(), rule.version(), "DISABLED"));
                continue;
            }
            versions.put(rule.code(), rule.version());
            try {
                RuleOutcome out = rule.evaluate(ctx, cfg.parameters());
                findings.addAll(out.findings());
                missing.addAll(out.missingData());
                if (out.news2() != null) {
                    news2 = out.news2();
                }
                applied.add(new AppliedRule(rule.code(), rule.version(),
                        out.findings().isEmpty() ? "NO_FINDINGS" : "FINDINGS:" + out.findings().size()));
            } catch (RuntimeException ex) {
                // A defective rule must never silently suppress others; record and continue.
                log.error("CDSS rule {} failed", rule.code(), ex);
                missing.add("Rule " + rule.code() + " could not be evaluated (internal error)");
                applied.add(new AppliedRule(rule.code(), rule.version(), "ERROR"));
            }
        }
        findings.sort(Comparator.comparing(Finding::severity).reversed());
        Severity highest = findings.stream().map(Finding::severity).reduce(Severity.NONE, Severity::max);
        return new Result(findings, missing, applied, news2, highest, versions);
    }
}

package com.rdss.cdss.engine;

import java.util.ArrayList;
import java.util.List;

/** What a rule produced: findings, plus data it needed but could not find. */
public final class RuleOutcome {

    private final List<Finding> findings = new ArrayList<>();
    private final List<String> missingData = new ArrayList<>();
    private News2Calculator.Result news2;

    public static RuleOutcome empty() {
        return new RuleOutcome();
    }

    public RuleOutcome add(Finding f) {
        findings.add(f);
        return this;
    }

    public RuleOutcome missing(String description) {
        missingData.add(description);
        return this;
    }

    public RuleOutcome news2(News2Calculator.Result result) {
        this.news2 = result;
        return this;
    }

    public List<Finding> findings() {
        return findings;
    }

    public List<String> missingData() {
        return missingData;
    }

    public News2Calculator.Result news2() {
        return news2;
    }
}

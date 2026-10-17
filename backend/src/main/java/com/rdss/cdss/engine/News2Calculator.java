package com.rdss.cdss.engine;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * NEWS2 scoring exactly as published by the Royal College of Physicians (2017), chart 1.
 * The scoring bands are fixed by the standard and intentionally not configurable.
 */
public final class News2Calculator {

    public enum Risk { LOW, LOW_MEDIUM, MEDIUM, HIGH }

    public record Input(Integer respiratoryRate, Integer spo2, Boolean onOxygen, Integer systolicBp,
                        Integer pulse, String consciousness, BigDecimal temperatureC, boolean useScale2) {
    }

    public record Result(int total, Risk risk, int scale, boolean anySingleParameterThree,
                         Map<String, Integer> parameterScores) {
    }

    private News2Calculator() {
    }

    /** Names of NEWS2 parameters that are missing (empty when the score can be calculated). */
    public static List<String> missingParameters(Input in) {
        List<String> missing = new ArrayList<>();
        if (in.respiratoryRate() == null) missing.add("respiratory rate");
        if (in.spo2() == null) missing.add("SpO2");
        if (in.onOxygen() == null) missing.add("air or oxygen");
        if (in.systolicBp() == null) missing.add("systolic blood pressure");
        if (in.pulse() == null) missing.add("pulse");
        if (in.consciousness() == null) missing.add("consciousness (ACVPU)");
        if (in.temperatureC() == null) missing.add("temperature");
        return missing;
    }

    public static Result calculate(Input in) {
        if (!missingParameters(in).isEmpty()) {
            throw new IllegalArgumentException("NEWS2 requires all seven parameters");
        }
        Map<String, Integer> s = new LinkedHashMap<>();
        s.put("respiratoryRate", respiratoryRate(in.respiratoryRate()));
        s.put("spo2", in.useScale2() ? spo2Scale2(in.spo2(), in.onOxygen()) : spo2Scale1(in.spo2()));
        s.put("airOrOxygen", in.onOxygen() ? 2 : 0);
        s.put("systolicBp", systolic(in.systolicBp()));
        s.put("pulse", pulse(in.pulse()));
        s.put("consciousness", "ALERT".equals(in.consciousness()) ? 0 : 3);
        s.put("temperature", temperature(in.temperatureC()));
        int total = s.values().stream().mapToInt(Integer::intValue).sum();
        boolean single3 = s.values().stream().anyMatch(v -> v == 3);
        return new Result(total, risk(total, single3), in.useScale2() ? 2 : 1, single3, s);
    }

    static Risk risk(int total, boolean anySingleParameterThree) {
        if (total >= 7) return Risk.HIGH;
        if (total >= 5) return Risk.MEDIUM;
        if (anySingleParameterThree) return Risk.LOW_MEDIUM;
        return Risk.LOW;
    }

    static int respiratoryRate(int rr) {
        if (rr <= 8) return 3;
        if (rr <= 11) return 1;
        if (rr <= 20) return 0;
        if (rr <= 24) return 2;
        return 3;
    }

    static int spo2Scale1(int spo2) {
        if (spo2 <= 91) return 3;
        if (spo2 <= 93) return 2;
        if (spo2 <= 95) return 1;
        return 0;
    }

    /** Scale 2: for patients with a prescribed 88-92% target due to hypercapnic respiratory failure. */
    static int spo2Scale2(int spo2, boolean onOxygen) {
        if (spo2 <= 83) return 3;
        if (spo2 <= 85) return 2;
        if (spo2 <= 87) return 1;
        if (spo2 <= 92) return 0;
        // >= 93: scores 0 on air; on oxygen high saturations are scored.
        if (!onOxygen) return 0;
        if (spo2 <= 94) return 1;
        if (spo2 <= 96) return 2;
        return 3;
    }

    static int systolic(int sbp) {
        if (sbp <= 90) return 3;
        if (sbp <= 100) return 2;
        if (sbp <= 110) return 1;
        if (sbp <= 219) return 0;
        return 3;
    }

    static int pulse(int hr) {
        if (hr <= 40) return 3;
        if (hr <= 50) return 1;
        if (hr <= 90) return 0;
        if (hr <= 110) return 1;
        if (hr <= 130) return 2;
        return 3;
    }

    static int temperature(BigDecimal t) {
        // Chart bands use one decimal place: <=35.0, 35.1-36.0, 36.1-38.0, 38.1-39.0, >=39.1
        double v = t.doubleValue();
        if (v <= 35.0) return 3;
        if (v <= 36.0) return 1;
        if (v <= 38.0) return 0;
        if (v <= 39.0) return 1;
        return 2;
    }
}

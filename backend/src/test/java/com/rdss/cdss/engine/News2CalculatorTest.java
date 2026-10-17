package com.rdss.cdss.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import com.rdss.cdss.engine.News2Calculator.Input;
import com.rdss.cdss.engine.News2Calculator.Risk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Band boundaries from the RCP NEWS2 (2017) observation chart. */
class News2CalculatorTest {

    @ParameterizedTest(name = "RR {0} -> {1}")
    @CsvSource({"8,3", "9,1", "11,1", "12,0", "20,0", "21,2", "24,2", "25,3"})
    void respiratoryRateBands(int rr, int score) {
        assertThat(News2Calculator.respiratoryRate(rr)).isEqualTo(score);
    }

    @ParameterizedTest(name = "SpO2 scale 1 {0} -> {1}")
    @CsvSource({"91,3", "92,2", "93,2", "94,1", "95,1", "96,0", "100,0"})
    void spo2Scale1Bands(int spo2, int score) {
        assertThat(News2Calculator.spo2Scale1(spo2)).isEqualTo(score);
    }

    @ParameterizedTest(name = "SpO2 scale 2 {0} oxygen={1} -> {2}")
    @CsvSource({"83,false,3", "84,false,2", "85,false,2", "86,false,1", "87,false,1", "88,false,0", "92,false,0",
            "93,false,0", "97,false,0", "93,true,1", "94,true,1", "95,true,2", "96,true,2", "97,true,3"})
    void spo2Scale2Bands(int spo2, boolean oxygen, int score) {
        assertThat(News2Calculator.spo2Scale2(spo2, oxygen)).isEqualTo(score);
    }

    @ParameterizedTest(name = "SBP {0} -> {1}")
    @CsvSource({"90,3", "91,2", "100,2", "101,1", "110,1", "111,0", "219,0", "220,3"})
    void systolicBands(int sbp, int score) {
        assertThat(News2Calculator.systolic(sbp)).isEqualTo(score);
    }

    @ParameterizedTest(name = "Pulse {0} -> {1}")
    @CsvSource({"40,3", "41,1", "50,1", "51,0", "90,0", "91,1", "110,1", "111,2", "130,2", "131,3"})
    void pulseBands(int hr, int score) {
        assertThat(News2Calculator.pulse(hr)).isEqualTo(score);
    }

    @ParameterizedTest(name = "Temp {0} -> {1}")
    @CsvSource({"35.0,3", "35.1,1", "36.0,1", "36.1,0", "38.0,0", "38.1,1", "39.0,1", "39.1,2"})
    void temperatureBands(String t, int score) {
        assertThat(News2Calculator.temperature(new BigDecimal(t))).isEqualTo(score);
    }

    /**
     * Worked example (synthetic demo patient "Maria Novak"):
     * RR 24 (2), SpO2 92 on air scale 1 (2), air (0), SBP 102 (1), pulse 112 (2), alert (0), 38.6 C (1)
     * = 8 -> HIGH clinical risk.
     */
    @Test
    void workedExampleHighRisk() {
        var r = News2Calculator.calculate(new Input(24, 92, false, 102, 112, "ALERT", new BigDecimal("38.6"), false));
        assertThat(r.total()).isEqualTo(8);
        assertThat(r.risk()).isEqualTo(Risk.HIGH);
        assertThat(r.scale()).isEqualTo(1);
    }

    /** Single red parameter: RR 26 (3), everything else normal = 3 -> LOW_MEDIUM (urgent ward-based response). */
    @Test
    void singleParameterThreeIsLowMedium() {
        var r = News2Calculator.calculate(new Input(26, 97, false, 125, 75, "ALERT", new BigDecimal("37.0"), false));
        assertThat(r.total()).isEqualTo(3);
        assertThat(r.anySingleParameterThree()).isTrue();
        assertThat(r.risk()).isEqualTo(Risk.LOW_MEDIUM);
    }

    /** New confusion scores 3 on its own. */
    @Test
    void confusionScoresThree() {
        var r = News2Calculator.calculate(new Input(16, 97, false, 125, 75, "CONFUSION", new BigDecimal("37.0"), false));
        assertThat(r.parameterScores()).containsEntry("consciousness", 3);
        assertThat(r.risk()).isEqualTo(Risk.LOW_MEDIUM);
    }

    /** Aggregate 5-6 -> MEDIUM; 0-4 without a 3 -> LOW. */
    @Test
    void mediumAndLowThresholds() {
        // RR 22 (2), SpO2 94 (1), air (0), SBP 105 (1), pulse 95 (1) = 5
        var medium = News2Calculator.calculate(new Input(22, 94, false, 105, 95, "ALERT", new BigDecimal("37.0"), false));
        assertThat(medium.total()).isEqualTo(5);
        assertThat(medium.risk()).isEqualTo(Risk.MEDIUM);
        var low = News2Calculator.calculate(new Input(16, 97, false, 125, 75, "ALERT", new BigDecimal("37.0"), false));
        assertThat(low.total()).isZero();
        assertThat(low.risk()).isEqualTo(Risk.LOW);
    }

    /**
     * Scale 2 (hypercapnic target 88-92%): SpO2 90 on 2 L oxygen scores 0 for saturation but 2 for oxygen.
     * The same patient on scale 1 would score 3 for SpO2.
     */
    @Test
    void scale2ChangesSaturationScoring() {
        var s2 = News2Calculator.calculate(new Input(18, 90, true, 130, 80, "ALERT", new BigDecimal("37.0"), true));
        var s1 = News2Calculator.calculate(new Input(18, 90, true, 130, 80, "ALERT", new BigDecimal("37.0"), false));
        assertThat(s2.parameterScores()).containsEntry("spo2", 0).containsEntry("airOrOxygen", 2);
        assertThat(s2.total()).isEqualTo(2);
        assertThat(s1.parameterScores()).containsEntry("spo2", 3);
        assertThat(s1.total()).isEqualTo(5);
    }

    @Test
    void refusesIncompleteInput() {
        var in = new Input(18, null, false, 130, 80, "ALERT", new BigDecimal("37.0"), false);
        assertThat(News2Calculator.missingParameters(in)).containsExactly("SpO2");
        assertThatThrownBy(() -> News2Calculator.calculate(in)).isInstanceOf(IllegalArgumentException.class);
    }
}

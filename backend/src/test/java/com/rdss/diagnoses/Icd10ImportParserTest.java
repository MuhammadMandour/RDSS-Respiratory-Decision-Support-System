package com.rdss.diagnoses;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.StringReader;

import org.junit.jupiter.api.Test;

public class Icd10ImportParserTest {

    /** Builds a line in the CDC order-file layout. */
    public static String line(int order, String code, int valid, String shortDesc, String longDesc) {
        return String.format("%05d %-7s %d %-60s %s", order, code, valid, shortDesc, longDesc);
    }

    @Test
    void parsesFixedWidthOrderFileAndInsertsDots() throws Exception {
        String file = String.join("\r\n",
                line(1, "J44", 0, "Other chronic obstructive pulmonary disease", "Other chronic obstructive pulmonary disease"),
                line(2, "J441", 1, "Chronic obstructive pulmonary disease w (acute) exacerbation",
                        "Chronic obstructive pulmonary disease with (acute) exacerbation"),
                line(3, "J45909", 1, "Unspecified asthma, uncomplicated", "Unspecified asthma, uncomplicated"),
                line(4, "J00", 1, "Acute nasopharyngitis [common cold]", "Acute nasopharyngitis [common cold]"),
                "",
                "garbage line",
                line(5, "U0", 1, "bad", "Bad code"));

        var r = Icd10ImportParser.parse(new BufferedReader(new StringReader(file)));

        assertThat(r.entries()).extracting(Icd10ImportParser.Entry::code).containsExactly("J44", "J44.1", "J45.909", "J00");
        assertThat(r.entries().get(0).billable()).isFalse();
        assertThat(r.entries().get(1).billable()).isTrue();
        assertThat(r.entries().get(1).description()).isEqualTo("Chronic obstructive pulmonary disease with (acute) exacerbation");
        assertThat(r.rejectedLines()).hasSize(2);
        assertThat(r.entries()).allSatisfy(e -> assertThat(Icd10ImportParser.DOTTED.matcher(e.code()).matches()).isTrue());
    }

    @Test
    void dottedFormat() {
        assertThat(Icd10ImportParser.toDotted("J449")).isEqualTo("J44.9");
        assertThat(Icd10ImportParser.toDotted("Z87891")).isEqualTo("Z87.891");
        assertThat(Icd10ImportParser.toDotted("J13")).isEqualTo("J13");
    }
}

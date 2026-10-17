package com.rdss.hl7;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import org.junit.jupiter.api.Test;

class Hl7SupportTest {

    @Test
    void readsHeaderWithoutFullParse() {
        String m = Hl7Support.normalize("MSH|^~\\&|EMR^1.2^ISO|CITYHOSP|RDSS|RDSS|20260101||ADT^A04^ADT_A01|ABC123|P|2.5.1\nPID|1\n");
        var h = Hl7Support.readHeader(m);
        assertThat(h.sendingApplication()).isEqualTo("EMR");
        assertThat(h.sendingFacility()).isEqualTo("CITYHOSP");
        assertThat(h.messageType()).isEqualTo("ADT^A04");
        assertThat(h.controlId()).isEqualTo("ABC123");
        assertThat(h.version()).isEqualTo("2.5.1");
        assertThat(m).doesNotContain("\n");
        assertThat(Hl7Support.readHeader("PID|1")).isNull();
    }

    @Test
    void stripsMllpFraming() {
        assertThat(Hl7Support.normalize("\u000bMSH|^~\\&|A\r\u001c\r")).isEqualTo("MSH|^~\\&|A");
    }

    @Test
    void parsesHl7DateTimes() {
        ZoneId london = ZoneId.of("Europe/London");
        assertThat(Hl7Support.parseInstant("20300601101500", london)).isEqualTo(Instant.parse("2030-06-01T09:15:00Z"));
        assertThat(Hl7Support.parseInstant("203006011015+0200", london)).isEqualTo(Instant.parse("2030-06-01T08:15:00Z"));
        assertThat(Hl7Support.parseInstant("20300601", ZoneId.of("UTC"))).isEqualTo(Instant.parse("2030-06-01T00:00:00Z"));
        assertThat(Hl7Support.parseInstant(null, london)).isNull();
        assertThatThrownBy(() -> Hl7Support.parseInstant("2030-06-01", london)).isInstanceOf(IllegalArgumentException.class);
        assertThat(Hl7Support.parseDate("19800215120000")).isEqualTo(LocalDate.of(1980, 2, 15));
    }

    @Test
    void rawRejectEscapesErrorText() {
        var h = new Hl7Message.Header("X1", "EMR", "HOSP", null, null, "ADT^A04", "2.5");
        String ack = Hl7Support.rawReject(h, "bad | field ^ text\r");
        assertThat(ack).contains("MSA|AR|X1|bad   field   text");
        assertThat(ack.split("\r")).hasSize(2);
    }
}

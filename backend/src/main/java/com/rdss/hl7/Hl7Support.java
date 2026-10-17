package com.rdss.hl7;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Small, dependency-free helpers for HL7 v2 text. */
public final class Hl7Support {

    private static final Pattern DTM = Pattern.compile(
            "^(\\d{4})(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(\\d{2})?(?:\\.\\d{1,4})?([+-]\\d{4})?$");

    private Hl7Support() {
    }

    /** Normalizes segment separators to CR (HL7 standard) and trims transport noise. */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace("\r\n", "\r").replace('\n', '\r').strip();
        // Strip MLLP framing characters if a client sent them over HTTP.
        return s.replace("\u000b", "").replace("\u001c", "");
    }

    /**
     * Reads MSH fields without a full parse, so even unparseable messages can be logged and
     * acknowledged. Returns null when there is no MSH segment.
     */
    public static Hl7Message.Header readHeader(String normalized) {
        if (!normalized.startsWith("MSH") || normalized.length() < 8) {
            return null;
        }
        char fs = normalized.charAt(3);
        char cs = normalized.charAt(4);
        String msh = normalized.split("\r", 2)[0];
        String[] f = msh.split(Pattern.quote(String.valueOf(fs)), -1);
        // f[0]="MSH", f[1]=encoding chars, so MSH-n is f[n-1].
        String type = field(f, 9);
        String[] typeParts = type == null ? new String[0] : type.split(Pattern.quote(String.valueOf(cs)));
        String messageType = typeParts.length >= 2 ? typeParts[0] + "^" + typeParts[1]
                : typeParts.length == 1 ? typeParts[0] : null;
        return new Hl7Message.Header(trunc(field(f, 10)), first(field(f, 3), cs), first(field(f, 4), cs),
                first(field(f, 5), cs), first(field(f, 6), cs), trunc(messageType), trunc(first(field(f, 12), cs)));
    }

    private static String field(String[] f, int mshIndex) {
        int i = mshIndex - 1;
        return i < f.length && !f[i].isBlank() ? f[i].trim() : null;
    }

    private static String first(String v, char cs) {
        return v == null ? null : v.split(Pattern.quote(String.valueOf(cs)), -1)[0];
    }

    private static String trunc(String v) {
        return v == null ? null : v.length() > 64 ? v.substring(0, 64) : v;
    }

    /** Parses an HL7 DTM/TS. Without an explicit offset the value is interpreted in {@code zone}. */
    public static Instant parseInstant(String value, ZoneId zone) {
        if (value == null || value.isBlank()) {
            return null;
        }
        Matcher m = DTM.matcher(value.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("Invalid HL7 date/time: " + value);
        }
        LocalDateTime ldt = LocalDateTime.of(Integer.parseInt(m.group(1)), num(m.group(2), 1), num(m.group(3), 1),
                num(m.group(4), 0), num(m.group(5), 0), num(m.group(6), 0));
        if (m.group(7) != null) {
            return ldt.toInstant(ZoneOffset.of(m.group(7).substring(0, 3) + ":" + m.group(7).substring(3)));
        }
        return ldt.atZone(zone).toInstant();
    }

    public static LocalDate parseDate(String value) {
        if (value == null || value.length() < 8) {
            return null;
        }
        return LocalDate.parse(value.substring(0, 8), DateTimeFormatter.BASIC_ISO_DATE);
    }

    private static int num(String s, int dflt) {
        return s == null ? dflt : Integer.parseInt(s);
    }

    /** Generic AR acknowledgement for messages HAPI cannot parse. */
    public static String rawReject(Hl7Message.Header h, String error) {
        String ts = DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(LocalDateTime.now(ZoneOffset.UTC));
        String control = h == null || h.controlId() == null ? "" : h.controlId();
        String text = error == null ? "" : error.replaceAll("[|^~\\\\&\r\n]", " ");
        if (text.length() > 200) {
            text = text.substring(0, 200);
        }
        return "MSH|^~\\&|RDSS|RDSS|" + nz(h == null ? null : h.sendingApplication()) + "|"
                + nz(h == null ? null : h.sendingFacility()) + "|" + ts + "||ACK|RDSS" + ts + "|P|2.5\r"
                + "MSA|AR|" + control + "|" + text + "\r";
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}

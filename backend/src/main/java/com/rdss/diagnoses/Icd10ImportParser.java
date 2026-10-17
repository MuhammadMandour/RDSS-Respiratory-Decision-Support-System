package com.rdss.diagnoses;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Parses the official CDC/NCHS ICD-10-CM "order" file ({@code icd10cm_order_YYYY.txt}), a
 * fixed-width format:
 * <pre>
 *   cols 1-5   order number
 *   cols 7-13  code without the dot, left-justified
 *   col  15    1 = valid for submission (billable), 0 = header
 *   cols 17-76 short description
 *   cols 78-   long description
 * </pre>
 */
public final class Icd10ImportParser {

    public static final Pattern DOTTED = Pattern.compile("^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$");
    private static final Pattern RAW = Pattern.compile("^[A-Z][0-9][0-9A-Z][0-9A-Z]{0,4}$");

    public record Entry(String code, boolean billable, String description) {
    }

    public record Result(List<Entry> entries, List<String> rejectedLines) {
    }

    private Icd10ImportParser() {
    }

    public static Result parse(BufferedReader reader) throws IOException {
        List<Entry> entries = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.isBlank()) {
                continue;
            }
            if (line.length() < 78) {
                rejected.add("line " + lineNo + ": too short for the order-file format");
                continue;
            }
            String raw = line.substring(6, 13).trim();
            char flag = line.charAt(14);
            String longDescription = line.substring(77).trim();
            if (!RAW.matcher(raw).matches() || (flag != '0' && flag != '1') || longDescription.isEmpty()) {
                rejected.add("line " + lineNo + ": invalid code, flag or description");
                continue;
            }
            entries.add(new Entry(toDotted(raw), flag == '1',
                    longDescription.length() > 400 ? longDescription.substring(0, 400) : longDescription));
        }
        return new Result(entries, rejected);
    }

    /** "J449" -> "J44.9", "J45909" -> "J45.909", "J00" -> "J00". */
    public static String toDotted(String raw) {
        return raw.length() <= 3 ? raw : raw.substring(0, 3) + "." + raw.substring(3);
    }
}

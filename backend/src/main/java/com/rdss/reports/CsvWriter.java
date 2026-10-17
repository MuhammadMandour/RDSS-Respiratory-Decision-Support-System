package com.rdss.reports;

import java.util.List;

/**
 * Minimal RFC 4180 CSV builder. Cells that a spreadsheet would interpret as a formula
 * (leading =, +, -, @, tab or carriage return) are prefixed with a single quote to prevent
 * CSV/formula injection when the export is opened in Excel or similar tools. Numeric values
 * passed as {@link Number} are written unchanged so negative amounts stay numeric.
 */
final class CsvWriter {

    private final StringBuilder out = new StringBuilder();

    CsvWriter row(List<?> cells) {
        for (int i = 0; i < cells.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(cell(cells.get(i)));
        }
        out.append("\r\n");
        return this;
    }

    CsvWriter row(Object... cells) {
        return row(java.util.Arrays.asList(cells));
    }

    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Number) {
            return value.toString();
        }
        String s = value.toString();
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        if (s.indexOf(',') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0) {
            s = '"' + s.replace("\"", "\"\"") + '"';
        }
        return s;
    }

    @Override
    public String toString() {
        return out.toString();
    }
}

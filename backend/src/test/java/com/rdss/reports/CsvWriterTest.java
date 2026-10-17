package com.rdss.reports;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CsvWriterTest {

    @Test
    void quotesAndEscapes() {
        assertThat(CsvWriter.cell("plain")).isEqualTo("plain");
        assertThat(CsvWriter.cell("a,b")).isEqualTo("\"a,b\"");
        assertThat(CsvWriter.cell("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"");
        assertThat(CsvWriter.cell("two\nlines")).isEqualTo("\"two\nlines\"");
        assertThat(CsvWriter.cell(null)).isEmpty();
    }

    @Test
    void neutralisesFormulaPrefixes() {
        assertThat(CsvWriter.cell("=1+1")).isEqualTo("'=1+1");
        assertThat(CsvWriter.cell("+cmd")).isEqualTo("'+cmd");
        assertThat(CsvWriter.cell("-2+3")).isEqualTo("'-2+3");
        assertThat(CsvWriter.cell("@SUM(A1)")).isEqualTo("'@SUM(A1)");
        assertThat(CsvWriter.cell("\tx")).isEqualTo("'\tx");
    }

    @Test
    void numbersStayNumeric() {
        assertThat(CsvWriter.cell(new BigDecimal("-12.50"))).isEqualTo("-12.50");
        assertThat(CsvWriter.cell(42L)).isEqualTo("42");
    }

    @Test
    void rowsUseCrLf() {
        assertThat(new CsvWriter().row("a", 1).row("b", 2).toString()).isEqualTo("a,1\r\nb,2\r\n");
        assertThat(new CsvWriter().row("c", null, 3).toString()).isEqualTo("c,,3\r\n");
    }
}

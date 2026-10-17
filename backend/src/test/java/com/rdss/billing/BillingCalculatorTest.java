package com.rdss.billing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Worked examples for the invoice arithmetic (see docs). */
class BillingCalculatorTest {

    static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    @Test
    void simpleLineWithoutDiscountOrTax() {
        var l = BillingCalculator.line(d("1"), d("180.00"), d("0"), d("0"));
        assertThat(l.subtotal()).isEqualByComparingTo("180.00");
        assertThat(l.total()).isEqualByComparingTo("180.00");
    }

    /** 3 x 18.50 = 55.50; 10% discount = 5.55; taxable base 49.95 x 5% = 2.4975 -> 2.50; total 52.45. */
    @Test
    void discountThenTaxWithHalfUpRounding() {
        var l = BillingCalculator.line(d("3"), d("18.50"), d("10"), d("0.05"));
        assertThat(l.subtotal()).isEqualByComparingTo("55.50");
        assertThat(l.discount()).isEqualByComparingTo("5.55");
        assertThat(l.tax()).isEqualByComparingTo("2.50");
        assertThat(l.total()).isEqualByComparingTo("52.45");
    }

    /** Fractional quantities round per line: 0.5 x 95.01 = 47.505 -> 47.51. */
    @Test
    void fractionalQuantityRoundsHalfUp() {
        assertThat(BillingCalculator.line(d("0.5"), d("95.01"), d("0"), d("0")).total()).isEqualByComparingTo("47.51");
    }

    @Test
    void totalsAreSumsOfRoundedLines() {
        var a = BillingCalculator.line(d("1"), d("180.00"), d("0"), d("0"));
        var b = BillingCalculator.line(d("3"), d("18.50"), d("10"), d("0.05"));
        var t = BillingCalculator.totals(List.of(a, b));
        assertThat(t.subtotal()).isEqualByComparingTo("235.50");
        assertThat(t.discount()).isEqualByComparingTo("5.55");
        assertThat(t.tax()).isEqualByComparingTo("2.50");
        assertThat(t.total()).isEqualByComparingTo("232.45");
        assertThat(t.subtotal().subtract(t.discount()).add(t.tax())).isEqualByComparingTo(t.total());
    }

    @Test
    void fullDiscountGivesZero() {
        assertThat(BillingCalculator.line(d("2"), d("50"), d("100"), d("0.2")).total()).isEqualByComparingTo("0.00");
    }

    @Test
    void rejectsInvalidInputs() {
        assertThatThrownBy(() -> BillingCalculator.line(d("0"), d("1"), d("0"), d("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingCalculator.line(d("1"), d("-1"), d("0"), d("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingCalculator.line(d("1"), d("1"), d("101"), d("0"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BillingCalculator.line(d("1"), d("1"), d("0"), d("1"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invoicePaymentStatusTransitions() {
        Invoice inv = new Invoice("INV-T-1", 1L, null, "USD", null, 1L);
        inv.replaceLines(List.of(new Invoice.LineInput(null, "Consultation", d("1"), d("100.00"), d("0"), d("0"))),
                List.of(), null);
        inv.issue(java.time.LocalDate.of(2030, 1, 1), 30);
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.ISSUED);
        assertThat(inv.getDueDate()).isEqualTo(java.time.LocalDate.of(2030, 1, 31));
        inv.applyPayment(d("40.00"));
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PARTIALLY_PAID);
        assertThat(inv.outstanding()).isEqualByComparingTo("60.00");
        assertThatThrownBy(() -> inv.applyPayment(d("60.01"))).hasMessageContaining("exceeds the outstanding balance");
        inv.applyPayment(d("60.00"));
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PAID);
        inv.applyPayment(d("-60.00"));
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PARTIALLY_PAID);
        assertThatThrownBy(() -> inv.voidInvoice("x")).hasMessageContaining("Reverse the recorded payments");
        assertThatThrownBy(() -> inv.replaceLines(List.of(), List.of(), null)).hasMessageContaining("only drafts");
    }

    @Test
    void zeroValueInvoiceIsPaidOnIssue() {
        Invoice inv = new Invoice("INV-T-2", 1L, null, "USD", null, 1L);
        inv.replaceLines(List.of(new Invoice.LineInput(null, "Waived", d("1"), d("50"), d("100"), d("0"))), List.of(), null);
        inv.issue(java.time.LocalDate.of(2030, 1, 1), 30);
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.PAID);
    }

    @Test
    void emptyInvoiceCannotBeIssued() {
        Invoice inv = new Invoice("INV-T-3", 1L, null, "USD", null, 1L);
        assertThatThrownBy(() -> inv.issue(java.time.LocalDate.now(), 30)).hasMessageContaining("at least one line");
    }
}

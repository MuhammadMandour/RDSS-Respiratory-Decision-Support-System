package com.rdss.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Pure money arithmetic. Every amount is rounded to 2 decimals HALF_UP per line, and invoice
 * totals are the sum of the rounded lines, so a printed invoice always adds up.
 * <pre>
 *   subtotal = qty x unitPrice
 *   discount = subtotal x discountPercent / 100
 *   tax      = (subtotal - discount) x taxRate
 *   total    = subtotal - discount + tax
 * </pre>
 */
public final class BillingCalculator {

    public static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);
    private static final BigDecimal HUNDRED = new BigDecimal("100");

    public record LineAmounts(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
    }

    public record Totals(BigDecimal subtotal, BigDecimal discount, BigDecimal tax, BigDecimal total) {
    }

    private BillingCalculator() {
    }

    public static LineAmounts line(BigDecimal quantity, BigDecimal unitPrice, BigDecimal discountPercent,
                                   BigDecimal taxRate) {
        if (quantity.signum() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        if (unitPrice.signum() < 0) {
            throw new IllegalArgumentException("unit price cannot be negative");
        }
        if (discountPercent.signum() < 0 || discountPercent.compareTo(HUNDRED) > 0) {
            throw new IllegalArgumentException("discount must be between 0 and 100 percent");
        }
        if (taxRate.signum() < 0 || taxRate.compareTo(BigDecimal.ONE) >= 0) {
            throw new IllegalArgumentException("tax rate must be a fraction in [0, 1)");
        }
        BigDecimal subtotal = money(quantity.multiply(unitPrice));
        BigDecimal discount = money(subtotal.multiply(discountPercent).divide(HUNDRED, 10, RoundingMode.HALF_UP));
        BigDecimal tax = money(subtotal.subtract(discount).multiply(taxRate));
        return new LineAmounts(subtotal, discount, tax, subtotal.subtract(discount).add(tax));
    }

    public static Totals totals(List<LineAmounts> lines) {
        BigDecimal s = ZERO;
        BigDecimal d = ZERO;
        BigDecimal t = ZERO;
        BigDecimal total = ZERO;
        for (LineAmounts l : lines) {
            s = s.add(l.subtotal());
            d = d.add(l.discount());
            t = t.add(l.tax());
            total = total.add(l.total());
        }
        return new Totals(s, d, t, total);
    }

    public static BigDecimal money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP);
    }
}

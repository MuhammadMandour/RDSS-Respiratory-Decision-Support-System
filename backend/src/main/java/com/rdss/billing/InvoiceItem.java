package com.rdss.billing;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "invoice_item")
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(name = "service_id")
    private Long serviceId;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(name = "discount_percent", nullable = false)
    private BigDecimal discountPercent;

    @Column(name = "tax_rate", nullable = false)
    private BigDecimal taxRate;

    @Column(name = "line_subtotal", nullable = false)
    private BigDecimal lineSubtotal;

    @Column(name = "line_discount", nullable = false)
    private BigDecimal lineDiscount;

    @Column(name = "line_tax", nullable = false)
    private BigDecimal lineTax;

    @Column(name = "line_total", nullable = false)
    private BigDecimal lineTotal;

    protected InvoiceItem() {
    }

    InvoiceItem(Invoice invoice, Long serviceId, String description, BigDecimal quantity, BigDecimal unitPrice,
                BigDecimal discountPercent, BigDecimal taxRate) {
        this.invoice = invoice;
        this.serviceId = serviceId;
        this.description = description;
        this.quantity = quantity;
        this.unitPrice = BillingCalculator.money(unitPrice);
        this.discountPercent = discountPercent;
        this.taxRate = taxRate;
        BillingCalculator.LineAmounts a = amounts();
        this.lineSubtotal = a.subtotal();
        this.lineDiscount = a.discount();
        this.lineTax = a.tax();
        this.lineTotal = a.total();
    }

    BillingCalculator.LineAmounts amounts() {
        return BillingCalculator.line(quantity, unitPrice, discountPercent, taxRate);
    }

    public Long getId() { return id; }
    public Long getServiceId() { return serviceId; }
    public String getDescription() { return description; }
    public BigDecimal getQuantity() { return quantity; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public BigDecimal getDiscountPercent() { return discountPercent; }
    public BigDecimal getTaxRate() { return taxRate; }
    public BigDecimal getLineSubtotal() { return lineSubtotal; }
    public BigDecimal getLineDiscount() { return lineDiscount; }
    public BigDecimal getLineTax() { return lineTax; }
    public BigDecimal getLineTotal() { return lineTotal; }
}

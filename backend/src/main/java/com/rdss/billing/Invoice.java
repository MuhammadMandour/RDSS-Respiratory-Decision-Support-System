package com.rdss.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;

import org.hibernate.annotations.ListIndexBase;

/**
 * Invoice aggregate. Drafts are editable; once issued the lines and diagnosis codes are frozen
 * and only payments (and voiding, if nothing was paid) change the invoice.
 */
@Entity
@Table(name = "invoice")
public class Invoice {

    public enum Status { DRAFT, ISSUED, PARTIALLY_PAID, PAID, VOID }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_number", nullable = false, updatable = false)
    private String invoiceNumber;

    @Column(name = "patient_id", nullable = false, updatable = false)
    private Long patientId;

    @Column(name = "encounter_id")
    private Long encounterId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.DRAFT;

    /** ISO 4217 code, stored as CHAR(3). */
    @Column(nullable = false, updatable = false, columnDefinition = "char(3)")
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    private String currency;

    @Column(name = "issue_date")
    private LocalDate issueDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(nullable = false)
    private BigDecimal subtotal = BillingCalculator.ZERO;

    @Column(name = "discount_total", nullable = false)
    private BigDecimal discountTotal = BillingCalculator.ZERO;

    @Column(name = "tax_total", nullable = false)
    private BigDecimal taxTotal = BillingCalculator.ZERO;

    @Column(nullable = false)
    private BigDecimal total = BillingCalculator.ZERO;

    @Column(name = "amount_paid", nullable = false)
    private BigDecimal amountPaid = BillingCalculator.ZERO;

    private String notes;

    @Column(name = "void_reason")
    private String voidReason;

    @Column(name = "created_by", nullable = false, updatable = false)
    private Long createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "issued_at")
    private Instant issuedAt;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("id")
    private List<InvoiceItem> items = new ArrayList<>();

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "invoice_diagnosis", joinColumns = @JoinColumn(name = "invoice_id"))
    @OrderColumn(name = "sequence_no", nullable = false)
    @ListIndexBase(1)
    @Column(name = "icd10_code", nullable = false)
    private List<String> diagnosisCodes = new ArrayList<>();

    protected Invoice() {
    }

    public Invoice(String invoiceNumber, Long patientId, Long encounterId, String currency, String notes,
                   Long createdBy) {
        this.invoiceNumber = invoiceNumber;
        this.patientId = patientId;
        this.encounterId = encounterId;
        this.currency = currency;
        this.notes = notes;
        this.createdBy = createdBy;
        this.createdAt = this.updatedAt = Instant.now();
    }

    // ---- drafting ----

    public void requireDraft() {
        if (status != Status.DRAFT) {
            throw new ConflictException("Invoice " + invoiceNumber + " is " + status + "; only drafts can be edited");
        }
    }

    public void replaceLines(List<LineInput> lines, List<String> codes, String notes) {
        requireDraft();
        items.clear();
        for (LineInput l : lines) {
            items.add(new InvoiceItem(this, l.serviceId(), l.description(), l.quantity(), l.unitPrice(),
                    l.discountPercent(), l.taxRate()));
        }
        diagnosisCodes.clear();
        diagnosisCodes.addAll(codes);
        this.notes = notes;
        recalculate();
    }

    public record LineInput(Long serviceId, String description, BigDecimal quantity, BigDecimal unitPrice,
                            BigDecimal discountPercent, BigDecimal taxRate) {
    }

    private void recalculate() {
        var t = BillingCalculator.totals(items.stream().map(InvoiceItem::amounts).toList());
        subtotal = t.subtotal();
        discountTotal = t.discount();
        taxTotal = t.tax();
        total = t.total();
    }

    // ---- lifecycle ----

    public void issue(LocalDate today, int paymentTermsDays) {
        requireDraft();
        if (items.isEmpty()) {
            throw new BusinessRuleException("An invoice needs at least one line before it can be issued");
        }
        issueDate = today;
        dueDate = today.plusDays(paymentTermsDays);
        issuedAt = Instant.now();
        status = total.signum() == 0 ? Status.PAID : Status.ISSUED;
    }

    public void voidInvoice(String reason) {
        if (status == Status.VOID) {
            throw new ConflictException("Invoice " + invoiceNumber + " is already void");
        }
        if (amountPaid.signum() > 0) {
            throw new ConflictException("Reverse the recorded payments before voiding invoice " + invoiceNumber);
        }
        status = Status.VOID;
        voidReason = reason;
        voidedAt = Instant.now();
    }

    public BigDecimal outstanding() {
        return total.subtract(amountPaid);
    }

    /** Applies a payment (positive) or a reversal (negative) and updates the status. */
    public void applyPayment(BigDecimal delta) {
        if (status == Status.DRAFT || status == Status.VOID) {
            throw new ConflictException("Payments can only be recorded against issued invoices");
        }
        BigDecimal next = amountPaid.add(delta);
        if (next.signum() < 0 || next.compareTo(total) > 0) {
            throw new BusinessRuleException("Payment of " + delta + " " + currency + " exceeds the outstanding balance of "
                    + outstanding() + " " + currency);
        }
        amountPaid = BillingCalculator.money(next);
        status = amountPaid.compareTo(total) == 0 ? Status.PAID
                : amountPaid.signum() == 0 ? Status.ISSUED : Status.PARTIALLY_PAID;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getInvoiceNumber() { return invoiceNumber; }
    public Long getPatientId() { return patientId; }
    public Long getEncounterId() { return encounterId; }
    public Status getStatus() { return status; }
    public String getCurrency() { return currency; }
    public LocalDate getIssueDate() { return issueDate; }
    public LocalDate getDueDate() { return dueDate; }
    public BigDecimal getSubtotal() { return subtotal; }
    public BigDecimal getDiscountTotal() { return discountTotal; }
    public BigDecimal getTaxTotal() { return taxTotal; }
    public BigDecimal getTotal() { return total; }
    public BigDecimal getAmountPaid() { return amountPaid; }
    public String getNotes() { return notes; }
    public String getVoidReason() { return voidReason; }
    public Long getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getVoidedAt() { return voidedAt; }
    public long getVersion() { return version; }
    public List<InvoiceItem> getItems() { return items; }
    public List<String> getDiagnosisCodes() { return diagnosisCodes; }
}

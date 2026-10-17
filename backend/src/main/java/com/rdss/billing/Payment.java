package com.rdss.billing;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import com.rdss.common.error.ConflictException;

/** A recorded payment. RDSS does not process card payments; SIMULATED payments move no money. */
@Entity
@Table(name = "payment")
public class Payment {

    public enum Method { CASH, CARD, BANK_TRANSFER, INSURANCE, SIMULATED }

    public enum Status { COMPLETED, REVERSED }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "invoice_id", nullable = false, updatable = false)
    private Long invoiceId;

    @Column(nullable = false, updatable = false)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private Method method;

    @Column(updatable = false)
    private String reference;

    @Column(nullable = false, updatable = false)
    private boolean simulated;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.COMPLETED;

    @Column(name = "reversal_reason")
    private String reversalReason;

    @Column(name = "paid_at", nullable = false, updatable = false)
    private Instant paidAt;

    @Column(name = "recorded_by", nullable = false, updatable = false)
    private Long recordedBy;

    protected Payment() {
    }

    public Payment(Long invoiceId, BigDecimal amount, Method method, String reference, Long recordedBy) {
        this.invoiceId = invoiceId;
        this.amount = BillingCalculator.money(amount);
        this.method = method;
        this.reference = reference;
        this.simulated = method == Method.SIMULATED;
        this.recordedBy = recordedBy;
        this.paidAt = Instant.now();
    }

    public void reverse(String reason) {
        if (status == Status.REVERSED) {
            throw new ConflictException("Payment " + id + " is already reversed");
        }
        status = Status.REVERSED;
        reversalReason = reason;
    }

    public Long getId() { return id; }
    public Long getInvoiceId() { return invoiceId; }
    public BigDecimal getAmount() { return amount; }
    public Method getMethod() { return method; }
    public String getReference() { return reference; }
    public boolean isSimulated() { return simulated; }
    public Status getStatus() { return status; }
    public String getReversalReason() { return reversalReason; }
    public Instant getPaidAt() { return paidAt; }
    public Long getRecordedBy() { return recordedBy; }
}

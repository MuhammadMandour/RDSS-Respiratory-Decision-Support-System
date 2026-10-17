package com.rdss.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class BillingDtos {

    private BillingDtos() {
    }

    public static final String SIMULATION_NOTICE = "SIMULATED PAYMENT - demonstration only. No payment gateway was "
            + "contacted and no money was transferred.";

    public record ServiceRequest(@NotBlank @Pattern(regexp = "^[A-Z0-9-]{2,30}$") String code,
                                 @NotBlank @Size(max = 200) String name,
                                 @NotNull BillableService.Category category,
                                 @NotNull @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
                                 boolean taxable, Boolean active) {
    }

    public record ServiceResponse(Long id, String code, String name, BillableService.Category category,
                                  BigDecimal unitPrice, boolean taxable, boolean active) {
        static ServiceResponse from(BillableService s) {
            return new ServiceResponse(s.getId(), s.getCode(), s.getName(), s.getCategory(), s.getUnitPrice(),
                    s.isTaxable(), s.isActive());
        }
    }

    /** Either a catalogue service (serviceId) or a free-text line (description + unitPrice). */
    public record LineRequest(Long serviceId,
                              @Size(max = 255) String description,
                              @DecimalMin("0.00") @Digits(integer = 10, fraction = 2) BigDecimal unitPrice,
                              @NotNull @DecimalMin("0.01") @Digits(integer = 8, fraction = 2) BigDecimal quantity,
                              @DecimalMin("0.00") @DecimalMax("100.00") BigDecimal discountPercent,
                              Boolean taxable) {

        @AssertTrue(message = "each line needs a serviceId, or a description and unit price")
        public boolean isComplete() {
            return serviceId != null || (description != null && !description.isBlank() && unitPrice != null);
        }
    }

    public record InvoiceRequest(Long patientId, Long encounterId,
                                 @NotNull @Size(max = 100) @Valid List<LineRequest> lines,
                                 @Size(max = 12) List<@Pattern(regexp = "^[A-Z][0-9][0-9A-Z](\\.[0-9A-Z]{1,4})?$") String> diagnosisCodes,
                                 @Size(max = 1000) String notes,
                                 /** Required when updating a draft. */
                                 Long version) {
    }

    public record PaymentRequest(@NotNull @DecimalMin("0.01") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                 @NotNull Payment.Method method, @Size(max = 100) String reference) {
        @AssertTrue(message = "use the simulated-payment endpoint for simulated payments")
        public boolean isNotSimulated() {
            return method != Payment.Method.SIMULATED;
        }
    }

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {
    }

    public record LineResponse(Long id, Long serviceId, String description, BigDecimal quantity, BigDecimal unitPrice,
                               BigDecimal discountPercent, BigDecimal taxRate, BigDecimal lineSubtotal,
                               BigDecimal lineDiscount, BigDecimal lineTax, BigDecimal lineTotal) {
    }

    public record DiagnosisCode(int sequence, String code, String description) {
    }

    public record PaymentResponse(Long id, BigDecimal amount, Payment.Method method, String reference, boolean simulated,
                                  Payment.Status status, String reversalReason, Instant paidAt, String recordedByName) {
    }

    public record InvoiceSummary(Long id, String invoiceNumber, Long patientId, String patientName, String patientMrn,
                                 Invoice.Status status, LocalDate issueDate, LocalDate dueDate, String currency,
                                 BigDecimal total, BigDecimal amountPaid, BigDecimal outstanding, boolean overdue) {
    }

    public record InvoiceResponse(InvoiceSummary summary, Long encounterId, BigDecimal subtotal, BigDecimal discountTotal,
                                  BigDecimal taxTotal, String notes, String voidReason, Instant createdAt,
                                  Instant issuedAt, Instant voidedAt, long version, List<LineResponse> lines,
                                  List<DiagnosisCode> diagnoses, List<PaymentResponse> payments, String clinicName) {
    }

    public record SimulatedPaymentResponse(InvoiceResponse invoice, String notice) {
    }
}

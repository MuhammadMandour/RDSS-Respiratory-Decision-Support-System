package com.rdss.billing;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;

import com.rdss.billing.BillingDtos.InvoiceRequest;
import com.rdss.billing.BillingDtos.InvoiceResponse;
import com.rdss.billing.BillingDtos.InvoiceSummary;
import com.rdss.billing.BillingDtos.PaymentRequest;
import com.rdss.billing.BillingDtos.ReasonRequest;
import com.rdss.billing.BillingDtos.ServiceRequest;
import com.rdss.billing.BillingDtos.ServiceResponse;
import com.rdss.billing.BillingDtos.SimulatedPaymentResponse;
import com.rdss.common.PageResponse;
import com.rdss.common.Pages;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "Billing")
public class BillingController {

    private final InvoiceService invoices;
    private final CatalogueService catalogue;

    public BillingController(InvoiceService invoices, CatalogueService catalogue) {
        this.invoices = invoices;
        this.catalogue = catalogue;
    }

    @GetMapping("/billing/services")
    public List<ServiceResponse> services() {
        return catalogue.list();
    }

    @PostMapping("/billing/services")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceResponse createService(@Valid @RequestBody ServiceRequest request) {
        return catalogue.create(request);
    }

    @PutMapping("/billing/services/{id}")
    public ServiceResponse updateService(@PathVariable Long id, @Valid @RequestBody ServiceRequest request) {
        return catalogue.update(id, request);
    }

    @GetMapping("/invoices")
    @Operation(summary = "Search invoices (patients: own issued invoices; clinicians: care-team patients)")
    public PageResponse<InvoiceSummary> search(
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) Invoice.Status status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "25") int size) {
        return invoices.search(patientId, status, from, to, q,
                Pages.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
    }

    @GetMapping("/invoices/{id}")
    public InvoiceResponse get(@PathVariable Long id) {
        return invoices.get(id);
    }

    @PostMapping("/invoices")
    @ResponseStatus(HttpStatus.CREATED)
    public InvoiceResponse create(@Valid @RequestBody InvoiceRequest request) {
        return invoices.create(request);
    }

    @PutMapping("/invoices/{id}")
    public InvoiceResponse update(@PathVariable Long id, @Valid @RequestBody InvoiceRequest request) {
        return invoices.updateDraft(id, request);
    }

    @PostMapping("/invoices/{id}/issue")
    public InvoiceResponse issue(@PathVariable Long id) {
        return invoices.issue(id);
    }

    @PostMapping("/invoices/{id}/void")
    public InvoiceResponse voidInvoice(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return invoices.voidInvoice(id, request.reason());
    }

    @PostMapping("/invoices/{id}/payments")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Record a payment received outside RDSS (administrators)")
    public InvoiceResponse recordPayment(@PathVariable Long id, @Valid @RequestBody PaymentRequest request) {
        return invoices.recordPayment(id, request);
    }

    @PostMapping("/invoices/{id}/simulated-payment")
    @Operation(summary = "SIMULATED payment of the outstanding balance - demonstration only, no gateway")
    public SimulatedPaymentResponse simulatedPayment(@PathVariable Long id) {
        return invoices.simulatePayment(id);
    }

    @PostMapping("/payments/{id}/reverse")
    public InvoiceResponse reversePayment(@PathVariable Long id, @Valid @RequestBody ReasonRequest request) {
        return invoices.reversePayment(id, request.reason());
    }
}

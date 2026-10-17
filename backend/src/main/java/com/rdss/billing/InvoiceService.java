package com.rdss.billing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.billing.BillingDtos.DiagnosisCode;
import com.rdss.billing.BillingDtos.InvoiceRequest;
import com.rdss.billing.BillingDtos.InvoiceResponse;
import com.rdss.billing.BillingDtos.InvoiceSummary;
import com.rdss.billing.BillingDtos.LineRequest;
import com.rdss.billing.BillingDtos.LineResponse;
import com.rdss.billing.BillingDtos.PaymentRequest;
import com.rdss.billing.BillingDtos.PaymentResponse;
import com.rdss.billing.BillingDtos.SimulatedPaymentResponse;
import com.rdss.common.PageResponse;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;
import com.rdss.common.security.Role;
import com.rdss.diagnoses.Icd10CodeRepository;
import com.rdss.diagnoses.Icd10Service;
import com.rdss.encounters.ClinicalEncounterRepository;
import com.rdss.patients.Patient;
import com.rdss.patients.PatientAccessPolicy;
import com.rdss.patients.PatientAccessPolicy.Access;
import com.rdss.patients.PatientRepository;
import com.rdss.patients.PatientService;
import com.rdss.system.SettingsService;
import com.rdss.users.AppUser;
import com.rdss.users.AppUserRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Invoicing and payment recording. No payment gateway is integrated: staff record payments that
 * happened elsewhere, and patients can run a clearly labelled SIMULATED payment for demos.
 */
@Service
public class InvoiceService {

    static final Set<Invoice.Status> PATIENT_VISIBLE =
            EnumSet.of(Invoice.Status.ISSUED, Invoice.Status.PARTIALLY_PAID, Invoice.Status.PAID, Invoice.Status.VOID);

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final BillableServiceRepository catalogue;
    private final Icd10Service icd10;
    private final Icd10CodeRepository icd10Codes;
    private final ClinicalEncounterRepository encounters;
    private final PatientRepository patients;
    private final PatientService patientService;
    private final AppUserRepository users;
    private final PatientAccessPolicy access;
    private final SettingsService settings;
    private final AuditService audit;

    @SuppressWarnings("checkstyle:ParameterNumber")
    public InvoiceService(InvoiceRepository invoices, PaymentRepository payments, BillableServiceRepository catalogue,
                          Icd10Service icd10, Icd10CodeRepository icd10Codes, ClinicalEncounterRepository encounters,
                          PatientRepository patients, PatientService patientService, AppUserRepository users,
                          PatientAccessPolicy access, SettingsService settings, AuditService audit) {
        this.invoices = invoices;
        this.payments = payments;
        this.catalogue = catalogue;
        this.icd10 = icd10;
        this.icd10Codes = icd10Codes;
        this.encounters = encounters;
        this.patients = patients;
        this.patientService = patientService;
        this.users = users;
        this.access = access;
        this.settings = settings;
        this.audit = audit;
    }

    // ---- drafting ----

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public InvoiceResponse create(InvoiceRequest req) {
        if (req.patientId() == null) {
            throw new BusinessRuleException("patientId is required");
        }
        access.require(req.patientId(), Access.DEMOGRAPHICS_WRITE);
        validateEncounter(req.patientId(), req.encounterId());
        String number = settings.get("billing.invoice_prefix", "INV") + "-" + LocalDate.now().getYear() + "-"
                + String.format("%06d", invoices.nextInvoiceNumber());
        Invoice inv = new Invoice(number, req.patientId(), req.encounterId(),
                settings.get("billing.currency", "USD"), trim(req.notes()), CurrentUser.require().id());
        inv.replaceLines(lines(req.lines()), codes(req.diagnosisCodes()), trim(req.notes()));
        inv = invoices.save(inv);
        audit.record("CREATE", "Invoice", inv.getId(), inv.getPatientId(), Outcome.SUCCESS, inv.getInvoiceNumber());
        return toResponse(inv);
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public InvoiceResponse updateDraft(Long id, InvoiceRequest req) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_WRITE);
        if (req.version() == null || req.version() != inv.getVersion()) {
            throw new ConflictException("The invoice was modified by someone else; reload and retry");
        }
        validateEncounter(inv.getPatientId(), req.encounterId());
        inv.replaceLines(lines(req.lines()), codes(req.diagnosisCodes()), trim(req.notes()));
        invoices.saveAndFlush(inv);
        audit.success("UPDATE", "Invoice", id, inv.getPatientId());
        return toResponse(inv);
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional
    public InvoiceResponse issue(Long id) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_WRITE);
        inv.issue(LocalDate.now(settings.clinicZone()), settings.getInt("billing.payment_terms_days", 30));
        audit.record("ISSUE", "Invoice", id, inv.getPatientId(), Outcome.SUCCESS, "total=" + inv.getTotal());
        return toResponse(inv);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public InvoiceResponse voidInvoice(Long id, String reason) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_WRITE);
        inv.voidInvoice(reason.trim());
        audit.record("VOID", "Invoice", id, inv.getPatientId(), Outcome.SUCCESS, null);
        return toResponse(inv);
    }

    // ---- payments ----

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public InvoiceResponse recordPayment(Long id, PaymentRequest req) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_WRITE);
        inv.applyPayment(BillingCalculator.money(req.amount()));
        Payment p = payments.save(new Payment(id, req.amount(), req.method(), trim(req.reference()),
                CurrentUser.require().id()));
        audit.record("PAYMENT", "Invoice", id, inv.getPatientId(), Outcome.SUCCESS,
                "payment=" + p.getId() + " amount=" + p.getAmount() + " method=" + p.getMethod());
        return toResponse(inv);
    }

    /**
     * Demonstration-only payment of the full outstanding balance. Clearly labelled; no gateway.
     * Patients may only simulate payment of their own issued invoices.
     */
    @Transactional
    public SimulatedPaymentResponse simulatePayment(Long id) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_READ);
        BigDecimal due = inv.outstanding();
        if (due.signum() <= 0) {
            throw new ConflictException("Invoice " + inv.getInvoiceNumber() + " has no outstanding balance");
        }
        inv.applyPayment(due);
        String ref = "SIM-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        payments.save(new Payment(id, due, Payment.Method.SIMULATED, ref, CurrentUser.require().id()));
        audit.record("SIMULATED_PAYMENT", "Invoice", id, inv.getPatientId(), Outcome.SUCCESS, "amount=" + due);
        return new SimulatedPaymentResponse(toResponse(inv), BillingDtos.SIMULATION_NOTICE);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public InvoiceResponse reversePayment(Long paymentId, String reason) {
        Payment p = payments.findById(paymentId).orElseThrow(() -> NotFoundException.of("Payment", paymentId));
        Invoice inv = requireInvoice(p.getInvoiceId(), Access.DEMOGRAPHICS_WRITE);
        p.reverse(reason.trim());
        inv.applyPayment(p.getAmount().negate());
        audit.record("REVERSE_PAYMENT", "Invoice", inv.getId(), inv.getPatientId(), Outcome.SUCCESS,
                "payment=" + paymentId);
        return toResponse(inv);
    }

    // ---- queries ----

    @Transactional(readOnly = true)
    public InvoiceResponse get(Long id) {
        Invoice inv = requireInvoice(id, Access.DEMOGRAPHICS_READ);
        audit.success("READ", "Invoice", id, inv.getPatientId());
        return toResponse(inv);
    }

    @Transactional(readOnly = true)
    public PageResponse<InvoiceSummary> search(Long patientId, Invoice.Status status, LocalDate from, LocalDate to,
                                               String query, Pageable pageable) {
        AuthenticatedUser user = CurrentUser.require();
        Set<Invoice.Status> statuses = status == null ? EnumSet.allOf(Invoice.Status.class) : EnumSet.of(status);
        String q = query == null || query.isBlank() ? null : "%" + query.trim().toLowerCase(Locale.ROOT) + "%";
        Page<Invoice> page = switch (user.role()) {
            case PATIENT -> {
                Set<Invoice.Status> visible = EnumSet.copyOf(statuses);
                visible.retainAll(PATIENT_VISIBLE);
                yield invoices.search(patientService.requireOwnPatientId(),
                        visible.isEmpty() ? PATIENT_VISIBLE : visible, from, to, q, pageable);
            }
            case CLINICIAN -> invoices.searchForClinician(user.id(), patientId, statuses, from, to, q, pageable);
            case ADMIN -> invoices.search(patientId, statuses, from, to, q, pageable);
        };
        Map<Long, Patient> pts = patients.findAllById(page.getContent().stream().map(Invoice::getPatientId).distinct()
                .toList()).stream().collect(Collectors.toMap(Patient::getId, Function.identity()));
        return PageResponse.of(page, i -> summary(i, pts.get(i.getPatientId())));
    }

    // ---- helpers ----

    private Invoice requireInvoice(Long id, Access level) {
        Invoice inv = invoices.findById(id).orElseThrow(() -> NotFoundException.of("Invoice", id));
        AuthenticatedUser user = CurrentUser.require();
        access.require(inv.getPatientId(), user.is(Role.PATIENT) ? Access.DEMOGRAPHICS_READ : level);
        if (user.is(Role.PATIENT) && !PATIENT_VISIBLE.contains(inv.getStatus())) {
            throw NotFoundException.of("Invoice", id);
        }
        return inv;
    }

    private void validateEncounter(Long patientId, Long encounterId) {
        if (encounterId != null) {
            var e = encounters.findById(encounterId).orElseThrow(() -> NotFoundException.of("Encounter", encounterId));
            if (!e.getPatientId().equals(patientId)) {
                throw new BusinessRuleException("Encounter " + encounterId + " does not belong to this patient");
            }
        }
    }

    private List<Invoice.LineInput> lines(List<LineRequest> requests) {
        BigDecimal defaultTax = settings.getDecimal("billing.default_tax_rate", BigDecimal.ZERO);
        List<Invoice.LineInput> out = new ArrayList<>();
        for (LineRequest r : requests) {
            BigDecimal discount = r.discountPercent() == null ? BigDecimal.ZERO : r.discountPercent();
            if (r.serviceId() != null) {
                BillableService s = catalogue.findById(r.serviceId())
                        .orElseThrow(() -> NotFoundException.of("Billing service", r.serviceId()));
                if (!s.isActive()) {
                    throw new BusinessRuleException("Billing service " + s.getCode() + " is inactive");
                }
                // Catalogue price applies unless an explicit override is given.
                BigDecimal price = r.unitPrice() != null ? r.unitPrice() : s.getUnitPrice();
                String description = r.description() == null || r.description().isBlank() ? s.getName() : r.description().trim();
                out.add(new Invoice.LineInput(s.getId(), description, r.quantity(), price, discount,
                        s.isTaxable() ? defaultTax : BigDecimal.ZERO));
            } else {
                out.add(new Invoice.LineInput(null, r.description().trim(), r.quantity(), r.unitPrice(), discount,
                        Boolean.TRUE.equals(r.taxable()) ? defaultTax : BigDecimal.ZERO));
            }
        }
        return out;
    }

    private List<String> codes(List<String> requested) {
        if (requested == null) {
            return List.of();
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String c : requested) {
            String code = c.trim().toUpperCase(Locale.ROOT);
            icd10.requireUsable(code);
            if (!unique.add(code)) {
                throw new BusinessRuleException("Diagnosis code " + code + " is listed more than once");
            }
        }
        return List.copyOf(unique);
    }

    private InvoiceSummary summary(Invoice i, Patient p) {
        boolean overdue = i.getDueDate() != null && i.outstanding().signum() > 0
                && (i.getStatus() == Invoice.Status.ISSUED || i.getStatus() == Invoice.Status.PARTIALLY_PAID)
                && i.getDueDate().isBefore(LocalDate.now(settings.clinicZone()));
        return new InvoiceSummary(i.getId(), i.getInvoiceNumber(), i.getPatientId(), p == null ? null : p.fullName(),
                p == null ? null : p.getMrn(), i.getStatus(), i.getIssueDate(), i.getDueDate(), i.getCurrency(),
                i.getTotal(), i.getAmountPaid(), i.outstanding(), overdue);
    }

    private InvoiceResponse toResponse(Invoice i) {
        Patient p = patients.findById(i.getPatientId()).orElse(null);
        List<LineResponse> lines = i.getItems().stream().map(l -> new LineResponse(l.getId(), l.getServiceId(),
                l.getDescription(), l.getQuantity(), l.getUnitPrice(), l.getDiscountPercent(), l.getTaxRate(),
                l.getLineSubtotal(), l.getLineDiscount(), l.getLineTax(), l.getLineTotal())).toList();
        List<DiagnosisCode> dx = new ArrayList<>();
        for (int n = 0; n < i.getDiagnosisCodes().size(); n++) {
            String code = i.getDiagnosisCodes().get(n);
            dx.add(new DiagnosisCode(n + 1, code, icd10Codes.findById(code).map(c -> c.getDescription()).orElse(null)));
        }
        List<Payment> ps = payments.findByInvoiceIdOrderByPaidAtAsc(i.getId());
        Map<Long, String> names = users.findAllById(ps.stream().map(Payment::getRecordedBy).filter(Objects::nonNull)
                .distinct().toList()).stream().collect(Collectors.toMap(AppUser::getId, AppUser::getFullName));
        List<PaymentResponse> payResponses = ps.stream().map(x -> new PaymentResponse(x.getId(), x.getAmount(),
                x.getMethod(), x.getReference(), x.isSimulated(), x.getStatus(), x.getReversalReason(), x.getPaidAt(),
                names.get(x.getRecordedBy()))).toList();
        return new InvoiceResponse(summary(i, p), i.getEncounterId(), i.getSubtotal(), i.getDiscountTotal(),
                i.getTaxTotal(), i.getNotes(), i.getVoidReason(), i.getCreatedAt(), i.getIssuedAt(), i.getVoidedAt(),
                i.getVersion(), lines, dx, payResponses, settings.get("clinic.name", "RDSS"));
    }

    private static String trim(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}

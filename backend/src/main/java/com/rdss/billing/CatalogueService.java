package com.rdss.billing;

import java.util.List;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.billing.BillingDtos.ServiceRequest;
import com.rdss.billing.BillingDtos.ServiceResponse;
import com.rdss.common.error.ConflictException;
import com.rdss.common.error.NotFoundException;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Configurable service catalogue and prices. Price changes never alter existing invoice lines. */
@Service
public class CatalogueService {

    private final BillableServiceRepository repository;
    private final AuditService audit;

    public CatalogueService(BillableServiceRepository repository, AuditService audit) {
        this.repository = repository;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ServiceResponse> list() {
        return repository.findAllByOrderByCategoryAscNameAsc().stream().map(ServiceResponse::from).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ServiceResponse create(ServiceRequest req) {
        if (repository.findByCode(req.code()).isPresent()) {
            throw new ConflictException("A service with code " + req.code() + " already exists");
        }
        BillableService s = new BillableService(req.code(), req.name().trim(), req.category(),
                BillingCalculator.money(req.unitPrice()), req.taxable());
        if (req.active() != null && !req.active()) {
            s.update(s.getName(), s.getCategory(), s.getUnitPrice(), s.isTaxable(), false);
        }
        s = repository.save(s);
        audit.record("CREATE", "BillingService", s.getCode(), null, Outcome.SUCCESS, "price=" + s.getUnitPrice());
        return ServiceResponse.from(s);
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public ServiceResponse update(Long id, ServiceRequest req) {
        BillableService s = repository.findById(id).orElseThrow(() -> NotFoundException.of("Billing service", id));
        String old = s.getUnitPrice().toPlainString();
        s.update(req.name().trim(), req.category(), BillingCalculator.money(req.unitPrice()), req.taxable(),
                req.active() == null || req.active());
        audit.record("UPDATE", "BillingService", s.getCode(), null, Outcome.SUCCESS,
                "price " + old + " -> " + s.getUnitPrice());
        return ServiceResponse.from(s);
    }
}

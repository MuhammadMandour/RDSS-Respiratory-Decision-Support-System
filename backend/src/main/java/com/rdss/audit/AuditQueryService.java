package com.rdss.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuditQueryService {

    public record Criteria(String actor, String action, String resourceType, Long patientId,
                           AuditLog.Outcome outcome, Instant from, Instant to) {
    }

    private final AuditLogRepository repository;

    public AuditQueryService(AuditLogRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public Page<AuditLog> search(Criteria c, Pageable pageable) {
        Specification<AuditLog> spec = (root, q, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (c.actor() != null && !c.actor().isBlank()) {
                p.add(cb.equal(cb.lower(root.get("actorUsername")), c.actor().toLowerCase()));
            }
            if (c.action() != null && !c.action().isBlank()) {
                p.add(cb.equal(root.get("action"), c.action()));
            }
            if (c.resourceType() != null && !c.resourceType().isBlank()) {
                p.add(cb.equal(root.get("resourceType"), c.resourceType()));
            }
            if (c.patientId() != null) {
                p.add(cb.equal(root.get("patientId"), c.patientId()));
            }
            if (c.outcome() != null) {
                p.add(cb.equal(root.get("outcome"), c.outcome()));
            }
            if (c.from() != null) {
                p.add(cb.greaterThanOrEqualTo(root.get("occurredAt"), c.from()));
            }
            if (c.to() != null) {
                p.add(cb.lessThan(root.get("occurredAt"), c.to()));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return repository.findAll(spec, pageable);
    }
}

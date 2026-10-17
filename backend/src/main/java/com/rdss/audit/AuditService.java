package com.rdss.audit;

import java.time.Instant;

import jakarta.servlet.http.HttpServletRequest;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.common.security.AuthenticatedUser;
import com.rdss.common.security.CurrentUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the audit trail. Runs in its own transaction so that denied or failed operations
 * are recorded even when the caller's transaction rolls back. Never pass clinical content
 * or credentials as {@code detail}.
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private final AuditLogRepository repository;
    private final TransactionTemplate requiresNew;

    public AuditService(AuditLogRepository repository, PlatformTransactionManager txManager) {
        this.repository = repository;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void record(String action, String resourceType, Object resourceId, Long patientId,
                       Outcome outcome, String detail) {
        AuthenticatedUser user = CurrentUser.find().orElse(null);
        recordAs(user, action, resourceType, resourceId, patientId, outcome, detail);
    }

    public void recordAs(AuthenticatedUser user, String action, String resourceType, Object resourceId,
                         Long patientId, Outcome outcome, String detail) {
        try {
            var entry = new AuditLog(
                    Instant.now(),
                    user == null ? null : user.id(),
                    user == null ? null : user.username(),
                    user == null ? null : user.role().name(),
                    action,
                    resourceType,
                    resourceId == null ? null : String.valueOf(resourceId),
                    patientId,
                    outcome,
                    truncate(detail),
                    clientIp());
            requiresNew.executeWithoutResult(status -> repository.save(entry));
        } catch (RuntimeException ex) {
            // Auditing must never break the clinical workflow; surface it in logs for operators.
            log.error("Failed to write audit record action={} resource={}", action, resourceType, ex);
        }
    }

    public void success(String action, String resourceType, Object resourceId, Long patientId) {
        record(action, resourceType, resourceId, patientId, Outcome.SUCCESS, null);
    }

    public void denied(String action, String resourceType, Object resourceId, Long patientId) {
        record(action, resourceType, resourceId, patientId, Outcome.DENIED, null);
    }

    private static String truncate(String s) {
        return s == null || s.length() <= 500 ? s : s.substring(0, 500);
    }

    private static String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest req = attrs.getRequest();
            // X-Forwarded-For is resolved by server.forward-headers-strategy=framework.
            return req.getRemoteAddr();
        }
        return null;
    }
}

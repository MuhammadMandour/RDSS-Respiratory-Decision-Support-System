package com.rdss.audit;

import java.time.Instant;

import com.rdss.common.PageResponse;
import com.rdss.common.Pages;

import io.swagger.v3.oas.annotations.tags.Tag;

import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/audit")
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Audit")
public class AuditController {

    public record AuditLogResponse(Long id, Instant occurredAt, Long actorUserId, String actorUsername,
                                   String actorRole, String action, String resourceType, String resourceId,
                                   Long patientId, String outcome, String detail, String clientIp) {
        static AuditLogResponse from(AuditLog a) {
            return new AuditLogResponse(a.getId(), a.getOccurredAt(), a.getActorUserId(), a.getActorUsername(),
                    a.getActorRole(), a.getAction(), a.getResourceType(), a.getResourceId(), a.getPatientId(),
                    a.getOutcome().name(), a.getDetail(), a.getClientIp());
        }
    }

    private final AuditQueryService queryService;

    public AuditController(AuditQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping
    public PageResponse<AuditLogResponse> search(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) Long patientId,
            @RequestParam(required = false) AuditLog.Outcome outcome,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return PageResponse.of(queryService.search(new AuditQueryService.Criteria(actor, action, resourceType,
                patientId, outcome, from, to), Pages.of(page, size, Sort.by(Sort.Direction.DESC, "occurredAt"))),
                AuditLogResponse::from);
    }
}

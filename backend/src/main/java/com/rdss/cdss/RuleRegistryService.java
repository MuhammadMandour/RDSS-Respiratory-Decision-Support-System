package com.rdss.cdss;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.rdss.audit.AuditLog.Outcome;
import com.rdss.audit.AuditService;
import com.rdss.cdss.CdssDtos.GuidelineResponse;
import com.rdss.cdss.CdssDtos.RuleResponse;
import com.rdss.cdss.engine.CdssEngine;
import com.rdss.cdss.engine.ClinicalRule;
import com.rdss.cdss.model.CdssRuleDefinition;
import com.rdss.cdss.model.CdssRuleDefinitionRepository;
import com.rdss.cdss.model.GuidelineReference;
import com.rdss.cdss.model.GuidelineReferenceRepository;
import com.rdss.common.error.BusinessRuleException;
import com.rdss.common.error.NotFoundException;
import com.rdss.common.security.CurrentUser;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Rule registry: enablement and tunable parameters. Only parameters a rule declares may be changed. */
@Service
public class RuleRegistryService {

    private final CdssRuleDefinitionRepository definitions;
    private final GuidelineReferenceRepository guidelines;
    private final CdssEngine engine;
    private final AuditService audit;

    public RuleRegistryService(CdssRuleDefinitionRepository definitions, GuidelineReferenceRepository guidelines,
                               CdssEngine engine, AuditService audit) {
        this.definitions = definitions;
        this.guidelines = guidelines;
        this.engine = engine;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Map<String, CdssEngine.RuleConfig> currentConfig() {
        return definitions.findAll().stream().collect(Collectors.toMap(CdssRuleDefinition::getCode,
                d -> new CdssEngine.RuleConfig(d.isEnabled(), d.getParameters())));
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional(readOnly = true)
    public List<RuleResponse> list() {
        return definitions.findAllByOrderByCategoryAscCodeAsc().stream().map(this::toResponse).toList();
    }

    @PreAuthorize("hasAnyRole('ADMIN','CLINICIAN')")
    @Transactional(readOnly = true)
    public List<GuidelineResponse> guidelines() {
        return guidelines.findAllByOrderByCodeAsc().stream().map(RuleRegistryService::toGuideline).toList();
    }

    @PreAuthorize("hasRole('ADMIN')")
    @Transactional
    public RuleResponse update(String code, boolean enabled, Map<String, Object> parameters) {
        CdssRuleDefinition d = definitions.findByCode(code).orElseThrow(() -> NotFoundException.of("CDSS rule", code));
        Map<String, Object> merged = new HashMap<>(d.getParameters());
        if (parameters != null) {
            for (var e : parameters.entrySet()) {
                if (!d.getParameters().containsKey(e.getKey())) {
                    throw new BusinessRuleException("Parameter '" + e.getKey() + "' is not configurable for rule " + code);
                }
                if (!(e.getValue() instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 1000) {
                    throw new BusinessRuleException("Parameter '" + e.getKey() + "' must be a number between 0 and 1000");
                }
                merged.put(e.getKey(), e.getValue());
            }
        }
        d.configure(enabled, merged, CurrentUser.require().username());
        audit.record("CONFIGURE", "CdssRule", code, null, Outcome.SUCCESS,
                "enabled=" + enabled + " params=" + merged);
        return toResponse(d);
    }

    static GuidelineResponse toGuideline(GuidelineReference g) {
        return new GuidelineResponse(g.getCode(), g.getTitle(), g.getPublisher(), g.getVersion(), g.getPublishedYear(),
                g.getUrl(), g.getCitation());
    }

    private RuleResponse toResponse(CdssRuleDefinition d) {
        ClinicalRule impl = engine.rules().get(d.getCode());
        return new RuleResponse(d.getCode(), d.getName(), d.getCategory(), d.getDescription(), d.getRuleVersion(),
                impl == null ? null : impl.version(), d.isValidatedMethod(), d.isEnabled(), d.getParameters(),
                d.getGuidelines().stream().map(RuleRegistryService::toGuideline).toList(), d.getUpdatedAt(),
                d.getUpdatedBy());
    }
}

package com.rdss.cdss.model;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Registry row for a rule: metadata, enablement and tunable parameters. Logic lives in Java. */
@Entity
@Table(name = "cdss_rule")
public class CdssRuleDefinition {

    @Id
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String category;

    @Column(nullable = false)
    private String description;

    @Column(name = "rule_version", nullable = false)
    private String ruleVersion;

    @Column(name = "validated_method", nullable = false)
    private boolean validatedMethod;

    @Column(nullable = false)
    private boolean enabled;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private Map<String, Object> parameters = new HashMap<>();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "cdss_rule_guideline", joinColumns = @JoinColumn(name = "rule_id"),
            inverseJoinColumns = @JoinColumn(name = "guideline_id"))
    private Set<GuidelineReference> guidelines = new LinkedHashSet<>();

    protected CdssRuleDefinition() {
    }

    public void configure(boolean enabled, Map<String, Object> parameters, String by) {
        this.enabled = enabled;
        if (parameters != null) {
            this.parameters = new HashMap<>(parameters);
        }
        this.updatedBy = by;
        this.updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public String getRuleVersion() { return ruleVersion; }
    public boolean isValidatedMethod() { return validatedMethod; }
    public boolean isEnabled() { return enabled; }
    public Map<String, Object> getParameters() { return parameters; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getUpdatedBy() { return updatedBy; }
    public Set<GuidelineReference> getGuidelines() { return guidelines; }
}

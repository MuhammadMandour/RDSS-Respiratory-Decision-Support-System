package com.rdss.diagnoses;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Catalogue entry. Codes are kept separate from internal identifiers and stored in dotted form. */
@Entity
@Table(name = "icd10_code")
public class Icd10Code {

    @Id
    private String code;

    @Column(nullable = false)
    private String description;

    @Column(nullable = false)
    private boolean billable;

    @Column(name = "code_system", nullable = false)
    private String codeSystem;

    @Column(name = "code_version", nullable = false)
    private String codeVersion;

    @Column(nullable = false)
    private String source;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Icd10Code() {
    }

    public void setActive(boolean active) {
        this.active = active;
        this.updatedAt = Instant.now();
    }

    public String getCode() { return code; }
    public String getDescription() { return description; }
    public boolean isBillable() { return billable; }
    public String getCodeSystem() { return codeSystem; }
    public String getCodeVersion() { return codeVersion; }
    public String getSource() { return source; }
    public boolean isActive() { return active; }
    public Instant getUpdatedAt() { return updatedAt; }
}

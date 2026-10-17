package com.rdss.billing;

import java.math.BigDecimal;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/** Entry in the configurable service catalogue (table {@code billing_service}). */
@Entity
@Table(name = "billing_service")
public class BillableService {

    public enum Category { CONSULTATION, DIAGNOSTIC_TEST, IMAGING, PROCEDURE, THERAPY, OTHER }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, updatable = false)
    private String code;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Category category;

    @Column(name = "unit_price", nullable = false)
    private BigDecimal unitPrice;

    @Column(nullable = false)
    private boolean taxable;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected BillableService() {
    }

    public BillableService(String code, String name, Category category, BigDecimal unitPrice, boolean taxable) {
        this.code = code;
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.taxable = taxable;
    }

    public void update(String name, Category category, BigDecimal unitPrice, boolean taxable, boolean active) {
        this.name = name;
        this.category = category;
        this.unitPrice = unitPrice;
        this.taxable = taxable;
        this.active = active;
    }

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public Category getCategory() { return category; }
    public BigDecimal getUnitPrice() { return unitPrice; }
    public boolean isTaxable() { return taxable; }
    public boolean isActive() { return active; }
}

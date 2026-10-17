package com.rdss.cdss.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "guideline_reference")
public class GuidelineReference {

    @Id
    private Long id;

    @Column(nullable = false)
    private String code;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String publisher;

    @Column(nullable = false)
    private String version;

    @Column(name = "published_year")
    private Integer publishedYear;

    private String url;
    private String citation;

    protected GuidelineReference() {
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getTitle() { return title; }
    public String getPublisher() { return publisher; }
    public String getVersion() { return version; }
    public Integer getPublishedYear() { return publishedYear; }
    public String getUrl() { return url; }
    public String getCitation() { return citation; }
}

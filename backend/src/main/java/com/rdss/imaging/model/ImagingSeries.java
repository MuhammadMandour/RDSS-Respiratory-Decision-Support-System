package com.rdss.imaging.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "imaging_series_metadata")
public class ImagingSeries {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "study_id", nullable = false, updatable = false)
    private Long studyId;

    @Column(name = "series_instance_uid", nullable = false, updatable = false)
    private String seriesInstanceUid;

    @Column(name = "orthanc_series_id", nullable = false)
    private String orthancSeriesId;

    private String modality;

    @Column(name = "body_part")
    private String bodyPart;

    @Column(name = "series_description")
    private String seriesDescription;

    @Column(name = "series_number")
    private Integer seriesNumber;

    @Column(name = "number_of_instances", nullable = false)
    private int numberOfInstances;

    protected ImagingSeries() {
    }

    public ImagingSeries(Long studyId, String seriesInstanceUid, String orthancSeriesId) {
        this.studyId = studyId;
        this.seriesInstanceUid = seriesInstanceUid;
        this.orthancSeriesId = orthancSeriesId;
    }

    public void update(String modality, String bodyPart, String description, Integer number, int instances) {
        this.modality = modality;
        this.bodyPart = bodyPart;
        this.seriesDescription = description;
        this.seriesNumber = number;
        this.numberOfInstances = instances;
    }

    public Long getId() { return id; }
    public Long getStudyId() { return studyId; }
    public String getSeriesInstanceUid() { return seriesInstanceUid; }
    public String getOrthancSeriesId() { return orthancSeriesId; }
    public String getModality() { return modality; }
    public String getBodyPart() { return bodyPart; }
    public String getSeriesDescription() { return seriesDescription; }
    public Integer getSeriesNumber() { return seriesNumber; }
    public int getNumberOfInstances() { return numberOfInstances; }
}

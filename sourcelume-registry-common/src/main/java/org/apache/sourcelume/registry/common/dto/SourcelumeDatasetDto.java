/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sourcelume.registry.common.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import java.util.Objects;

/**
 * High-level DTO representing a sourcelume_dataset entity in Apache Atlas.
 *
 * <p>Besides the provenance attributes, the DTO carries the ingest lifecycle
 * fields ({@link #getRecordStatus() recordStatus}, rawJsonLd, validationIssues)
 * written by the ingest pipeline: the API validates a submission synchronously
 * and stores the record with its verdict — VALIDATED or INCOMPLETE.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourcelumeDatasetDto {

    public static final String TYPE_NAME = "sourcelume_dataset";

    /**
     * The value that explicitly clears the {@code validationIssues}
     * attribute. Atlas upserts merge attributes — an absent attribute keeps
     * its previous value — so every transition away from INCOMPLETE must
     * write this sentinel instead of leaving the attribute unset, or the
     * record would carry its old failure report into the next state.
     */
    public static final String CLEARED_VALIDATION_ISSUES = "[]";

    @NotBlank(message = "Qualified name is required")
    private String qualifiedName;

    /**
     * Optional in the domain: an INCOMPLETE record may lack the mapped
     * fields, so the ingest API writes a placeholder derived from the
     * record id, and a validating verdict overwrites it with the mapped
     * name. (Atlas' Asset supertype requires a name on every entity; the
     * mapped view of a record is {@link ProvenanceRecordDto}, whose
     * constraints are enforced by the JSON Schema stage.)
     */
    private String name;

    private String description;
    private String sourceUri;
    private String licenseId;
    private String guid;
    private RecordStatus recordStatus;
    private String rawJsonLd;
    private String validationIssues;

    public SourcelumeDatasetDto() {}

    public SourcelumeDatasetDto(String qualifiedName, String name, String sourceUri, String licenseId) {
        this.qualifiedName = qualifiedName;
        this.name = name;
        this.sourceUri = sourceUri;
        this.licenseId = licenseId;
    }

    public String getQualifiedName() {
        return qualifiedName;
    }

    public void setQualifiedName(String qualifiedName) {
        this.qualifiedName = qualifiedName;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getSourceUri() {
        return sourceUri;
    }

    public void setSourceUri(String sourceUri) {
        this.sourceUri = sourceUri;
    }

    public String getLicenseId() {
        return licenseId;
    }

    public void setLicenseId(String licenseId) {
        this.licenseId = licenseId;
    }

    public String getGuid() {
        return guid;
    }

    public void setGuid(String guid) {
        this.guid = guid;
    }

    public RecordStatus getRecordStatus() {
        return recordStatus;
    }

    public void setRecordStatus(RecordStatus recordStatus) {
        this.recordStatus = recordStatus;
    }

    public String getRawJsonLd() {
        return rawJsonLd;
    }

    public void setRawJsonLd(String rawJsonLd) {
        this.rawJsonLd = rawJsonLd;
    }

    public String getValidationIssues() {
        return validationIssues;
    }

    public void setValidationIssues(String validationIssues) {
        this.validationIssues = validationIssues;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        SourcelumeDatasetDto that = (SourcelumeDatasetDto) o;
        return Objects.equals(qualifiedName, that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedName);
    }

    @Override
    public String toString() {
        return "SourcelumeDatasetDto{" + "qualifiedName='"
                + qualifiedName + '\'' + ", name='"
                + name + '\'' + ", sourceUri='"
                + sourceUri + '\'' + ", licenseId='"
                + licenseId + '\'' + ", guid='"
                + guid + '\'' + ", recordStatus="
                + recordStatus + '}';
    }
}

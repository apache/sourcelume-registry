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
import org.apache.sourcelume.registry.spi.RecordStatus;

/**
 * Base DTO for a Sourcelume entity that stores an ingested record with its
 * verdict ({@link SourcelumeDatasetDto} for {@code ProvenanceRecord},
 * {@link SourcelumeModelDto} for {@code TrainingProvenanceRecord}).
 *
 * <p>Besides the domain attributes a subtype adds, the DTO carries the
 * ingest lifecycle fields ({@link #getRecordStatus() recordStatus},
 * rawJsonLd, validationIssues, sha256, validatedBy, validatedAt) written
 * by the ingest pipeline: the API validates a submission synchronously
 * and stores the record with its verdict — VALIDATED or INCOMPLETE.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public abstract class RecordEntityDto {

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
     * name. (Atlas' Asset supertype requires a name on every entity write.)
     */
    private String name;

    private String description;
    private String guid;
    private RecordStatus recordStatus;
    private String rawJsonLd;
    private String validationIssues;
    /** Hex SHA-256 over the received request bytes — set by the REST layer. */
    private String sha256;
    /** The validator chain that produced the verdict (comma-separated ids). */
    private String validatedBy;
    /** When the verdict was produced (ISO-8601, UTC). */
    private String validatedAt;

    private String recordFacets;
    private String recordFacetIndex;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRecordFacets() {
        return recordFacets;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setRecordFacets(String value) {
        recordFacets = value;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRecordFacetIndex() {
        return recordFacetIndex;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setRecordFacetIndex(String value) {
        recordFacetIndex = value;
    }

    private String recordAttributes;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRecordAttributes() {
        return recordAttributes;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setRecordAttributes(String value) {
        recordAttributes = value;
    }

    private String registryRevision;
    private String recordAttachments;

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRegistryRevision() {
        return registryRevision;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setRegistryRevision(String value) {
        registryRevision = value;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public String getRecordAttachments() {
        return recordAttachments;
    }

    @com.fasterxml.jackson.annotation.JsonIgnore
    public void setRecordAttachments(String value) {
        recordAttachments = value;
    }

    /**
     * The backend entity type name this DTO is written under (the
     * {@code typeName} of the Atlas entity). Record types declare one
     * entity type each; the SPI relies on it instead of caller-supplied
     * type names so a DTO can never be written under the wrong type.
     */
    public abstract String getTypeName();

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

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public String getValidatedBy() {
        return validatedBy;
    }

    public void setValidatedBy(String validatedBy) {
        this.validatedBy = validatedBy;
    }

    public String getValidatedAt() {
        return validatedAt;
    }

    public void setValidatedAt(String validatedAt) {
        this.validatedAt = validatedAt;
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
        RecordEntityDto that = (RecordEntityDto) o;
        return Objects.equals(qualifiedName, that.qualifiedName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(qualifiedName);
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "{" + "typeName='"
                + getTypeName() + '\'' + ", qualifiedName='"
                + qualifiedName + '\'' + ", name='"
                + name + '\'' + ", guid='"
                + guid + '\'' + ", recordStatus="
                + recordStatus + '}';
    }
}

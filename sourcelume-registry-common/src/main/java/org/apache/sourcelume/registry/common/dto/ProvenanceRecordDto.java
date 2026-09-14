/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;
import java.util.Objects;

/**
 * Shared DTO mapping the Sourcelume ProvenanceRecord schema specification (0.0.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class ProvenanceRecordDto {

    @JsonProperty("@context")
    private Object context;

    @NotBlank(message = "Provenance record id IRI is required")
    private String id;

    private String type = "ProvenanceRecord";

    @NotBlank(message = "Dataset identifier is required")
    private String identifier;

    @NotBlank(message = "Dataset name is required")
    private String name;

    private String version;

    @NotBlank(message = "License IRI is required")
    private String license;

    private String licenseScope;
    private String licenseNote;
    private String licenseCategory;

    private List<LicenseHistoryDto> licenseHistory;

    @NotEmpty(message = "At least one creator is required")
    @Valid
    private List<CreatorDto> creator;

    @NotBlank(message = "created timestamp is required")
    private String created;

    @NotBlank(message = "added timestamp is required")
    private String added;

    @NotBlank(message = "contentCreated timestamp is required")
    private String contentCreated;

    @NotBlank(message = "origin description is required")
    private String origin;

    @NotEmpty(message = "At least one custodyChain event is required")
    @Valid
    private List<CustodyEventDto> custodyChain;

    public ProvenanceRecordDto() {
    }

    public Object getContext() {
        return context;
    }

    public void setContext(Object context) {
        this.context = context;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getIdentifier() {
        return identifier;
    }

    public void setIdentifier(String identifier) {
        this.identifier = identifier;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public String getLicense() {
        return license;
    }

    public void setLicense(String license) {
        this.license = license;
    }

    public String getLicenseScope() {
        return licenseScope;
    }

    public void setLicenseScope(String licenseScope) {
        this.licenseScope = licenseScope;
    }

    public String getLicenseNote() {
        return licenseNote;
    }

    public void setLicenseNote(String licenseNote) {
        this.licenseNote = licenseNote;
    }

    public String getLicenseCategory() {
        return licenseCategory;
    }

    public void setLicenseCategory(String licenseCategory) {
        this.licenseCategory = licenseCategory;
    }

    public List<LicenseHistoryDto> getLicenseHistory() {
        return licenseHistory;
    }

    public void setLicenseHistory(List<LicenseHistoryDto> licenseHistory) {
        this.licenseHistory = licenseHistory;
    }

    public List<CreatorDto> getCreator() {
        return creator;
    }

    public void setCreator(List<CreatorDto> creator) {
        this.creator = creator;
    }

    public String getCreated() {
        return created;
    }

    public void setCreated(String created) {
        this.created = created;
    }

    public String getAdded() {
        return added;
    }

    public void setAdded(String added) {
        this.added = added;
    }

    public String getContentCreated() {
        return contentCreated;
    }

    public void setContentCreated(String contentCreated) {
        this.contentCreated = contentCreated;
    }

    public String getOrigin() {
        return origin;
    }

    public void setOrigin(String origin) {
        this.origin = origin;
    }

    public List<CustodyEventDto> getCustodyChain() {
        return custodyChain;
    }

    public void setCustodyChain(List<CustodyEventDto> custodyChain) {
        this.custodyChain = custodyChain;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProvenanceRecordDto that = (ProvenanceRecordDto) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ProvenanceRecordDto{" +
                "id='" + id + '\'' +
                ", identifier='" + identifier + '\'' +
                ", name='" + name + '\'' +
                ", license='" + license + '\'' +
                '}';
    }
}

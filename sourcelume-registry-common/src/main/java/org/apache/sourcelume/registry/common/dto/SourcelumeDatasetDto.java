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
import jakarta.validation.constraints.NotBlank;

import java.util.Objects;

/**
 * High-level DTO representing a sourcelume_dataset entity in Apache Atlas.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class SourcelumeDatasetDto {

    public static final String TYPE_NAME = "sourcelume_dataset";

    @NotBlank(message = "Qualified name is required")
    private String qualifiedName;

    @NotBlank(message = "Dataset name is required")
    private String name;

    private String description;
    private String sourceUri;
    private String licenseId;
    private String guid;

    public SourcelumeDatasetDto() {
    }

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
        return "SourcelumeDatasetDto{" +
                "qualifiedName='" + qualifiedName + '\'' +
                ", name='" + name + '\'' +
                ", sourceUri='" + sourceUri + '\'' +
                ", licenseId='" + licenseId + '\'' +
                ", guid='" + guid + '\'' +
                '}';
    }
}

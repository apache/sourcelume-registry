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
 * Represents a historical license claim attached to a ProvenanceRecord.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LicenseHistoryDto {

    @NotBlank(message = "License IRI is required")
    private String iri;

    @NotBlank(message = "License effective date is required")
    private String effectiveDate;

    public LicenseHistoryDto() {
    }

    public LicenseHistoryDto(String iri, String effectiveDate) {
        this.iri = iri;
        this.effectiveDate = effectiveDate;
    }

    public String getIri() {
        return iri;
    }

    public void setIri(String iri) {
        this.iri = iri;
    }

    public String getEffectiveDate() {
        return effectiveDate;
    }

    public void setEffectiveDate(String effectiveDate) {
        this.effectiveDate = effectiveDate;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LicenseHistoryDto that = (LicenseHistoryDto) o;
        return Objects.equals(iri, that.iri) &&
                Objects.equals(effectiveDate, that.effectiveDate);
    }

    @Override
    public int hashCode() {
        return Objects.hash(iri, effectiveDate);
    }

    @Override
    public String toString() {
        return "LicenseHistoryDto{" +
                "iri='" + iri + '\'' +
                ", effectiveDate='" + effectiveDate + '\'' +
                '}';
    }
}

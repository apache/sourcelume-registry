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
 * Represents one file covered by a record's claim: the file's {@code contentUrl}
 * and its SHA-256 checksum, so a signature over the record names the exact
 * artifacts it describes. Only SHA-256 is offered by the spec, deliberately.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class DistributionDto {

    @NotBlank(message = "Distribution contentUrl is required")
    private String contentUrl;

    @NotBlank(message = "Distribution sha256 is required")
    private String sha256;

    public DistributionDto() {}

    public DistributionDto(String contentUrl, String sha256) {
        this.contentUrl = contentUrl;
        this.sha256 = sha256;
    }

    public String getContentUrl() {
        return contentUrl;
    }

    public void setContentUrl(String contentUrl) {
        this.contentUrl = contentUrl;
    }

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        DistributionDto that = (DistributionDto) o;
        return Objects.equals(contentUrl, that.contentUrl) && Objects.equals(sha256, that.sha256);
    }

    @Override
    public int hashCode() {
        return Objects.hash(contentUrl, sha256);
    }

    @Override
    public String toString() {
        return "DistributionDto{" + "contentUrl='" + contentUrl + '\'' + ", sha256='" + sha256 + '\'' + '}';
    }
}

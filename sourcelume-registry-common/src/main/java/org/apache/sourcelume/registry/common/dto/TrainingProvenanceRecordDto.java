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

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.Objects;

/**
 * Shared DTO mapping the Sourcelume TrainingProvenanceRecord schema (0.0.1):
 * the model producer's claim of which datasets a model was trained on.
 * The claim is the model producer's — dataset records stay untouched and
 * never point back at models.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class TrainingProvenanceRecordDto {

    @JsonProperty("@context")
    private Object context;

    @NotBlank(message = "Training provenance record id IRI is required")
    private String id;

    private String type = "TrainingProvenanceRecord";

    @NotBlank(message = "Model identifier is required")
    private String identifier;

    @NotBlank(message = "Model name is required")
    private String name;

    private String version;

    @NotEmpty(message = "At least one creator is required")
    @Valid
    private List<CreatorDto> creator;

    @NotBlank(message = "created timestamp is required")
    private String created;

    @NotBlank(message = "disclosure is required")
    private String disclosure;

    @Valid
    private List<TrainingDataEntryDto> trainingData;

    private String note;

    @Valid
    @JsonFormat(with = JsonFormat.Feature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
    private List<DistributionDto> distribution;

    public TrainingProvenanceRecordDto() {}

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

    public String getDisclosure() {
        return disclosure;
    }

    public void setDisclosure(String disclosure) {
        this.disclosure = disclosure;
    }

    public List<TrainingDataEntryDto> getTrainingData() {
        return trainingData;
    }

    public void setTrainingData(List<TrainingDataEntryDto> trainingData) {
        this.trainingData = trainingData;
    }

    public String getNote() {
        return note;
    }

    public void setNote(String note) {
        this.note = note;
    }

    public List<DistributionDto> getDistribution() {
        return distribution;
    }

    public void setDistribution(List<DistributionDto> distribution) {
        this.distribution = distribution;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        TrainingProvenanceRecordDto that = (TrainingProvenanceRecordDto) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "TrainingProvenanceRecordDto{" + "id='"
                + id + '\'' + ", identifier='"
                + identifier + '\'' + ", name='"
                + name + '\'' + ", disclosure='"
                + disclosure + '\'' + '}';
    }

    /**
     * One entry of the {@code trainingData} list: a referenced dataset
     * record (by IRI, normally the {@code id} of a ProvenanceRecord) with
     * the producer's claim about its role in training.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TrainingDataEntryDto {

        @NotBlank(message = "trainingData dataset IRI is required")
        private String dataset;

        private String phase;
        private Long tokens;
        private Double share;

        public TrainingDataEntryDto() {}

        public String getDataset() {
            return dataset;
        }

        public void setDataset(String dataset) {
            this.dataset = dataset;
        }

        public String getPhase() {
            return phase;
        }

        public void setPhase(String phase) {
            this.phase = phase;
        }

        public Long getTokens() {
            return tokens;
        }

        public void setTokens(Long tokens) {
            this.tokens = tokens;
        }

        public Double getShare() {
            return share;
        }

        public void setShare(Double share) {
            this.share = share;
        }
    }
}

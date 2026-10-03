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
package org.apache.sourcelume.registry.common.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sourcelume.registry.common.dto.ProvenanceRecordDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.exception.SourcelumeValidationException;

/**
 * Deterministic mapping between a raw JSON-LD provenance record and the
 * registry's DTOs: {@link #map(String)} binds the document to
 * {@link ProvenanceRecordDto}, {@link #toDatasetEntity(ProvenanceRecordDto)}
 * projects a validated record onto the {@code sourcelume_dataset} entity.
 *
 * <p>This is intentionally a plain Jackson binding, not an RDF round trip:
 * validation runs <em>before</em> mapping, and the validators enforce the
 * canonical record shape, so no JSON-LD expansion is required here. The
 * record's IRI ({@code id}) becomes the entity's qualifiedName — the
 * deterministic identity that makes resubmission idempotent.
 */
public final class ProvenanceRecordMapper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private ProvenanceRecordMapper() {}

    /**
     * Binds a raw JSON-LD provenance record to {@link ProvenanceRecordDto}.
     *
     * @param rawJsonLd the raw JSON-LD document
     * @return the bound record, never null
     * @throws SourcelumeValidationException if the document is not parseable JSON
     */
    public static ProvenanceRecordDto map(String rawJsonLd) {
        requireText(rawJsonLd, "rawJsonLd");
        try {
            return OBJECT_MAPPER.readValue(rawJsonLd, ProvenanceRecordDto.class);
        } catch (JsonProcessingException e) {
            throw new SourcelumeValidationException("Record is not parseable JSON: " + e.getOriginalMessage());
        }
    }

    /**
     * Extracts the record IRI ({@code id}) from a raw document without
     * requiring any other field to be present. The ingest API uses this to
     * key the PENDING entity before validation runs — a document without an
     * {@code id} cannot be stored and is rejected up front.
     *
     * @param rawJsonLd the raw JSON-LD document
     * @return the record IRI, never null or blank
     * @throws IllegalArgumentException if the document is not parseable JSON or carries no usable {@code id}
     */
    public static String extractRecordId(String rawJsonLd) {
        requireText(rawJsonLd, "rawJsonLd");
        JsonNode root;
        try {
            root = OBJECT_MAPPER.readTree(rawJsonLd);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Record is not parseable JSON: " + e.getOriginalMessage());
        }
        JsonNode id = root.path("id");
        if (!id.isTextual() || id.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "Record carries no usable 'id' — the record IRI is required to store a record");
        }
        return id.asText();
    }

    /**
     * Projects a validated record onto the {@code sourcelume_dataset}
     * entity: the record IRI becomes the qualifiedName, the dataset license
     * becomes the licenseId and the identifier becomes the sourceUri. The
     * full sourcelume-spec attribute set follows as the spec evolves; the
     * raw document itself stays available on the entity as rawJsonLd.
     *
     * @param record the validated record, never null
     * @return the entity projection, without status and guid (set by the pipeline)
     */
    public static SourcelumeDatasetDto toDatasetEntity(ProvenanceRecordDto record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        SourcelumeDatasetDto entity = new SourcelumeDatasetDto();
        entity.setQualifiedName(record.getId());
        entity.setName(record.getName());
        entity.setLicenseId(record.getLicense());
        entity.setSourceUri(record.getIdentifier());
        return entity;
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be null or blank");
        }
    }
}

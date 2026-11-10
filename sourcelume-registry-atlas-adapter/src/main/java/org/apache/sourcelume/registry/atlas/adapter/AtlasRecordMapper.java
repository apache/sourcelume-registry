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
package org.apache.sourcelume.registry.atlas.adapter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.spi.*;

/** Mapping to the existing dataset/model entities. Atlas names never cross the SPI. */
final class AtlasRecordMapper {
    private final ObjectMapper json;

    AtlasRecordMapper(ObjectMapper json) {
        this.json = json;
    }

    StoredRecord read(RecordEntityDto entity) {
        try {
            RecordType type = RecordType.fromEntityTypeName(entity.getTypeName())
                    .orElseThrow(() -> new IllegalArgumentException("Unknown stored entity type"));
            RawDocument document = entity.getRawJsonLd() == null
                    ? null
                    : RawDocument.builder()
                            .bytes(entity.getRawJsonLd().getBytes(StandardCharsets.UTF_8))
                            .build();
            if (document != null
                    && entity.getSha256() != null
                    && !document.sha256().equals(entity.getSha256())) {
                throw new IllegalArgumentException("Stored document digest does not match its bytes");
            }
            var attributes = RecordAttributes.builder().name(entity.getName()).description(entity.getDescription());
            if (entity instanceof SourcelumeDatasetDto dataset)
                attributes.licenseId(dataset.getLicenseId()).sourceUri(dataset.getSourceUri());
            if (entity instanceof SourcelumeModelDto model)
                attributes
                        .modelUri(model.getModelUri())
                        .modelVersion(model.getModelVersion())
                        .disclosure(model.getDisclosure());
            ValidationMetadata validation = null;
            if (entity.getRecordStatus() != null) {
                List<ValidationIssue> issues = entity.getValidationIssues() == null
                        ? List.of()
                        : json.readValue(entity.getValidationIssues(), new TypeReference<List<ValidationIssue>>() {});
                validation = ValidationMetadata.builder()
                        .status(entity.getRecordStatus())
                        .issues(issues)
                        .validatedBy(entity.getValidatedBy())
                        .validatedAt(entity.getValidatedAt())
                        .build();
            }
            List<RecordAttachment> attachments = List.of();
            if (entity.getRecordAttachments() != null) {
                List<AttachmentValue> values =
                        json.readValue(entity.getRecordAttachments(), new TypeReference<List<AttachmentValue>>() {});
                attachments = values.stream()
                        .map(v -> RecordAttachment.builder()
                                .name(v.name())
                                .mediaType(v.mediaType())
                                .documentSha256(v.documentSha256())
                                .bytes(Base64.getDecoder().decode(v.base64()))
                                .build())
                        .toList();
            }
            RecordEnvelope envelope = RecordEnvelope.builder()
                    .id(entity.getQualifiedName())
                    .type(type.typeName())
                    .document(document)
                    .validation(validation)
                    .attributes(
                            entity.getRecordAttributes() == null
                                    ? attributes.build()
                                    : json.readValue(entity.getRecordAttributes(), RecordAttributes.class))
                    .facets(
                            entity.getRecordFacets() == null
                                    ? RecordFacets.builder().build()
                                    : new RecordFacets(json.readValue(
                                            entity.getRecordFacets(),
                                            new TypeReference<java.util.Map<RecordFacet, java.util.Set<String>>>() {})))
                    .attachments(attachments)
                    .build();
            String revision = entity.getRegistryRevision();
            // Old records have no revision attribute. Fingerprint the entire stored snapshot,
            // including absent metadata, rather than pretending its document digest is a version.
            if (revision == null)
                revision = "legacy:"
                        + digest(json.writeValueAsBytes(List.of(
                                entity.getGuid() == null ? "" : entity.getGuid(),
                                entity.getTypeName(),
                                json.valueToTree(entity))));
            return StoredRecord.builder().record(envelope).revision(revision).build();
        } catch (JsonProcessingException | IllegalArgumentException | NullPointerException e) {
            throw new StorageException(StorageException.Code.INVALID_DATA, "Cannot decode stored record", e);
        }
    }

    RecordEntityDto write(RecordEnvelope record, String revision) {
        RecordEntityDto entity = RecordType.fromTypeName(record.type())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported record type: " + record.type()))
                .newEntity();
        entity.setQualifiedName(record.id());
        RecordAttributes a = record.attributes();
        entity.setName(a.name() == null ? record.id() : a.name());
        entity.setDescription(a.description());
        if (entity instanceof SourcelumeDatasetDto dataset) {
            dataset.setLicenseId(a.licenseId());
            dataset.setSourceUri(a.sourceUri());
        }
        if (entity instanceof SourcelumeModelDto model) {
            model.setModelUri(a.modelUri());
            model.setModelVersion(a.modelVersion());
            model.setDisclosure(a.disclosure());
        }
        entity.setRawJsonLd(record.document().text());
        entity.setSha256(record.document().sha256());
        entity.setRecordStatus(record.validation().status());
        entity.setValidatedBy(record.validation().validatedBy());
        entity.setValidatedAt(record.validation().validatedAt());
        entity.setRegistryRevision(revision);
        try {
            entity.setRecordAttributes(json.writeValueAsString(record.attributes()));
            entity.setRecordFacets(json.writeValueAsString(record.facets().values()));
            entity.setRecordFacetIndex(AtlasFacetIndex.encode(record.facets()));
            entity.setValidationIssues(
                    json.writeValueAsString(record.validation().issues()));
            entity.setRecordAttachments(json.writeValueAsString(record.attachments().stream()
                    .map(v -> new AttachmentValue(
                            v.name(),
                            v.mediaType(),
                            v.documentSha256(),
                            Base64.getEncoder().encodeToString(v.bytes())))
                    .toList()));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot encode record", e);
        }
        return entity;
    }

    private static String digest(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record AttachmentValue(String name, String mediaType, String documentSha256, String base64) {}
}

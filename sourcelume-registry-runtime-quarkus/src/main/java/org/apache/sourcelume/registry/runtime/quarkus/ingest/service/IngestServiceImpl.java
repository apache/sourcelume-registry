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
package org.apache.sourcelume.registry.runtime.quarkus.ingest.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.RecordValidator;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;

/**
 * The ingest pipeline: validate a submitted record through the plugin
 * chain and store the verdict on the entity. Called synchronously by the
 * REST resource today — and it is the same method a queue consumer would
 * call the day a slow validation step (attestation, and only then a real
 * queue) makes asynchronous processing worth its price.
 */
@ApplicationScoped
public class IngestServiceImpl implements IngestService {

    private final ValidatorChain validatorChain;
    private final AtlasAdapter atlasAdapter;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    @Inject
    public IngestServiceImpl(ValidatorChain validatorChain, AtlasAdapter atlasAdapter, ObjectMapper objectMapper) {
        this(validatorChain, atlasAdapter, objectMapper, Clock.systemUTC());
    }

    IngestServiceImpl(
            ValidatorChain validatorChain, AtlasAdapter atlasAdapter, ObjectMapper objectMapper, Clock clock) {
        this.validatorChain = validatorChain;
        this.atlasAdapter = atlasAdapter;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    public IngestResult process(SourcelumeDatasetDto submitted) {
        Objects.requireNonNull(submitted, "submitted");

        ValidationResult validation = validatorChain.validate(submitted.getRawJsonLd());
        SourcelumeDatasetDto verdict =
                validation.conforms() ? promoteToValidated(submitted) : markIncomplete(submitted, validation);
        stampVerdictContext(verdict, submitted);

        atlasAdapter.createOrUpdateDatasetEntity(verdict);
        return new IngestResult(
                verdict.getQualifiedName(),
                verdict.getRecordStatus(),
                validation,
                verdict.getSha256(),
                verdict.getValidatedBy(),
                verdict.getValidatedAt());
    }

    private SourcelumeDatasetDto promoteToValidated(SourcelumeDatasetDto submitted) {
        // Conforms → the record parses and satisfies schema and shapes, so
        // the mapped provenance attributes (name, licenseId, sourceUri) are
        // safe to write. qualifiedName comes from the record's own id.
        SourcelumeDatasetDto verdict =
                ProvenanceRecordMapper.toDatasetEntity(ProvenanceRecordMapper.map(submitted.getRawJsonLd()));
        verdict.setGuid(submitted.getGuid());
        verdict.setRecordStatus(RecordStatus.VALIDATED);
        verdict.setRawJsonLd(submitted.getRawJsonLd());
        // Explicit clear — see CLEARED_VALIDATION_ISSUES. A resubmitted
        // record previously stored its failure report, and the Atlas
        // upsert merges attributes: an absent attribute keeps its value.
        verdict.setValidationIssues(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES);
        return verdict;
    }

    private SourcelumeDatasetDto markIncomplete(SourcelumeDatasetDto submitted, ValidationResult validation) {
        // Does not conform → the record may lack fields the entity mapping
        // needs, so nothing is mapped; only the lifecycle verdict is stored.
        SourcelumeDatasetDto incomplete = new SourcelumeDatasetDto();
        incomplete.setQualifiedName(submitted.getQualifiedName());
        incomplete.setGuid(submitted.getGuid());
        // Atlas requires a name on every entity write (mandatory on the
        // Asset supertype), so the placeholder name travels along — the
        // verdict would otherwise be rejected.
        incomplete.setName(submitted.getName());
        incomplete.setRecordStatus(RecordStatus.INCOMPLETE);
        incomplete.setRawJsonLd(submitted.getRawJsonLd());
        incomplete.setValidationIssues(toIssuesJson(validation.issues()));
        return incomplete;
    }

    /**
     * The verdict's context: what was submitted (SHA-256 of the received
     * bytes), which chain judged it, and when. Without it a stored verdict
     * loses its meaning as the spec and the chain evolve.
     */
    private void stampVerdictContext(SourcelumeDatasetDto verdict, SourcelumeDatasetDto submitted) {
        verdict.setSha256(submitted.getSha256());
        verdict.setValidatedBy(
                validatorChain.validators().stream().map(RecordValidator::id).collect(Collectors.joining(",")));
        verdict.setValidatedAt(ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
    }

    private String toIssuesJson(List<ValidationIssue> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize validation issues", e);
        }
    }
}

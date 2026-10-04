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
package org.apache.sourcelume.registry.ingest.worker.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;

/**
 * The ingest pipeline: validate a PENDING record's raw JSON-LD and promote
 * it to ACTIVE or INCOMPLETE.
 *
 * <p>Validation is result-based, not exception-based: the chain runs
 * directly (errors-only contract), a failed validation is a <em>state</em>
 * (INCOMPLETE), not an error. The stored document is never modified — the
 * byte-identical rawJsonLd travels with the entity through every state.
 *
 * <p>Adapter failures are deliberately not caught here: the scheduler
 * decides what a failed round means for scheduling, and the record simply
 * stays PENDING.
 */
@ApplicationScoped
public class IngestServiceImpl implements IngestService {

    private final ValidatorChain validatorChain;
    private final AtlasAdapter atlasAdapter;
    private final ObjectMapper objectMapper;

    @Inject
    public IngestServiceImpl(ValidatorChain validatorChain, AtlasAdapter atlasAdapter, ObjectMapper objectMapper) {
        this.validatorChain = validatorChain;
        this.atlasAdapter = atlasAdapter;
        this.objectMapper = objectMapper;
    }

    @Override
    public IngestResult process(SourcelumeDatasetDto pendingDataset) {
        Objects.requireNonNull(pendingDataset, "pendingDataset");

        // The polled snapshot may be stale: a corrected resubmission can
        // land between the poll and this write. Re-read and only proceed if
        // the record is unchanged — otherwise skip: a verdict derived from
        // the old document must never overwrite a fresh submission, and the
        // next poll picks up the new state anyway.
        SourcelumeDatasetDto current = atlasAdapter.getDatasetByQualifiedName(pendingDataset.getQualifiedName());
        if (!isStillThePolledRecord(pendingDataset, current)) {
            return new IngestResult(pendingDataset.getQualifiedName(), RecordStatus.PENDING, null);
        }

        ValidationResult validation = validatorChain.validate(current.getRawJsonLd());
        SourcelumeDatasetDto promoted =
                validation.conforms() ? promoteToActive(current) : markIncomplete(current, validation);

        atlasAdapter.createOrUpdateDatasetEntity(promoted);
        return new IngestResult(promoted.getQualifiedName(), promoted.getRecordStatus(), validation);
    }

    private static boolean isStillThePolledRecord(SourcelumeDatasetDto polled, SourcelumeDatasetDto current) {
        return current != null
                && current.getRecordStatus() == RecordStatus.PENDING
                && Objects.equals(current.getRawJsonLd(), polled.getRawJsonLd());
    }

    private SourcelumeDatasetDto promoteToActive(SourcelumeDatasetDto pending) {
        // Conforms → the record parses and satisfies schema and shapes, so
        // the mapped provenance attributes (name, licenseId, sourceUri) are
        // safe to write. qualifiedName comes from the record's own id.
        SourcelumeDatasetDto active =
                ProvenanceRecordMapper.toDatasetEntity(ProvenanceRecordMapper.map(pending.getRawJsonLd()));
        active.setGuid(pending.getGuid());
        active.setRecordStatus(RecordStatus.ACTIVE);
        active.setRawJsonLd(pending.getRawJsonLd());
        // Explicit clear — see CLEARED_VALIDATION_ISSUES.
        active.setValidationIssues(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES);
        return active;
    }

    private SourcelumeDatasetDto markIncomplete(SourcelumeDatasetDto pending, ValidationResult validation) {
        // Does not conform → the record may lack fields the entity mapping
        // needs, so nothing is mapped; only the lifecycle verdict is stored.
        SourcelumeDatasetDto incomplete = new SourcelumeDatasetDto();
        incomplete.setQualifiedName(pending.getQualifiedName());
        incomplete.setGuid(pending.getGuid());
        // Atlas requires a name on every entity write (mandatory on the
        // Asset supertype), so the placeholder from the PENDING write must
        // travel along — the verdict would otherwise be rejected.
        incomplete.setName(pending.getName());
        incomplete.setRecordStatus(RecordStatus.INCOMPLETE);
        incomplete.setRawJsonLd(pending.getRawJsonLd());
        incomplete.setValidationIssues(toIssuesJson(validation.issues()));
        return incomplete;
    }

    private String toIssuesJson(List<ValidationIssue> issues) {
        try {
            return objectMapper.writeValueAsString(issues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize validation issues", e);
        }
    }
}

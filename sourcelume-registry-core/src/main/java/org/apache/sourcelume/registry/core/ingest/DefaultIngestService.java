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
package org.apache.sourcelume.registry.core.ingest;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.stream.Collectors;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.common.mapper.RecordJson;
import org.apache.sourcelume.registry.common.mapper.TrainingProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.validation.RecordValidator;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.apache.sourcelume.registry.spi.*;

/** Framework-free submission policy shared by all frontends. */
public final class DefaultIngestService implements IngestService {
    private final ValidatorChain validators;
    private final RecordStore store;
    private final Clock clock;

    public DefaultIngestService(ValidatorChain validators, RecordStore store) {
        this(validators, store, Clock.systemUTC());
    }

    public DefaultIngestService(ValidatorChain validators, RecordStore store, Clock clock) {
        this.validators = Objects.requireNonNull(validators);
        this.store = Objects.requireNonNull(store);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public IngestResult process(RecordSubmission submission) {
        store.limits().check(submission.document(), submission.attachments());
        String raw = submission.document().text();
        String id = RecordJson.extractRecordId(raw);
        String claimedType = RecordJson.extractRecordType(raw).orElse(null);
        RecordType type = RecordType.fromTypeName(claimedType).orElse(RecordType.PROVENANCE_RECORD);
        StoredRecord head = store.get(id).orElse(null);
        WriteCondition condition = writeCondition(type, head, submission.expectedRevision());
        var result = validators.validate(raw);
        ValidationMetadata validation = ValidationMetadata.builder()
                .status(result.conforms() ? RecordStatus.VALIDATED : RecordStatus.INCOMPLETE)
                .issues(result.issues())
                .validatedBy(validators.validators().stream()
                        .map(RecordValidator::id)
                        .collect(Collectors.joining(",")))
                .validatedAt(ZonedDateTime.now(clock).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
                .build();
        RecordAttributes attributes = result.conforms()
                ? attributes(type, raw)
                : RecordAttributes.builder().name(placeholderName(id)).build();
        RecordEnvelope record = RecordEnvelope.builder()
                .id(id)
                .type(type.typeName())
                .document(submission.document())
                .validation(validation)
                .attributes(attributes)
                .facets(RecordFacetExtractor.extract(raw, type))
                .attachments(submission.attachments())
                .build();
        WriteResult written = store.write(
                WriteRequest.builder().candidate(record).condition(condition).build());
        return new IngestResult(
                id,
                claimedType,
                validation.status(),
                result,
                record.document().sha256(),
                validation.validatedBy(),
                validation.validatedAt(),
                written.disposition());
    }

    private static WriteCondition writeCondition(RecordType type, StoredRecord head, String expectedRevision) {
        if (head != null && !head.record().type().equals(type.typeName())) {
            throw new RecordConflictException(RecordConflictException.Reason.TYPE_MISMATCH, head);
        }
        if (head != null
                && head.record().validation() != null
                && head.record().validation().status() == RecordStatus.VALIDATED) {
            throw new RecordConflictException(RecordConflictException.Reason.IMMUTABLE, head);
        }
        if (expectedRevision != null) {
            return WriteCondition.atRevision(expectedRevision);
        }
        if (head == null) {
            return WriteCondition.absent();
        }
        return WriteCondition.atRevision(head.revision());
    }

    private static RecordAttributes attributes(RecordType type, String raw) {
        if (type == RecordType.TRAINING_PROVENANCE_RECORD) {
            var record = TrainingProvenanceRecordMapper.map(raw);
            return RecordAttributes.builder()
                    .name(record.getName())
                    .description(record.getNote())
                    .modelUri(record.getIdentifier())
                    .modelVersion(record.getVersion())
                    .disclosure(record.getDisclosure())
                    .build();
        }
        var record = ProvenanceRecordMapper.map(raw);
        return RecordAttributes.builder()
                .name(record.getName())
                .licenseId(record.getLicense())
                .sourceUri(record.getIdentifier())
                .build();
    }

    private static String placeholderName(String id) {
        int slash = Math.max(id.lastIndexOf('/'), id.lastIndexOf('#'));
        String tail = id.substring(slash + 1);
        return tail.isBlank() ? id : tail;
    }
}

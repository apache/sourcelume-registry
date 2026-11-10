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

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.sourcelume.registry.core.validation.*;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.tck.MemoryPlugin;
import org.apache.sourcelume.registry.spi.tck.RecordStoreContract;
import org.junit.jupiter.api.Test;

class DefaultIngestServiceTest {
    private static ValidatorChain validators(Runnable duringValidation) {
        return ValidatorChain.of(List.of(new RecordValidator() {
            public String id() {
                return "test";
            }

            public ValidationResult validate(String raw) {
                duringValidation.run();
                return ValidationResult.failed(List.of(ValidationIssue.violation("test", "/license", "missing")));
            }
        }));
    }

    private static RecordSubmission submission(String revision) {
        return RecordSubmission.builder()
                .document(RawDocument.builder()
                        .bytes("{\"id\":\"urn:one\",\"type\":\"ProvenanceRecord\"}".getBytes(StandardCharsets.UTF_8))
                        .build())
                .expectedRevision(revision)
                .build();
    }

    @Test
    void legacyCorrectionReadsCurrentRevisionAndReplacesIncomplete() {
        try (var plugin = new MemoryPlugin()) {
            var service = new DefaultIngestService(validators(() -> {}), plugin);
            assertEquals(
                    WriteResult.Disposition.CREATED,
                    service.process(submission(null)).disposition());
            assertEquals(
                    WriteResult.Disposition.REPLACED,
                    service.process(submission(null)).disposition());
        }
    }

    @Test
    void explicitStaleRevisionIsNeverReplacedWithLatest() {
        try (var plugin = new MemoryPlugin()) {
            var service = new DefaultIngestService(validators(() -> {}), plugin);
            service.process(submission(null));
            String old = plugin.get("urn:one").orElseThrow().revision();
            service.process(submission(null));
            String latest = plugin.get("urn:one").orElseThrow().revision();
            assertEquals(
                    RecordConflictException.Reason.STALE_REVISION,
                    assertThrows(RecordConflictException.class, () -> service.process(submission(old)))
                            .reason());
            assertEquals(latest, plugin.get("urn:one").orElseThrow().revision());
        }
    }

    @Test
    void concurrentChangeDuringValidationIsNotRetried() {
        try (var plugin = new MemoryPlugin()) {
            new DefaultIngestService(validators(() -> {}), plugin).process(submission(null));
            var service = new DefaultIngestService(
                    validators(() -> {
                        String head = plugin.get("urn:one").orElseThrow().revision();
                        plugin.write(WriteRequest.builder()
                                .candidate(RecordStoreContract.record(
                                        "urn:one", "ProvenanceRecord", "{ }", RecordStatus.INCOMPLETE))
                                .condition(WriteCondition.atRevision(head))
                                .build());
                    }),
                    plugin);
            assertEquals(
                    RecordConflictException.Reason.STALE_REVISION,
                    assertThrows(RecordConflictException.class, () -> service.process(submission(null)))
                            .reason());
            assertEquals(
                    "{ }",
                    plugin.get("urn:one").orElseThrow().record().document().text());
        }
    }

    @Test
    void extractsClaimsFromIncompleteRecordsAndClearsThemOnCorrection() {
        try (var plugin = new MemoryPlugin()) {
            var service = new DefaultIngestService(validators(() -> {}), plugin);
            String raw =
                    """
                    {"id":"urn:claims","type":"TrainingProvenanceRecord",
                     "creator":[{"name":"Team A"},{"name":"Team A"},{"name":42}],
                     "trainingData":[{"dataset":"urn:data:A"},{"dataset":"urn:data:B"},{"dataset":null}],
                     "license":"not-a-training-record-field"}
                    """;
            service.process(RecordSubmission.builder()
                    .document(RawDocument.builder()
                            .bytes(raw.getBytes(StandardCharsets.UTF_8))
                            .build())
                    .build());
            RecordFacets facets =
                    plugin.get("urn:claims").orElseThrow().record().facets();
            assertEquals(java.util.Set.of("Team A"), facets.values(RecordFacet.CREATOR_NAME));
            assertEquals(java.util.Set.of("urn:data:A", "urn:data:B"), facets.values(RecordFacet.TRAINING_DATASET));
            assertTrue(facets.values(RecordFacet.LICENSE).isEmpty());
            String correction = "{\"id\":\"urn:claims\",\"type\":\"TrainingProvenanceRecord\"}";
            service.process(RecordSubmission.builder()
                    .document(RawDocument.builder()
                            .bytes(correction.getBytes(StandardCharsets.UTF_8))
                            .build())
                    .build());
            assertTrue(plugin.get("urn:claims").orElseThrow().record().facets().isEmpty());
        }
    }

    @Test
    void datasetFacetsPreserveExactClaimsWithoutTreatingCreatorNamesAsIdentifiers() {
        RecordFacets facets = RecordFacetExtractor.extract(
                """
                {"license":"https://example.org/LICENSE",
                 "creator":[{"name":"作者 A"},{"name":"  "}],
                 "custodyChain":[{"agent":"https://example.org/org"},{"agent":[]}]}
                """,
                org.apache.sourcelume.registry.common.dto.RecordType.PROVENANCE_RECORD);
        assertEquals(java.util.Set.of("https://example.org/LICENSE"), facets.values(RecordFacet.LICENSE));
        assertEquals(java.util.Set.of("作者 A"), facets.values(RecordFacet.CREATOR_NAME));
        assertEquals(java.util.Set.of("https://example.org/org"), facets.values(RecordFacet.CUSTODY_AGENT));
    }

    @Test
    void escapedUnpairedSurrogatesDoNotBecomeLossyFacetValues() {
        String raw = "{\"license\":\"" + "\\" + "ud800\",\"creator\":[{\"name\":\"valid\"}]}";
        RecordFacets facets = RecordFacetExtractor.extract(
                raw, org.apache.sourcelume.registry.common.dto.RecordType.PROVENANCE_RECORD);
        assertTrue(facets.values(RecordFacet.LICENSE).isEmpty());
        assertEquals(java.util.Set.of("valid"), facets.values(RecordFacet.CREATOR_NAME));
        assertThrows(IllegalArgumentException.class, () -> RecordFacets.builder()
                .add(RecordFacet.LICENSE, String.valueOf((char) 0xd800))
                .build());
    }
}

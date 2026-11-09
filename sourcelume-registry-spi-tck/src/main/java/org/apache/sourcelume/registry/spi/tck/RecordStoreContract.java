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
package org.apache.sourcelume.registry.spi.tck;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.sourcelume.registry.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Run against every provider; createPlugin must return an empty, isolated store. */
public abstract class RecordStoreContract {
    protected RegistryPlugin plugin;
    protected RecordStore store;

    protected abstract RegistryPlugin createPlugin();

    @BeforeEach
    void openStore() {
        plugin = createPlugin();
        store = plugin.recordStore();
    }

    @AfterEach
    void closeStore() {
        plugin.close();
    }

    public static RecordEnvelope record(String id, String type, String text, RecordStatus status) {
        return RecordEnvelope.builder()
                .id(id)
                .type(type)
                .document(RawDocument.builder()
                        .bytes(text.getBytes(StandardCharsets.UTF_8))
                        .build())
                .attributes(RecordAttributes.builder().name("sample").build())
                .validation(ValidationMetadata.builder()
                        .status(status)
                        .issues(
                                status == RecordStatus.INCOMPLETE
                                        ? List.of(ValidationIssue.violation("schema", "/license", "missing"))
                                        : List.of())
                        .validatedBy("schema")
                        .validatedAt("2026-11-07T00:00:00Z")
                        .build())
                .build();
    }

    protected WriteResult write(RecordEnvelope record, WriteCondition condition) {
        return store.write(
                WriteRequest.builder().candidate(record).condition(condition).build());
    }

    @Test
    void absentDisplayAttributesRemainAbsentAcrossStorage() {
        RecordEnvelope input = RecordEnvelope.builder()
                .id("urn:no-attributes")
                .type("ProvenanceRecord")
                .document(RawDocument.builder()
                        .bytes("{}".getBytes(StandardCharsets.UTF_8))
                        .build())
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .build())
                .build();
        write(input, WriteCondition.absent());
        assertEquals(input, store.get(input.id()).orElseThrow().record());
    }

    @Test
    void exactBytesAndVerdictRoundTrip() {
        RecordEnvelope input = record("urn:one", "ProvenanceRecord", "{ \"name\": \"雪\" }\n", RecordStatus.INCOMPLETE);
        WriteResult result = write(input, WriteCondition.absent());
        assertEquals(WriteResult.Disposition.CREATED, result.disposition());
        assertEquals(input, store.get(input.id()).orElseThrow().record());
        assertEquals(
                result.stored().revision(), store.get(input.id()).orElseThrow().revision());
    }

    @Test
    void correctionReplacesDocumentVerdictAndAttachmentsTogether() {
        RawDocument doc = RawDocument.builder()
                .bytes("{ }".getBytes(StandardCharsets.UTF_8))
                .build();
        RecordAttachment attachment = RecordAttachment.builder()
                .name("signature")
                .mediaType("application/jose")
                .documentSha256(doc.sha256())
                .bytes(new byte[] {1, 2, 3})
                .build();
        RecordEnvelope initial = RecordEnvelope.builder()
                .id("urn:one")
                .type("ProvenanceRecord")
                .document(doc)
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .build())
                .attributes(RecordAttributes.builder()
                        .name("sample")
                        .description("old")
                        .build())
                .attachments(List.of(attachment))
                .build();
        String revision = write(initial, WriteCondition.absent()).stored().revision();
        assertEquals(
                List.of(attachment),
                store.get(initial.id()).orElseThrow().record().attachments());
        RecordEnvelope correction = record("urn:one", "ProvenanceRecord", "{\"new\":true}", RecordStatus.VALIDATED);
        WriteResult result = write(correction, WriteCondition.atRevision(revision));
        assertEquals(WriteResult.Disposition.REPLACED, result.disposition());
        assertNotEquals(revision, result.stored().revision());
        assertEquals(correction, store.get("urn:one").orElseThrow().record());
    }

    @Test
    void staleRevisionCannotReplaceLatestSnapshot() {
        RecordEnvelope a = record("urn:one", "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE);
        String revision = write(a, WriteCondition.absent()).stored().revision();
        RecordEnvelope b = record("urn:one", "ProvenanceRecord", "{ }", RecordStatus.INCOMPLETE);
        write(b, WriteCondition.atRevision(revision));
        assertEquals(
                RecordConflictException.Reason.STALE_REVISION,
                assertThrows(RecordConflictException.class, () -> write(a, WriteCondition.atRevision(revision)))
                        .reason());
        assertEquals(b, store.get("urn:one").orElseThrow().record());
    }

    @Test
    void validatedRecordsAndBoundTypesAreImmutable() {
        RecordEnvelope initial = record("urn:one", "ProvenanceRecord", "{}", RecordStatus.VALIDATED);
        String revision = write(initial, WriteCondition.absent()).stored().revision();
        assertEquals(
                RecordConflictException.Reason.IMMUTABLE,
                assertThrows(RecordConflictException.class, () -> write(initial, WriteCondition.atRevision(revision)))
                        .reason());
        RecordEnvelope model = record("urn:one", "TrainingProvenanceRecord", "{}", RecordStatus.INCOMPLETE);
        assertEquals(
                RecordConflictException.Reason.TYPE_MISMATCH,
                assertThrows(RecordConflictException.class, () -> write(model, WriteCondition.atRevision(revision)))
                        .reason());
        assertEquals(initial, store.get("urn:one").orElseThrow().record());
    }

    @Test
    void missingRevisionDoesNotCreateRecord() {
        assertEquals(
                RecordConflictException.Reason.NOT_FOUND,
                assertThrows(
                                RecordConflictException.class,
                                () -> write(
                                        record("urn:one", "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE),
                                        WriteCondition.atRevision("missing")))
                        .reason());
        assertTrue(store.get("urn:one").isEmpty());
    }

    @Test
    void concurrentCreatesHaveOneWinner() throws Exception {
        race(WriteCondition.absent());
    }

    @Test
    void concurrentCorrectionsHaveOneWinner() throws Exception {
        String revision = write(
                        record("urn:race", "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE), WriteCondition.absent())
                .stored()
                .revision();
        race(WriteCondition.atRevision(revision));
    }

    private void race(WriteCondition condition) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int i = 0; i < 12; i++) {
                String text = "{\"attempt\":" + i + "}";
                tasks.add(executor.submit(() -> {
                    start.await();
                    try {
                        write(record("urn:race", "ProvenanceRecord", text, RecordStatus.INCOMPLETE), condition);
                        return true;
                    } catch (RecordConflictException expected) {
                        return false;
                    }
                }));
            }
            start.countDown();
            int successes = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
        }
    }

    @Test
    void batchReadsReturnCompleteSnapshotsAndOmitMissingRecords() {
        var dataset = record("urn:dataset", "ProvenanceRecord", "{ \"name\": \"雪\" }\n", RecordStatus.INCOMPLETE);
        var model = RecordEnvelope.builder()
                .id("urn:model")
                .type("TrainingProvenanceRecord")
                .document(dataset.document())
                .validation(dataset.validation())
                .facets(RecordFacets.builder()
                        .add(RecordFacet.TRAINING_DATASET, dataset.id())
                        .build())
                .attachments(List.of(RecordAttachment.builder()
                        .name("signature")
                        .mediaType("application/jose")
                        .documentSha256(dataset.document().sha256())
                        .bytes(new byte[] {0, 1, -1})
                        .build()))
                .build();
        var storedDataset = write(dataset, WriteCondition.absent()).stored();
        var storedModel = write(model, WriteCondition.absent()).stored();
        var found = store.getAll(List.of("urn:missing", model.id(), dataset.id(), model.id()));
        assertEquals(java.util.Map.of(dataset.id(), storedDataset, model.id(), storedModel), found);
        assertThrows(UnsupportedOperationException.class, found::clear);
    }

    @Test
    void batchReadsHandleEmptyRequestsAndRejectInvalidIds() {
        assertTrue(store.getAll(List.of()).isEmpty());
        assertTrue(store.getAll(List.of("urn:missing")).isEmpty());
        assertThrows(NullPointerException.class, () -> store.getAll(null));
        assertThrows(IllegalArgumentException.class, () -> store.getAll(List.of("urn:one", " ")));
        assertThrows(IllegalArgumentException.class, () -> store.getAll(java.util.Arrays.asList("urn:one", null)));
    }

    @Test
    void closeIsIdempotentAndStopsAccess() {
        plugin.close();
        plugin.close();
        assertThrows(StorageException.class, () -> store.get("urn:one"));
        assertThrows(StorageException.class, () -> store.getAll(List.of("urn:one")));
    }

    @Test
    void facetSearchUsesExactOrWithinAndAcrossFieldsAndClearsOnReplacement() throws Exception {
        RecordFacets original = RecordFacets.builder()
                .add(RecordFacet.TRAINING_DATASET, "urn:data:A")
                .add(RecordFacet.TRAINING_DATASET, "urn:data:B")
                .add(RecordFacet.CREATOR_NAME, "Team A")
                .build();
        var input = RecordEnvelope.builder()
                .id("urn:faceted")
                .type("TrainingProvenanceRecord")
                .document(RawDocument.builder()
                        .bytes("{}".getBytes(StandardCharsets.UTF_8))
                        .build())
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .build())
                .facets(original)
                .build();
        String revision = write(input, WriteCondition.absent()).stored().revision();
        assertEquals(original, store.get(input.id()).orElseThrow().record().facets());
        var filter = RecordFacets.builder()
                .add(RecordFacet.TRAINING_DATASET, "urn:missing")
                .add(RecordFacet.TRAINING_DATASET, "urn:data:B")
                .add(RecordFacet.CREATOR_NAME, "Team A")
                .build();
        SearchQuery query = SearchQuery.builder()
                .type("TrainingProvenanceRecord")
                .status(RecordStatus.INCOMPLETE)
                .facets(filter)
                .build();
        awaitMatches(query, java.util.Set.of(input.id()));
        for (var mismatch : List.of(
                RecordFacets.builder()
                        .add(RecordFacet.TRAINING_DATASET, "urn:data")
                        .build(),
                RecordFacets.builder()
                        .add(RecordFacet.TRAINING_DATASET, "urn:data:b")
                        .build(),
                RecordFacets.builder()
                        .add(RecordFacet.CREATOR_NAME, "Team A")
                        .add(RecordFacet.TRAINING_DATASET, "urn:missing")
                        .build())) {
            awaitMatches(
                    SearchQuery.builder()
                            .type(query.type())
                            .status(query.status())
                            .facets(mismatch)
                            .build(),
                    java.util.Set.of());
        }
        write(record(input.id(), input.type(), "{ }", RecordStatus.INCOMPLETE), WriteCondition.atRevision(revision));
        awaitMatches(query, java.util.Set.of());
        assertTrue(store.get(input.id()).orElseThrow().record().facets().isEmpty());
    }

    private void awaitMatches(SearchQuery query, java.util.Set<String> expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        java.util.Set<String> found;
        do {
            found = plugin.recordSearch().search(query).stream()
                    .map(v -> v.record().id())
                    .collect(java.util.stream.Collectors.toSet());
            if (expected.equals(found)) return;
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, found);
    }
}

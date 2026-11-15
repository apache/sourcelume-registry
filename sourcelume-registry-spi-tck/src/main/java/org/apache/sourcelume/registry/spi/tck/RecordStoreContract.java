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
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.apache.sourcelume.registry.spi.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Run against every provider; createPlugin must return an initialized, empty, isolated store. */
public abstract class RecordStoreContract {
    private static final int MAX_TEST_PAYLOAD_BYTES = 16 * 1024 * 1024;
    private static final int MAX_TEST_ATTACHMENTS = 1024;

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
    void documentLimitRejectsOversizedCreatesAndCorrections() {
        int maximum = store.limits().maxDocumentBytes();
        assumeTrue(maximum <= MAX_TEST_PAYLOAD_BYTES, "Configure a document limit of at most 16 MiB for this test");
        RawDocument accepted = documentOfSize(maximum);
        RawDocument rejected = documentOfSize(maximum + 1);
        assertLimitEnforced(
                limitRecord(accepted, smallAttachment(accepted), "original"),
                limitRecord(rejected, smallAttachment(rejected), "rejected"));
    }

    @Test
    void attachmentCountLimitRejectsOversizedCreatesAndCorrections() {
        int maximum = store.limits().maxAttachments();
        assumeTrue(
                maximum <= MAX_TEST_ATTACHMENTS, "Configure an attachment count limit of at most 1024 for this test");
        RawDocument document = documentOfSize(1);
        var attachments = new ArrayList<RecordAttachment>();
        for (int i = 0; i < maximum; i++) attachments.add(attachment(document, "part-" + i, 0));
        RecordEnvelope accepted = limitRecord(document, attachments, "original");
        attachments.add(attachment(document, "one-too-many", 0));
        assertLimitEnforced(accepted, limitRecord(document, attachments, "rejected"));
    }

    @Test
    void combinedAttachmentLimitRejectsOversizedCreatesAndCorrections() {
        assumeTrue(store.limits().maxAttachments() > 0, "This store does not allow attachments");
        int maximum = store.limits().maxAttachmentBytes();
        assumeTrue(
                maximum <= MAX_TEST_PAYLOAD_BYTES,
                "Configure an attachment byte limit of at most 16 MiB for this test");
        RawDocument document = documentOfSize(1);
        assertLimitEnforced(
                limitRecord(document, attachmentsWithTotalBytes(document, maximum), "original"),
                limitRecord(document, attachmentsWithTotalBytes(document, maximum + 1), "rejected"));
    }

    private void assertLimitEnforced(RecordEnvelope accepted, RecordEnvelope rejected) {
        assertThrows(IllegalArgumentException.class, () -> write(rejected, WriteCondition.absent()));
        assertTrue(store.get(rejected.id()).isEmpty(), "A rejected create must not leave a record");

        StoredRecord original = write(accepted, WriteCondition.absent()).stored();
        assertEquals(accepted, original.record(), "The declared limit is inclusive");
        assertEquals(original, store.get(accepted.id()).orElseThrow());
        assertThrows(
                IllegalArgumentException.class, () -> write(rejected, WriteCondition.atRevision(original.revision())));
        assertEquals(
                original, store.get(accepted.id()).orElseThrow(), "A rejected correction must preserve the snapshot");
    }

    private static RawDocument documentOfSize(int size) {
        if (size < 5) {
            return RawDocument.builder()
                    .bytes(("0" + " ".repeat(size - 1)).getBytes(StandardCharsets.UTF_8))
                    .build();
        }
        return RawDocument.builder()
                .bytes(("\"雪\"" + " ".repeat(size - 5)).getBytes(StandardCharsets.UTF_8))
                .build();
    }

    private List<RecordAttachment> smallAttachment(RawDocument document) {
        if (store.limits().maxAttachments() == 0) return List.of();
        return List.of(
                attachment(document, "signature", Math.min(3, store.limits().maxAttachmentBytes())));
    }

    private List<RecordAttachment> attachmentsWithTotalBytes(RawDocument document, int size) {
        if (store.limits().maxAttachments() == 1) return List.of(attachment(document, "signature", size));
        // With a positive limit, each part fits on its own; their combined size exercises the bound.
        return List.of(attachment(document, "part-0", size / 2), attachment(document, "part-1", size - size / 2));
    }

    private static RecordAttachment attachment(RawDocument document, String name, int size) {
        return RecordAttachment.builder()
                .name(name)
                .mediaType("application/octet-stream")
                .documentSha256(document.sha256())
                .bytes(new byte[size])
                .build();
    }

    private static RecordEnvelope limitRecord(RawDocument document, List<RecordAttachment> attachments, String marker) {
        return RecordEnvelope.builder()
                .id("urn:limits")
                .type("ProvenanceRecord")
                .document(document)
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .issues(List.of(ValidationIssue.violation("schema", "/name", marker)))
                        .build())
                .attributes(RecordAttributes.builder().name(marker).build())
                .facets(RecordFacets.builder()
                        .add(RecordFacet.CREATOR_NAME, marker)
                        .build())
                .attachments(attachments)
                .build();
    }

    @Test
    void repeatedInitializationPreservesRecordsAndRevisions() throws Exception {
        RawDocument document = documentOfSize(1);
        RecordEnvelope input = limitRecord(document, smallAttachment(document), "original");
        StoredRecord original = write(input, WriteCondition.absent()).stored();
        plugin.initialize();
        plugin.initialize();
        assertTrue(plugin.isReady());
        assertEquals(original, store.get(input.id()).orElseThrow());
        assertEquals(original, plugin.recordStore().get(input.id()).orElseThrow());
        awaitSearch(
                SearchQuery.builder()
                        .type(input.type())
                        .status(RecordStatus.INCOMPLETE)
                        .facets(input.facets())
                        .build(),
                found -> assertEquals(List.of(original), found));
    }

    @Test
    void closeIsIdempotentAndStopsAccess() {
        RecordSearch search = plugin.recordSearch();
        RecordEnvelope input = record("urn:one", "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE);
        StoredRecord original = write(input, WriteCondition.absent()).stored();
        plugin.close();
        plugin.close();
        assertFalse(plugin.isReady());
        assertThrows(StorageException.class, () -> store.get("urn:one"));
        assertThrows(StorageException.class, () -> store.getAll(List.of("urn:one")));
        assertThrows(StorageException.class, () -> write(input, WriteCondition.atRevision(original.revision())));
        assertThrows(
                StorageException.class,
                () -> write(
                        record("urn:after-close", input.type(), "{}", RecordStatus.INCOMPLETE),
                        WriteCondition.absent()));
        assertThrows(
                StorageException.class,
                () -> search.search(SearchQuery.builder()
                        .type(input.type())
                        .status(RecordStatus.INCOMPLETE)
                        .build()));
    }

    @Test
    void searchFiltersTypeAndStatus() throws Exception {
        var records = new ArrayList<StoredRecord>();
        for (String type : List.of("ProvenanceRecord", "TrainingProvenanceRecord")) {
            for (RecordStatus status : RecordStatus.values()) {
                records.add(write(record("urn:" + type + ":" + status, type, "{}", status), WriteCondition.absent())
                        .stored());
            }
        }
        for (StoredRecord expected : records) {
            for (int limit : List.of(100, 1)) {
                awaitSearch(
                        SearchQuery.builder()
                                .type(expected.record().type())
                                .status(expected.record().validation().status())
                                .limit(limit)
                                .build(),
                        found -> assertEquals(List.of(expected), found));
            }
        }
    }

    @Test
    void searchAppliesLimitAfterFacetFiltering() throws Exception {
        Map<RecordFacets, List<StoredRecord>> groups = new LinkedHashMap<>();
        for (String creator : List.of("Team A", "Team B")) {
            RecordFacets facets = RecordFacets.builder()
                    .add(RecordFacet.CREATOR_NAME, creator)
                    .build();
            var records = new ArrayList<StoredRecord>();
            for (int i = 0; i < 3; i++) {
                var input = record(
                        "urn:" + creator.replace(' ', '-') + ":" + i,
                        "ProvenanceRecord",
                        "{}",
                        RecordStatus.INCOMPLETE);
                records.add(write(
                                RecordEnvelope.builder()
                                        .id(input.id())
                                        .type(input.type())
                                        .document(input.document())
                                        .validation(input.validation())
                                        .facets(facets)
                                        .build(),
                                WriteCondition.absent())
                        .stored());
            }
            groups.put(facets, records);
        }
        for (var group : groups.entrySet()) {
            List<StoredRecord> expected = group.getValue();
            awaitSearch(
                    SearchQuery.builder()
                            .type("ProvenanceRecord")
                            .status(RecordStatus.INCOMPLETE)
                            .facets(group.getKey())
                            .build(),
                    found -> assertEquals(Set.copyOf(expected), Set.copyOf(found)));
            for (int limit : List.of(1, 2)) {
                awaitSearch(
                        SearchQuery.builder()
                                .type("ProvenanceRecord")
                                .status(RecordStatus.INCOMPLETE)
                                .facets(group.getKey())
                                .limit(limit)
                                .build(),
                        found -> {
                            assertFalse(found.isEmpty(), "Indexed matches must remain searchable with a smaller limit");
                            assertTrue(expected.containsAll(found));
                        });
            }
        }
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
        awaitSearch(
                query,
                found -> assertEquals(
                        expected,
                        found.stream().map(v -> v.record().id()).collect(java.util.stream.Collectors.toSet())));
    }

    private void awaitSearch(SearchQuery query, Consumer<List<StoredRecord>> assertions) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        AssertionError lastFailure;
        do {
            List<StoredRecord> found = plugin.recordSearch().search(query);
            assertTrue(found.size() <= query.limit(), "Search must honor the requested limit");
            assertEquals(
                    found.size(),
                    found.stream().map(v -> v.record().id()).distinct().count(),
                    "Search must not return duplicate records");
            try {
                assertions.accept(found);
                return;
            } catch (AssertionError failure) {
                lastFailure = failure;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        throw lastFailure;
    }
}

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
package org.apache.sourcelume.registry.runtime.quarkus.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.apache.sourcelume.registry.runtime.quarkus.ingest.service.IngestServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Semantics of the ingest endpoints, with the real ingest pipeline
 * (the real plugin chain from the classpath) and an in-memory Atlas
 * fake: POST validates inline and answers with the record's verdict —
 * 201 for a new record, 200 for a corrected resubmission of an
 * INCOMPLETE record, 409 for anything already VALIDATED.
 */
class IngestResourceTest {

    private static final String RECORD_ID = "https://sourcelume.apache.org/records/ingest-test";
    private static final String VALID_RECORD =
            """
            {
              "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
              "id": "https://sourcelume.apache.org/records/ingest-test",
              "type": "ProvenanceRecord",
              "identifier": "https://example.org/datasets/ingest-test",
              "name": "Ingest endpoint test record",
              "license": "https://www.apache.org/licenses/LICENSE-2.0",
              "creator": [ { "type": "schema:Organization", "name": "Example Organization" } ],
              "created": "2026-09-08T00:00:00Z",
              "added": "2026-09-08T00:00:00Z",
              "contentCreated": "2026-09-08T00:00:00Z",
              "origin": "Test record for the ingest endpoint.",
              "custodyChain": [ { "agent": "https://example.org/", "action": "created",
                                  "startTime": "2026-09-08T00:00:00Z" } ]
            }""";

    private static final String INVALID_RECORD =
            VALID_RECORD.replace("\"license\": \"https://www.apache.org/licenses/LICENSE-2.0\",", "");

    /** The same record without its {@code type} term — lands as an INCOMPLETE stub on the dataset entity. */
    private static final String TYPELESS_RECORD = VALID_RECORD.replace("\"type\": \"ProvenanceRecord\",", "");

    /** A conforming TrainingProvenanceRecord fixture, its id rewritten to this test's record id. */
    private static final String TRAINING_RECORD = trainingRecord();

    private static final String MODEL_RECORD_ID = "https://sourcelume.apache.org/records/ingest-test-model";
    private static final String UNREGISTERED_DATASET = "https://example.org/datasets/no-record";

    private final FakeAtlasAdapter fake = new FakeAtlasAdapter();
    private final IngestResource resource = new IngestResource(
            fake, new IngestServiceImpl(ValidatorChain.discover(), fake, new ObjectMapper()), new ObjectMapper());

    @BeforeEach
    void resetStore() {
        fake.store.clear();
        fake.lookups.clear();
        fake.unreachable.clear();
    }

    @Test
    void validRecordAnswersWithItsVerdict() {
        var response = resource.submit(VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(201, response.getStatus());
        assertEquals(
                URI.create("/records/https%3A%2F%2Fsourcelume.apache.org%2Frecords%2Fingest-test"),
                response.getLocation());

        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertNull(body.validationIssues(), "a validated record reports no issues");
        assertEquals(
                org.apache.sourcelume.registry.runtime.quarkus.ingest.service.Digests.sha256Hex(
                        VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                body.sha256(),
                "the digest covers the received bytes");
        assertTrue(body.validatedBy().contains("json"), "the chain ids are reported");
        assertNotNull(body.validatedAt(), "the verdict carries its timestamp");

        SourcelumeDatasetDto stored = storedDataset();
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals("Ingest endpoint test record", stored.getName(), "the mapped name replaces the placeholder");
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", stored.getLicenseId());
        assertEquals(VALID_RECORD, stored.getRawJsonLd(), "the stored document must be byte-identical");
        assertEquals(body.sha256(), stored.getSha256());
    }

    @Test
    void invalidRecordBecomesIncompleteAndReportsItsIssues() {
        var response = resource.submit(INVALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(201, response.getStatus());
        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertTrue(
                body.validationIssues().toLowerCase().contains("license"),
                "the issues come with the response — no polling needed");

        SourcelumeDatasetDto stored = storedDataset();
        assertEquals(RecordStatus.INCOMPLETE, stored.getRecordStatus());
        assertEquals(
                "ingest-test",
                stored.getName(),
                "Atlas requires a name — the placeholder stays on an INCOMPLETE record");
        assertTrue(
                stored.getValidationIssues() != null
                        && !SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES.equals(stored.getValidationIssues()),
                "the pipeline stores the real failure report, not the sentinel");
    }

    @Test
    void correctedResubmissionOfAnIncompleteRecordAnswersWithItsVerdict() {
        resource.submit(INVALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        storedDataset().setValidationIssues("[{\"message\": \"missing license\"}]");

        var response = resource.submit(VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(200, response.getStatus());
        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertNull(body.validationIssues(), "the old failure report does not surface");

        SourcelumeDatasetDto stored = storedDataset();
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals(
                SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES,
                stored.getValidationIssues(),
                "the resubmission explicitly clears the old issues — see CLEARED_VALIDATION_ISSUES");
    }

    /**
     * The id names one record whichever store the first submission landed
     * in: an INCOMPLETE stub left under the dataset type is deleted when
     * the resubmission arrives as a TrainingProvenanceRecord — it may not
     * stay behind shadowing {@code GET /records/{id}} with a stale verdict.
     */
    @Test
    void resubmittingAnIncompleteStubUnderAnotherTypeReplacesIt() {
        var first = resource.submit(TYPELESS_RECORD.getBytes(StandardCharsets.UTF_8));
        assertEquals(RecordStatus.INCOMPLETE, ((IngestResource.IngestResponse) first.getEntity()).recordStatus());

        var response = resource.submit(TRAINING_RECORD.getBytes(StandardCharsets.UTF_8));

        assertEquals(200, response.getStatus());
        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());

        assertNull(
                fake.getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, RECORD_ID),
                "the stale dataset stub must be deleted, not shadowed");
        assertNotNull(
                fake.getRecordEntityByQualifiedName(SourcelumeModelDto.TYPE_NAME, RECORD_ID),
                "the resubmission lands on the model entity");

        var status = resource.status(RECORD_ID);
        IngestResource.RecordResponse statusBody = (IngestResource.RecordResponse) status.getEntity();
        assertEquals(RecordStatus.VALIDATED, statusBody.recordStatus());
        assertEquals("TrainingProvenanceRecord", statusBody.type());
    }

    /**
     * A TrainingProvenanceRecord under the IRI of a VALIDATED dataset
     * record is a conflict — one IRI may not hold two VALIDATED records,
     * one per type.
     */
    @Test
    void trainingRecordUnderAValidatedDatasetIdIsRejected() {
        resource.submit(VALID_RECORD.getBytes(StandardCharsets.UTF_8));

        var response = resource.submit(TRAINING_RECORD.getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        IngestResource.ConflictResponse body = (IngestResource.ConflictResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertEquals(SourcelumeDatasetDto.TYPE_NAME, body.entityType(), "names the store that holds the id");
        assertTrue(body.reason().contains("VALIDATED"), body.reason());
        assertNull(
                fake.getRecordEntityByQualifiedName(SourcelumeModelDto.TYPE_NAME, RECORD_ID),
                "no second record may be created under a taken id");
    }

    /**
     * A plain dataset (no record status) on the id keeps a training record
     * out, and the 409 says why: there is no status to read the cause from.
     */
    @Test
    void trainingRecordUnderAPlainDatasetIdIsRejectedWithItsReason() {
        SourcelumeDatasetDto plain = new SourcelumeDatasetDto();
        plain.setQualifiedName(RECORD_ID);
        plain.setName("plain dataset");
        fake.createOrUpdateRecordEntity(plain);

        var response = resource.submit(TRAINING_RECORD.getBytes(StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        IngestResource.ConflictResponse body = (IngestResource.ConflictResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertNull(body.recordStatus(), "a plain dataset has no record status");
        assertEquals(SourcelumeDatasetDto.TYPE_NAME, body.entityType());
        assertTrue(body.reason().contains("plain dataset"), body.reason());
        assertNull(
                fake.getRecordEntityByQualifiedName(SourcelumeModelDto.TYPE_NAME, RECORD_ID),
                "no second record may be created under a taken id");
    }

    @Test
    void validatedRecordRejectsDuplicateSubmit() {
        resource.submit(VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var response = resource.submit(VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(409, response.getStatus());
        IngestResource.ConflictResponse body = (IngestResource.ConflictResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertEquals(SourcelumeDatasetDto.TYPE_NAME, body.entityType());
        assertTrue(body.reason().contains("new id"), "tells the client what to do instead: " + body.reason());
    }

    @Test
    void recordWithoutAnIdIsRejected() {
        var response = resource.submit("{\"name\": \"no id here\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(400, response.getStatus());
    }

    @Test
    void unparseableBodyIsRejected() {
        var response = resource.submit("this is not JSON".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(400, response.getStatus());
    }

    @Test
    void statusOfAnUnknownRecordIs404() {
        var response = resource.status("https://sourcelume.apache.org/records/unknown");

        assertEquals(404, response.getStatus());
    }

    @Test
    void statusReturnsRecordStateAndIssues() {
        resource.submit(INVALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        storedDataset().setValidationIssues("[{\"message\": \"missing license\"}]");

        var response = resource.status(RECORD_ID);

        assertEquals(200, response.getStatus());
        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertEquals("[{\"message\": \"missing license\"}]", body.validationIssues());
    }

    @Test
    void statusOfAValidatedRecordCarriesTheMappedAttributes() {
        resource.submit(VALID_RECORD.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        var response = resource.status(RECORD_ID);

        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertEquals("Ingest endpoint test record", body.name());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", body.licenseId());
        assertEquals("https://example.org/datasets/ingest-test", body.sourceUri());
        assertNull(body.validationIssues(), "the cleared sentinel never surfaces");
    }

    /**
     * A VALIDATED model record reports each referenced dataset with the
     * state its record is in: registered, or absent for an IRI the
     * registry holds no record for.
     */
    @Test
    void statusOfATrainingRecordResolvesItsDatasets() {
        resource.submit(VALID_RECORD.getBytes(StandardCharsets.UTF_8));
        resource.submit(modelRecord("complete", RECORD_ID, UNREGISTERED_DATASET).getBytes(StandardCharsets.UTF_8));

        IngestResource.RecordResponse body =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();

        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertEquals(
                List.of(
                        new IngestResource.TrainingDataResponse(
                                RECORD_ID, "pretraining", 1000L, 0.5, RecordStatus.VALIDATED, null),
                        new IngestResource.TrainingDataResponse(
                                UNREGISTERED_DATASET, "pretraining", 1000L, 0.5, null, null)),
                body.trainingData());
    }

    /** Nothing is stored on the model entity: a dataset record published later shows up on the next read. */
    @Test
    void trainingDataIsResolvedAtReadTime() {
        resource.submit(modelRecord("complete", RECORD_ID).getBytes(StandardCharsets.UTF_8));
        IngestResource.RecordResponse before =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();
        assertNull(before.trainingData().get(0).recordStatus());

        resource.submit(INVALID_RECORD.getBytes(StandardCharsets.UTF_8));

        IngestResource.RecordResponse after =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();
        assertEquals(RecordStatus.INCOMPLETE, after.trainingData().get(0).recordStatus());
    }

    /** A dataset listed in several entries (e.g. once per phase) is looked up once and reported for each. */
    @Test
    void aDatasetListedTwiceIsLookedUpOnce() {
        resource.submit(VALID_RECORD.getBytes(StandardCharsets.UTF_8));
        resource.submit(modelRecord("complete", RECORD_ID, RECORD_ID).getBytes(StandardCharsets.UTF_8));
        fake.lookups.clear();

        IngestResource.RecordResponse body =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();

        assertEquals(2, body.trainingData().size());
        body.trainingData().forEach(entry -> assertEquals(RecordStatus.VALIDATED, entry.recordStatus()));
        assertEquals(1, fake.lookups.get(RECORD_ID));
    }

    /** A dataset the backend cannot be asked about marks only its own entry; the model record stays readable. */
    @Test
    void aFailedDatasetLookupMarksOnlyItsEntry() {
        resource.submit(VALID_RECORD.getBytes(StandardCharsets.UTF_8));
        resource.submit(modelRecord("complete", RECORD_ID, UNREGISTERED_DATASET).getBytes(StandardCharsets.UTF_8));
        fake.unreachable.add(UNREGISTERED_DATASET);

        var response = resource.status(MODEL_RECORD_ID);

        assertEquals(200, response.getStatus());
        List<IngestResource.TrainingDataResponse> trainingData =
                ((IngestResource.RecordResponse) response.getEntity()).trainingData();
        assertEquals(RecordStatus.VALIDATED, trainingData.get(0).recordStatus());
        assertNull(trainingData.get(0).lookupFailed());
        assertNull(trainingData.get(1).recordStatus());
        assertEquals(Boolean.TRUE, trainingData.get(1).lookupFailed());
    }

    /** After a failed lookup the backend is not asked again: the remaining datasets are marked without waiting. */
    @Test
    void datasetsAfterAFailedLookupAreMarkedWithoutAsking() {
        resource.submit(modelRecord("complete", UNREGISTERED_DATASET, RECORD_ID).getBytes(StandardCharsets.UTF_8));
        fake.unreachable.add(UNREGISTERED_DATASET);
        fake.lookups.clear();

        List<IngestResource.TrainingDataResponse> trainingData = ((IngestResource.RecordResponse)
                        resource.status(MODEL_RECORD_ID).getEntity())
                .trainingData();

        trainingData.forEach(entry -> assertEquals(Boolean.TRUE, entry.lookupFailed()));
        assertNull(fake.lookups.get(RECORD_ID), "no lookup after the backend failed");
    }

    /** Only a VALIDATED model record's references are resolved; an INCOMPLETE one is not a published claim. */
    @Test
    void statusOfAnIncompleteTrainingRecordHasNoTrainingData() {
        resource.submit(modelRecord("not-a-disclosure", RECORD_ID).getBytes(StandardCharsets.UTF_8));

        IngestResource.RecordResponse body =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();

        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertNull(body.trainingData());
    }

    @Test
    void statusOfAnUndisclosedTrainingRecordHasNoTrainingData() {
        resource.submit(modelRecord("undisclosed").getBytes(StandardCharsets.UTF_8));

        IngestResource.RecordResponse body =
                (IngestResource.RecordResponse) resource.status(MODEL_RECORD_ID).getEntity();

        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertNull(body.trainingData());
    }

    /** A TrainingProvenanceRecord under {@link #MODEL_RECORD_ID}; no datasets means no trainingData list. */
    private static String modelRecord(String disclosure, String... datasets) {
        String trainingData = datasets.length == 0
                ? ""
                : Arrays.stream(datasets)
                        .map(dataset -> "{ \"dataset\": \"" + dataset
                                + "\", \"phase\": \"pretraining\", \"tokens\": 1000, \"share\": 0.5 }")
                        .collect(Collectors.joining(", ", ", \"trainingData\": [", "]"));
        return """
                {
                  "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
                  "id": "%s",
                  "type": "TrainingProvenanceRecord",
                  "identifier": "https://example.org/models/ingest-test",
                  "name": "Ingest endpoint test model",
                  "creator": [ { "type": "schema:Organization", "name": "Example Organization" } ],
                  "created": "2026-10-24T00:00:00Z",
                  "disclosure": "%s"%s
                }"""
                .formatted(MODEL_RECORD_ID, disclosure, trainingData);
    }

    private SourcelumeDatasetDto storedDataset() {
        return (SourcelumeDatasetDto) fake.getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, RECORD_ID);
    }

    private static String trainingRecord() {
        try {
            String body = Files.readString(
                    Path.of("src/test/resources/records/it-training-valid.jsonld"), StandardCharsets.UTF_8);
            return body.replaceFirst("\"id\":\\s*\"[^\"]*\"", "\"id\": \"" + RECORD_ID + "\"");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not read it-training-valid.jsonld", e);
        }
    }

    /**
     * In-memory stand-in for the Atlas backend: a map keyed by
     * (type, qualifiedName), like Atlas — or cross-type collisions stay
     * invisible to the tests.
     */
    static class FakeAtlasAdapter implements AtlasAdapter {

        record StoreKey(String entityType, String qualifiedName) {}

        final java.util.Map<StoreKey, RecordEntityDto> store = new java.util.HashMap<>();

        /** Lookups by qualified name, and the names whose lookup fails as if Atlas were down. */
        final java.util.Map<String, Integer> lookups = new java.util.HashMap<>();

        final java.util.Set<String> unreachable = new java.util.HashSet<>();

        @Override
        public boolean isServerReady() {
            return true;
        }

        @Override
        public TypeDefinitionModel loadTypeDefs(String resourcePath) {
            return null;
        }

        @Override
        public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel typeDefs) {
            return null;
        }

        @Override
        public String createOrUpdateRecordEntity(RecordEntityDto record) {
            store.put(new StoreKey(record.getTypeName(), record.getQualifiedName()), record);
            return record.getGuid() != null ? record.getGuid() : "guid-1";
        }

        @Override
        public RecordEntityDto getRecordEntityByQualifiedName(String entityType, String qualifiedName) {
            lookups.merge(qualifiedName, 1, Integer::sum);
            if (unreachable.contains(qualifiedName)) {
                throw new AtlasAdapterException("Atlas unreachable", 503, null);
            }
            return store.get(new StoreKey(entityType, qualifiedName));
        }

        @Override
        public void deleteRecordEntity(String entityType, String qualifiedName) {
            store.remove(new StoreKey(entityType, qualifiedName));
        }

        @Override
        public RecordEntityDto getRecordEntityByGuid(String guid) {
            return null;
        }

        @Override
        public List<RecordEntityDto> findRecordEntitiesByStatus(String entityType, RecordStatus status, int limit) {
            return store.values().stream()
                    .filter(dto -> entityType.equals(dto.getTypeName()) && status == dto.getRecordStatus())
                    .limit(limit)
                    .toList();
        }
    }
}

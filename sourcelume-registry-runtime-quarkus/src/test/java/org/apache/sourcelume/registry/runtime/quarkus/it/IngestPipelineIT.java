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
package org.apache.sourcelume.registry.runtime.quarkus.it;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.runtime.quarkus.storage.AtlasCompatibilityProducer;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * End-to-end ingest against a real Apache Atlas 2.5.0 backend: the
 * synchronous pipeline — REST submission, inline validation, verdict
 * storage — automated as the counterpart of the manual docker-compose
 * round.
 *
 * <p>Each test rewrites the record id to a unique IRI: the Atlas
 * instance is shared across tests and stores what earlier tests
 * submitted.
 */
@QuarkusTest
@TestProfile(AtlasITProfile.class)
class IngestPipelineIT {

    @Inject
    AtlasCompatibilityProducer atlasCompatibility;

    @Inject
    RegistryPlugin plugin;

    @Inject
    SourcelumeAtlasProperties atlasProperties;

    @Test
    void validRecordIsAnsweredWithItsVerdict() throws Exception {
        String recordId = uniqueId("valid");
        String body = fixture("/records/it-valid.jsonld", recordId);

        String location = given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .body("qualifiedName", org.hamcrest.Matchers.equalTo(recordId))
                .body("recordStatus", org.hamcrest.Matchers.equalTo("VALIDATED"))
                .extract()
                .header("Location");

        // The status view agrees, with the mapped attributes.
        given().urlEncodingEnabled(false)
                .when()
                .get(location.replaceFirst("^https?://[^/]+", ""))
                .then()
                .statusCode(200)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("VALIDATED"))
                .body("name", org.hamcrest.Matchers.equalTo("Minimal example record"))
                .body("licenseId", org.hamcrest.Matchers.equalTo("https://www.apache.org/licenses/LICENSE-2.0"));

        SourcelumeDatasetDto stored = storedDataset(recordId);
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES, stored.getValidationIssues());
        assertEquals(64, stored.getSha256().length(), "the entity carries the received-bytes digest");
        assertTrue(stored.getValidatedBy().contains("json"), "the entity names the chain");
        assertNotNull(stored.getValidatedAt(), "the entity carries the verdict timestamp");
    }

    @Test
    void invalidRecordBecomesIncompleteWithStoredIssues() throws Exception {
        String recordId = uniqueId("invalid");
        String body = fixture("/records/it-invalid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"));

        SourcelumeDatasetDto stored = storedDataset(recordId);
        assertEquals(RecordStatus.INCOMPLETE, stored.getRecordStatus());
        assertNotNull(stored.getValidationIssues(), "the failure report must be stored on the record");
        assertTrue(stored.getValidationIssues().toLowerCase().contains("license"));
    }

    @Test
    void correctedResubmissionClearsIssuesAndValidates() throws Exception {
        String recordId = uniqueId("resubmit");
        String invalid = fixture("/records/it-invalid.jsonld", recordId);
        String valid = fixture("/records/it-valid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(invalid)
                .when()
                .post("/records")
                .then()
                .statusCode(201);

        // The corrected record is validated in place of the old verdict.
        given().contentType("application/ld+json")
                .body(valid)
                .when()
                .post("/records")
                .then()
                .statusCode(200)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("VALIDATED"));

        SourcelumeDatasetDto stored = storedDataset(recordId);
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES, stored.getValidationIssues());
    }

    @Test
    void validatedRecordRejectsDuplicateSubmission() throws Exception {
        String recordId = uniqueId("duplicate");
        String body = fixture("/records/it-valid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(201);
        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(409)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("VALIDATED"));
    }

    @Test
    void trainingRecordValidatesOntoTheModelEntity() throws Exception {
        String recordId = uniqueId("training");
        String body = fixture("/records/it-training-valid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .body("qualifiedName", org.hamcrest.Matchers.equalTo(recordId))
                .body("type", org.hamcrest.Matchers.equalTo("TrainingProvenanceRecord"))
                .body("recordStatus", org.hamcrest.Matchers.equalTo("VALIDATED"));

        // The status view answers the model attributes.
        given().urlEncodingEnabled(false)
                .when()
                .get("/records/" + java.net.URLEncoder.encode(recordId, StandardCharsets.UTF_8))
                .then()
                .statusCode(200)
                .body("type", org.hamcrest.Matchers.equalTo("TrainingProvenanceRecord"))
                .body("modelUri", org.hamcrest.Matchers.equalTo("https://example.org/models/it-model"));

        SourcelumeModelDto stored = (SourcelumeModelDto)
                atlasCompatibility.adapter().getRecordEntityByQualifiedName(SourcelumeModelDto.TYPE_NAME, recordId);
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals("https://example.org/models/it-model", stored.getModelUri());
        assertEquals("complete", stored.getDisclosure());
    }

    @Test
    void trainingRecordCannotReplaceAnIncompleteDataset() throws Exception {
        String recordId = uniqueId("type-conflict");
        String typeless = fixture("/records/it-valid.jsonld", recordId).replace("\"type\": \"ProvenanceRecord\",", "");

        given().contentType("application/ld+json")
                .body(typeless)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"));
        SourcelumeDatasetDto original = storedDataset(recordId);

        String training = fixture("/records/it-training-valid.jsonld", recordId);
        given().contentType("application/ld+json")
                .body(training)
                .when()
                .post("/records")
                .then()
                .statusCode(409)
                .body("entityType", org.hamcrest.Matchers.equalTo(SourcelumeDatasetDto.TYPE_NAME))
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"))
                .body("reason", org.hamcrest.Matchers.containsString("new id"));

        given().urlEncodingEnabled(false)
                .when()
                .get("/records/" + java.net.URLEncoder.encode(recordId, StandardCharsets.UTF_8))
                .then()
                .statusCode(200)
                .body("type", org.hamcrest.Matchers.equalTo("ProvenanceRecord"))
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"))
                .body("sha256", org.hamcrest.Matchers.equalTo(original.getSha256()))
                .body("validationIssues", org.hamcrest.Matchers.equalTo(original.getValidationIssues()))
                .body("validatedBy", org.hamcrest.Matchers.equalTo(original.getValidatedBy()))
                .body("validatedAt", org.hamcrest.Matchers.equalTo(original.getValidatedAt()));

        SourcelumeDatasetDto retained = storedDataset(recordId);
        assertEquals(original.getGuid(), retained.getGuid());
        assertEquals(typeless, retained.getRawJsonLd());
        assertNull(
                atlasCompatibility.adapter().getRecordEntityByQualifiedName(SourcelumeModelDto.TYPE_NAME, recordId),
                "a conflicting submission must not create a model record");
    }

    @Test
    void bootstrapIsIdempotentOnAnExistingTypeDef() {
        // The app boot already registered the type definitions; a second
        // registration must succeed on the existing type (mandatory
        // attributes cannot be added to an existing Atlas type).
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(plugin::initialize);
    }

    @Test
    void storageSpiPreservesLargeUtf8DocumentsAndReplacesAttachments() {
        String id = uniqueId("attachments");
        var store = plugin.recordStore();
        String text = "{\"id\":\"" + id + "\",\"padding\":\"" + "雪".repeat(50000) + "\"}";
        var document = org.apache.sourcelume.registry.spi.RawDocument.builder()
                .bytes(text.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .build();
        var attachment = org.apache.sourcelume.registry.spi.RecordAttachment.builder()
                .name("signature")
                .mediaType("application/jose")
                .documentSha256(document.sha256())
                .bytes(new byte[] {0, 1, 2, (byte) 255})
                .build();
        var original = org.apache.sourcelume.registry.spi.RecordEnvelope.builder()
                .id(id)
                .type("ProvenanceRecord")
                .document(document)
                .validation(org.apache.sourcelume.registry.spi.ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .build())
                .attributes(org.apache.sourcelume.registry.spi.RecordAttributes.builder()
                        .name("sample")
                        .description("remove me")
                        .licenseId("remove me too")
                        .build())
                .attachments(java.util.List.of(attachment))
                .build();
        var first = store.write(org.apache.sourcelume.registry.spi.WriteRequest.builder()
                .candidate(original)
                .condition(org.apache.sourcelume.registry.spi.WriteCondition.absent())
                .build());
        assertEquals(original, store.get(id).orElseThrow().record());
        var replacement = org.apache.sourcelume.registry.spi.RecordEnvelope.builder()
                .id(id)
                .type("ProvenanceRecord")
                .document(document)
                .validation(org.apache.sourcelume.registry.spi.ValidationMetadata.builder()
                        .status(RecordStatus.VALIDATED)
                        .build())
                .attributes(org.apache.sourcelume.registry.spi.RecordAttributes.builder()
                        .build())
                .build();
        store.write(org.apache.sourcelume.registry.spi.WriteRequest.builder()
                .candidate(replacement)
                .condition(org.apache.sourcelume.registry.spi.WriteCondition.atRevision(
                        first.stored().revision()))
                .build());
        var stored = store.get(id).orElseThrow();
        assertEquals(replacement, stored.record(), "Optional Atlas attributes must be cleared, not retained by upsert");
        org.junit.jupiter.api.Assertions.assertNotEquals(first.stored().revision(), stored.revision());
    }

    @Test
    void legacyAtlasViewsUseTheSelectedAtlasPlugin() {
        given().get("/api/v1/types/dataset")
                .then()
                .statusCode(200)
                .body("entityDefs[0].name", org.hamcrest.Matchers.equalTo("sourcelume_dataset"));
    }

    @Test
    void bulkReadsSeeFreshRecordsAcrossBatchesAndOmitDeletedRecords() {
        String prefix = uniqueId("bulk") + "/" + "segment-".repeat(50);
        var ids = new java.util.ArrayList<String>();
        var adapter = atlasCompatibility.adapter();
        for (int i = 0; i < 40; i++) {
            String id = prefix + i;
            ids.add(id);
            org.apache.sourcelume.registry.common.dto.RecordEntityDto entity =
                    i % 2 == 0 ? new SourcelumeDatasetDto() : new SourcelumeModelDto();
            entity.setQualifiedName(id);
            entity.setName("bulk-" + i);
            entity.setRawJsonLd("{ \"fresh\": true }\n");
            entity.setRecordStatus(RecordStatus.INCOMPLETE);
            adapter.createOrUpdateRecordEntity(entity);
        }
        String deleted = ids.get(0);
        adapter.deleteRecordEntity(SourcelumeDatasetDto.TYPE_NAME, deleted);
        String present = ids.get(1);
        ids.add(present);
        ids.add(prefix + "missing");
        var found = plugin.recordStore().getAll(ids);
        assertEquals(39, found.size());
        assertTrue(!found.containsKey(deleted));
        assertTrue(!found.containsKey(prefix + "missing"));
        assertEquals(plugin.recordStore().get(present).orElseThrow(), found.get(present));
        assertEquals("TrainingProvenanceRecord", found.get(present).record().type());
        assertTrue(found.values().stream().allMatch(r -> r.record().document() != null));
    }

    @Test
    void facetsQueryExactReferencesAndClearOnCorrection() throws Exception {
        String id = uniqueId("facets");
        String dataset = id + "/Dataset";
        String creator = "Team 雪 \"A\" " + id;
        RecordFacets facets = RecordFacets.builder()
                .add(RecordFacet.TRAINING_DATASET, dataset)
                .add(RecordFacet.TRAINING_DATASET, id + "/Other")
                .add(RecordFacet.CREATOR_NAME, creator)
                .build();
        RecordEnvelope record = RecordEnvelope.builder()
                .id(id)
                .type("TrainingProvenanceRecord")
                .document(RawDocument.builder()
                        .bytes("{}".getBytes(StandardCharsets.UTF_8))
                        .build())
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.INCOMPLETE)
                        .build())
                .facets(facets)
                .build();
        WriteResult first = plugin.recordStore()
                .write(WriteRequest.builder()
                        .candidate(record)
                        .condition(WriteCondition.absent())
                        .build());
        assertEquals(facets, plugin.recordStore().get(id).orElseThrow().record().facets());
        SearchQuery query = SearchQuery.builder()
                .type(record.type())
                .status(RecordStatus.INCOMPLETE)
                .facets(RecordFacets.builder()
                        .add(RecordFacet.TRAINING_DATASET, "urn:missing")
                        .add(RecordFacet.TRAINING_DATASET, dataset)
                        .add(RecordFacet.CREATOR_NAME, creator)
                        .build())
                .build();
        awaitFacetIds(query, java.util.Set.of(id));
        for (String wrong : java.util.List.of(dataset.toLowerCase(java.util.Locale.ROOT), id + "/Data")) {
            awaitFacetIds(
                    SearchQuery.builder()
                            .type(record.type())
                            .status(RecordStatus.INCOMPLETE)
                            .facets(RecordFacets.builder()
                                    .add(RecordFacet.TRAINING_DATASET, wrong)
                                    .build())
                            .build(),
                    java.util.Set.of());
        }
        awaitFacetIds(
                SearchQuery.builder()
                        .type(record.type())
                        .status(RecordStatus.INCOMPLETE)
                        .facets(RecordFacets.builder()
                                .add(RecordFacet.TRAINING_DATASET, dataset)
                                .add(RecordFacet.CREATOR_NAME, "wrong creator")
                                .build())
                        .build(),
                java.util.Set.of());
        RecordEnvelope replacement = RecordEnvelope.builder()
                .id(id)
                .type(record.type())
                .document(record.document())
                .validation(record.validation())
                .build();
        plugin.recordStore()
                .write(WriteRequest.builder()
                        .candidate(replacement)
                        .condition(WriteCondition.atRevision(first.stored().revision()))
                        .build());
        awaitFacetIds(query, java.util.Set.of());
        assertEquals(replacement, plugin.recordStore().get(id).orElseThrow().record());
    }

    @ParameterizedTest
    @ValueSource(ints = {40, 41, 161, 600})
    void manyTrainingReferencesCanBeStoredQueriedAndReplaced(int references) throws Exception {
        String id = uniqueId("many-facets-" + references);
        ObjectNode body = (ObjectNode) new ObjectMapper().readTree(fixture("/records/it-training-valid.jsonld", id));
        body.remove("created"); // Keep the record correctable while exercising the HTTP ingestion path.
        ((ObjectNode) body.withArray("creator").get(0)).put("name", id);
        var trainingData = body.putArray("trainingData");
        for (int i = 0; i < references; i++) {
            trainingData.addObject().put("dataset", id + "/before/" + i).put("phase", "pretraining");
        }
        String original = body.toString();
        given().contentType("application/ld+json")
                .body(original)
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"));
        var stored = plugin.recordStore().get(id).orElseThrow().record();
        assertEquals(original, stored.document().text());
        assertEquals(
                references,
                stored.facets().values().get(RecordFacet.TRAINING_DATASET).size());
        SearchQuery before = trainingReferenceQuery(id, id + "/before/" + (references - 1));
        awaitFacetIds(before, java.util.Set.of(id));

        trainingData.removeAll();
        for (int i = 0; i < references; i++) {
            trainingData.addObject().put("dataset", id + "/after/" + i).put("phase", "pretraining");
        }
        String replacement = body.toString();
        given().contentType("application/ld+json")
                .body(replacement)
                .post("/records")
                .then()
                .statusCode(200)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("INCOMPLETE"));
        assertEquals(
                replacement,
                plugin.recordStore().get(id).orElseThrow().record().document().text());
        awaitFacetIds(before, java.util.Set.of());
        awaitFacetIds(trainingReferenceQuery(id, id + "/after/" + (references - 1)), java.util.Set.of(id));
        assertTrue(plugin.isReady(), "Large facet sets must not quarantine subsequent writes");
    }

    @Test
    void legacyFacetIndexRemainsSearchableAndCanBeReplacedWithManyReferences() throws Exception {
        var json = new ObjectMapper();
        var adapter = atlasCompatibility.adapter();
        var definitions = adapter.loadTypeDefs(atlasProperties.typedefsResource());
        var oldIndexedDefinitions = json.readTree(definitions.getRawJson());
        for (var entity : oldIndexedDefinitions.path("entityDefs")) {
            for (var attribute : entity.path("attributeDefs")) {
                if ("recordFacetIndex".equals(attribute.path("name").asText())) {
                    ((ObjectNode) attribute).put("isIndexable", true);
                }
            }
        }
        // Recreate the old composite index. Updating the typedef cannot remove it.
        adapter.registerOrUpdateTypeDefs(new TypeDefinitionModel(
                "legacy-facet-index", json.writeValueAsBytes(oldIndexedDefinitions), definitions.getEntityDefCount()));
        String id = uniqueId("legacy-facets");
        String dataset = id + "/old";
        String token = java.util.HexFormat.of()
                .formatHex(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(("TRAINING_DATASET\n" + dataset).getBytes(StandardCharsets.UTF_8)));
        ObjectNode payload = json.createObjectNode();
        ObjectNode attributes = payload.putArray("entities")
                .addObject()
                .put("typeName", SourcelumeModelDto.TYPE_NAME)
                .putObject("attributes");
        attributes.put("qualifiedName", id);
        attributes.put("name", id);
        attributes.put("rawJsonLd", "{}");
        attributes.put("recordStatus", "INCOMPLETE");
        attributes.put(
                "recordFacets",
                json.writeValueAsString(java.util.Map.of("TRAINING_DATASET", java.util.List.of(dataset))));
        attributes.put("recordFacetIndex", token);
        try {
            given().auth()
                    .preemptive()
                    .basic(atlasProperties.user(), atlasProperties.resolvedPassword())
                    .contentType("application/json")
                    .body(payload.toString())
                    .post(atlasProperties.url() + "/api/atlas/v2/entity/bulk")
                    .then()
                    .statusCode(200);
        } finally {
            plugin.initialize();
        }
        SearchQuery oldQuery = SearchQuery.builder()
                .type("TrainingProvenanceRecord")
                .status(RecordStatus.INCOMPLETE)
                .facets(RecordFacets.builder()
                        .add(RecordFacet.TRAINING_DATASET, dataset)
                        .build())
                .limit(1)
                .build();
        awaitFacetIds(oldQuery, java.util.Set.of(id));
        var old = plugin.recordStore().get(id).orElseThrow();
        assertEquals(java.util.Set.of(dataset), old.record().facets().values().get(RecordFacet.TRAINING_DATASET));
        var facets = RecordFacets.builder().add(RecordFacet.CREATOR_NAME, id);
        for (int i = 0; i < 600; i++) {
            facets.add(RecordFacet.TRAINING_DATASET, id + "/new/" + i);
        }
        var replacement = RecordEnvelope.builder()
                .id(id)
                .type(old.record().type())
                .document(old.record().document())
                .validation(old.record().validation())
                .facets(facets.build())
                .build();
        plugin.recordStore()
                .write(WriteRequest.builder()
                        .candidate(replacement)
                        .condition(WriteCondition.atRevision(old.revision()))
                        .build());
        assertEquals(replacement, plugin.recordStore().get(id).orElseThrow().record());
        awaitFacetIds(oldQuery, java.util.Set.of());
        awaitFacetIds(trainingReferenceQuery(id, id + "/new/599"), java.util.Set.of(id));
        assertTrue(plugin.isReady());
    }

    private SearchQuery trainingReferenceQuery(String creator, String dataset) {
        return SearchQuery.builder()
                .type("TrainingProvenanceRecord")
                .status(RecordStatus.INCOMPLETE)
                .facets(RecordFacets.builder()
                        .add(RecordFacet.CREATOR_NAME, creator)
                        .add(RecordFacet.TRAINING_DATASET, dataset)
                        .build())
                .limit(1)
                .build();
    }

    private void awaitFacetIds(SearchQuery query, java.util.Set<String> expected) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
        java.util.Set<String> found;
        do {
            found = plugin.recordSearch().search(query).stream()
                    .map(v -> v.record().id())
                    .collect(java.util.stream.Collectors.toSet());
            if (found.equals(expected)) return;
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        assertEquals(expected, found);
    }

    // --- helpers ---

    private static String uniqueId(String test) {
        return "https://sourcelume.apache.org/records/it-" + test + "-" + UUID.randomUUID();
    }

    private SourcelumeDatasetDto storedDataset(String recordId) {
        return (SourcelumeDatasetDto)
                atlasCompatibility.adapter().getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, recordId);
    }

    private static String fixture(String resource, String recordId) throws IOException {
        try (InputStream in = IngestPipelineIT.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("fixture not found: " + resource);
            }
            String body = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            // Unique IRI per test: the shared Atlas instance keeps every
            // submitted record, and the id doubles as the qualified name.
            return body.replaceFirst("\"id\":\\s*\"[^\"]*\"", "\"id\": \"" + recordId + "\"");
        }
    }
}

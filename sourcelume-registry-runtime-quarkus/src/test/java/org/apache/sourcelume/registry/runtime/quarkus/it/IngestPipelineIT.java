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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.apache.sourcelume.registry.ingest.worker.service.IngestServiceImpl;
import org.apache.sourcelume.registry.runtime.quarkus.bootstrap.AtlasBootstrapService;
import org.junit.jupiter.api.Test;

/**
 * End-to-end ingest pipeline against a real Apache Atlas 2.5.0 backend:
 * REST submission, the exact worker promotion code, and status queries —
 * the automated counterpart of the manual docker-compose round.
 *
 * <p>The promotion runs inline (the worker has no REST trigger, its
 * scheduler drives the same {@code IngestService} implementation), so the
 * whole chain is exercised synchronously per test. Each test rewrites the
 * record id to a unique IRI: the Atlas instance is shared across tests and
 * stores what earlier tests submitted.
 */
@QuarkusTest
@TestProfile(AtlasITProfile.class)
class IngestPipelineIT {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    AtlasAdapter atlasAdapter;

    @Inject
    AtlasBootstrapService bootstrapService;

    @Test
    void validRecordIsPromotedToActive() throws Exception {
        String recordId = uniqueId("valid");
        String body = fixture("/records/it-valid.jsonld", recordId);

        String location = given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(202)
                .extract()
                .header("Location");

        promote(recordId);

        // The location carries the percent-encoded record IRI; REST Assured
        // must not re-encode it (the server route matches the encoded form).
        given().urlEncodingEnabled(false)
                .when()
                .get(location.replaceFirst("^https?://[^/]+", ""))
                .then()
                .statusCode(200)
                .body("qualifiedName", org.hamcrest.Matchers.equalTo(recordId))
                .body("recordStatus", org.hamcrest.Matchers.equalTo("ACTIVE"))
                .body("name", org.hamcrest.Matchers.equalTo("Minimal example record"))
                .body("licenseId", org.hamcrest.Matchers.equalTo("https://www.apache.org/licenses/LICENSE-2.0"));

        // The ACTIVE view must not surface a stale or sentinel issue list.
        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
        assertEquals(RecordStatus.ACTIVE, stored.getRecordStatus());
        assertEquals(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES, stored.getValidationIssues());
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
                .statusCode(202);

        promote(recordId);

        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
        assertEquals(RecordStatus.INCOMPLETE, stored.getRecordStatus());
        assertNotNull(stored.getValidationIssues(), "the failure report must be stored on the record");
        assertTrue(stored.getValidationIssues().toLowerCase().contains("license"));
    }

    @Test
    void correctedResubmissionClearsIssuesAndPromotes() throws Exception {
        String recordId = uniqueId("resubmit");
        String invalid = fixture("/records/it-invalid.jsonld", recordId);
        String valid = fixture("/records/it-valid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(invalid)
                .when()
                .post("/records")
                .then()
                .statusCode(202);
        promote(recordId);

        // The corrected record goes back to PENDING, old issues cleared.
        given().contentType("application/ld+json")
                .body(valid)
                .when()
                .post("/records")
                .then()
                .statusCode(202);
        SourcelumeDatasetDto pending = atlasAdapter.getDatasetByQualifiedName(recordId);
        assertEquals(RecordStatus.PENDING, pending.getRecordStatus());
        assertEquals(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES, pending.getValidationIssues());

        promote(recordId);

        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
        assertEquals(RecordStatus.ACTIVE, stored.getRecordStatus());
    }

    @Test
    void duplicateSubmissionIsRejectedWhilePending() throws Exception {
        String recordId = uniqueId("duplicate");
        String body = fixture("/records/it-valid.jsonld", recordId);

        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(202);
        given().contentType("application/ld+json")
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(409)
                .body("recordStatus", org.hamcrest.Matchers.equalTo("PENDING"));
    }

    @Test
    void bootstrapIsIdempotentOnAnExistingTypeDef() {
        // The app boot already registered the type definitions; a second
        // registration must succeed on the existing type (mandatory
        // attributes cannot be added to an existing Atlas type).
        assertTrue(
                bootstrapService.registerTypeDefs(bootstrapService.loadTypeDefs()),
                "re-registering the type definitions on an existing type must not fail");
    }

    // --- helpers ---

    /**
     * Runs the worker's promotion step inline: the same validator chain and
     * the same {@code IngestService} implementation the scheduler calls.
     */
    private void promote(String recordId) {
        IngestServiceImpl ingestService = new IngestServiceImpl(ValidatorChain.discover(), atlasAdapter, MAPPER);
        SourcelumeDatasetDto pending = atlasAdapter.getDatasetByQualifiedName(recordId);
        assertNotNull(pending, "the submitted record must be readable before promotion");
        assertEquals(RecordStatus.PENDING, pending.getRecordStatus());
        IngestResult result = ingestService.process(pending);
        assertEquals(recordId, result.qualifiedName());
        assertNotNull(result.validation(), "promotion must produce a verdict");
    }

    private static String uniqueId(String test) {
        return "https://sourcelume.apache.org/records/it-" + test + "-" + UUID.randomUUID();
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

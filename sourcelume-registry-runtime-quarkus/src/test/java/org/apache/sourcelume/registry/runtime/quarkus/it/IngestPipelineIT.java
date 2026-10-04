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
import org.apache.sourcelume.registry.runtime.quarkus.bootstrap.AtlasBootstrapService;
import org.junit.jupiter.api.Test;

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
    AtlasAdapter atlasAdapter;

    @Inject
    AtlasBootstrapService bootstrapService;

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

        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
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

        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
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

        SourcelumeDatasetDto stored = atlasAdapter.getDatasetByQualifiedName(recordId);
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
    void bootstrapIsIdempotentOnAnExistingTypeDef() {
        // The app boot already registered the type definitions; a second
        // registration must succeed on the existing type (mandatory
        // attributes cannot be added to an existing Atlas type).
        assertTrue(
                bootstrapService.registerTypeDefs(bootstrapService.loadTypeDefs()),
                "re-registering the type definitions on an existing type must not fail");
    }

    // --- helpers ---

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

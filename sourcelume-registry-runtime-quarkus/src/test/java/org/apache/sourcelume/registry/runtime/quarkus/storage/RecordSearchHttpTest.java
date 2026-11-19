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
package org.apache.sourcelume.registry.runtime.quarkus.storage;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The search endpoint answers from the storage plugin's {@code RecordSearch},
 * so it works on every backend. These tests run against the in-memory plugin
 * and cover the HTTP contract: required parameters, facet AND/OR semantics,
 * the limit, and the deliberate absence of resolved references.
 */
@QuarkusTest
@TestProfile(MemoryStorageProfile.class)
class RecordSearchHttpTest {

    private static final String DATASET =
            """
            {"id":"urn:search:dataset-a","type":"ProvenanceRecord","name":"Dataset A",
             "license":"https://spdx.org/licenses/Apache-2.0",
             "creator":[{"type":"schema:Organization","name":"Example Org"}]}
            """;

    private static final String OTHER_DATASET =
            """
            {"id":"urn:search:dataset-b","type":"ProvenanceRecord","name":"Dataset B",
             "license":"https://spdx.org/licenses/MIT",
             "creator":[{"type":"schema:Organization","name":"Other Org"}]}
            """;

    private static final String MODEL =
            """
            {"id":"urn:search:model","type":"TrainingProvenanceRecord","name":"Model",
             "license":"https://spdx.org/licenses/Apache-2.0",
             "creator":[{"type":"schema:Organization","name":"Example Org"}],
             "trainingData":[{"dataset":"urn:search:dataset-a"}]}
            """;

    @BeforeEach
    void seed() {
        for (String body : new String[] {DATASET, OTHER_DATASET, MODEL}) {
            given().contentType("application/ld+json")
                    .body(body.getBytes(StandardCharsets.UTF_8))
                    .post("/records")
                    .then()
                    .statusCode(anyOf(is(200), is(201)));
        }
    }

    @Test
    void returnsOnlyTheRequestedTypeAndStatus() {
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records.qualifiedName", hasItems("urn:search:dataset-a", "urn:search:dataset-b"))
                .body("records.qualifiedName", not(hasItem("urn:search:model")))
                .body("records.type", everyItem(equalTo("ProvenanceRecord")))
                .body("records.recordStatus", everyItem(equalTo("INCOMPLETE")));

        given().queryParam("type", "TrainingProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records.qualifiedName", contains("urn:search:model"));
    }

    @Test
    void nothingMatchesTheOtherStatus() {
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "VALIDATED")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records", empty());
    }

    @Test
    void facetValuesOfOneFacetAreOrAndDifferentFacetsAreAnd() {
        // One license value, one creator value: both must match.
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("license", "https://spdx.org/licenses/Apache-2.0")
                .queryParam("creatorName", "Example Org")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records.qualifiedName", contains("urn:search:dataset-a"));

        // Two license values: either may match.
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("license", "https://spdx.org/licenses/Apache-2.0")
                .queryParam("license", "https://spdx.org/licenses/MIT")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records.qualifiedName", hasItems("urn:search:dataset-a", "urn:search:dataset-b"));

        // Contradicting facets: no record carries both.
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("license", "https://spdx.org/licenses/MIT")
                .queryParam("creatorName", "Example Org")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records", empty());
    }

    @Test
    void filtersOnTrainingDataset() {
        given().queryParam("type", "TrainingProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("trainingDataset", "urn:search:dataset-a")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records.qualifiedName", contains("urn:search:model"));

        given().queryParam("type", "TrainingProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("trainingDataset", "urn:search:dataset-unknown")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records", empty());
    }

    @Test
    void appliesTheLimitAndReportsIt() {
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("limit", 1)
                .get("/records")
                .then()
                .statusCode(200)
                .body("limit", equalTo(1))
                .body("records", hasSize(1));
    }

    @Test
    void rejectsMissingOrInvalidParameters() {
        given().queryParam("status", "INCOMPLETE").get("/records").then().statusCode(400);
        given().queryParam("type", "ProvenanceRecord").get("/records").then().statusCode(400);
        given().queryParam("type", "NoSuchType")
                .queryParam("status", "INCOMPLETE")
                .get("/records")
                .then()
                .statusCode(400);
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "published")
                .get("/records")
                .then()
                .statusCode(400);
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("limit", 0)
                .get("/records")
                .then()
                .statusCode(400);
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("limit", 1001)
                .get("/records")
                .then()
                .statusCode(400);
        // A non-numeric limit is a bad request, not a missing route.
        given().queryParam("type", "ProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .queryParam("limit", "abc")
                .get("/records")
                .then()
                .statusCode(400);
    }

    @Test
    void doesNotResolveReferencesInSearchResults() {
        given().queryParam("type", "TrainingProvenanceRecord")
                .queryParam("status", "INCOMPLETE")
                .get("/records")
                .then()
                .statusCode(200)
                .body("records[0].trainingData", nullValue())
                .body("records[0].custodyChain", nullValue());
    }
}

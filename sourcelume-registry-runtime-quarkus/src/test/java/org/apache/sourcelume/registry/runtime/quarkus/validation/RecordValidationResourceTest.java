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
package org.apache.sourcelume.registry.runtime.quarkus.validation;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the pre-flight validation endpoint. The test profile
 * needs no Atlas backend: {@code POST /records/validate} is backend-free by
 * design. The Quarkus application must start with both plugins discovered
 * from the classpath (fail-on-start wiring, see ValidatorChainProducer);
 * the first test case proves that implicitly by the application booting.
 */
@QuarkusTest
class RecordValidationResourceTest {

    private static final String VALID_RECORD =
            """
            {
              "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
              "id": "https://sourcelume.apache.org/records/validate-endpoint-test",
              "type": "ProvenanceRecord",
              "identifier": "https://example.org/datasets/validate-endpoint-test",
              "name": "Validate endpoint test record",
              "license": "https://www.apache.org/licenses/LICENSE-2.0",
              "creator": [
                {
                  "type": "schema:Organization",
                  "name": "Example Organization"
                }
              ],
              "created": "2026-10-03T00:00:00Z",
              "added": "2026-10-03T00:00:00Z",
              "contentCreated": "2026-10-03T00:00:00Z",
              "origin": "A minimal conformant ProvenanceRecord.",
              "custodyChain": [
                {
                  "agent": "https://example.org/",
                  "action": "created",
                  "startTime": "2026-10-03T00:00:00Z"
                }
              ]
            }
            """;

    private static final String MISSING_LICENSE_RECORD =
            """
            {
              "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
              "id": "https://sourcelume.apache.org/records/missing-license",
              "type": "ProvenanceRecord",
              "identifier": "https://example.org/datasets/missing-license",
              "name": "Record without a license",
              "creator": [
                {
                  "type": "schema:Organization",
                  "name": "Example Organization"
                }
              ],
              "created": "2026-10-03T00:00:00Z",
              "added": "2026-10-03T00:00:00Z",
              "contentCreated": "2026-10-03T00:00:00Z",
              "origin": "No license on purpose.",
              "custodyChain": [
                {
                  "agent": "https://example.org/",
                  "action": "created",
                  "startTime": "2026-10-03T00:00:00Z"
                }
              ]
            }
            """;

    @Inject
    ValidatorChain validatorChain;

    @Test
    void chainIsWiredWithBothPluginsInOrder() {
        assertEquals(2, validatorChain.validators().size());
        assertEquals("json-schema", validatorChain.validators().get(0).id());
        assertEquals("shacl", validatorChain.validators().get(1).id());
    }

    @Test
    void conformingRecordReturns200() {
        given().contentType(ContentType.JSON)
                .body(VALID_RECORD)
                .when()
                .post("/records/validate")
                .then()
                .statusCode(200)
                .body("conforms", equalTo(true))
                .body("issues", hasSize(0));
    }

    @Test
    void recordMissingLicenseReturns422WithIssues() {
        given().contentType(ContentType.JSON)
                .body(MISSING_LICENSE_RECORD)
                .when()
                .post("/records/validate")
                .then()
                .statusCode(422)
                .body("conforms", equalTo(false))
                .body("issues.size()", is(1))
                .body("issues[0].validatorId", equalTo("json-schema"))
                .body("issues[0].severity", equalTo("VIOLATION"));
    }

    @Test
    void malformedJsonReturns422Not500() {
        given().contentType(ContentType.JSON)
                .body("{ not json ")
                .when()
                .post("/records/validate")
                .then()
                .statusCode(422)
                .body("conforms", equalTo(false));
    }

    @Test
    void emptyBodyIsRejectedByHttpLayer() {
        // no content at all: the endpoint requires a JSON body; Quarkus
        // answers 415 before the chain sees anything
        given().when().post("/records/validate").then().statusCode(415);
    }
}

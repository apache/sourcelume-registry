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

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * HTTP contracts for ingest, using the in-memory test Atlas adapter.
 * The full lifecycle semantics are covered by {@link IngestResourceTest}
 * and the integration tests against a real Atlas backend.
 */
@QuarkusTest
class IngestResourceHttpTest {

    @Test
    void wrongContentTypeIsRejected() {
        given().contentType(ContentType.TEXT)
                .body("{}")
                .when()
                .post("/records")
                .then()
                .statusCode(415);
    }

    @Test
    void bodyWithoutAnIdIsRejectedOverHttp() {
        given().contentType(ContentType.JSON)
                .body("{\"name\": \"no id here\"}")
                .when()
                .post("/records")
                .then()
                .statusCode(400);
    }

    @Test
    void bodyThatIsNotUtf8IsRejectedOverHttp() {
        given().contentType(ContentType.JSON)
                .body(new byte[] {'{', '"', (byte) 0xC0, '"', '}'})
                .when()
                .post("/records")
                .then()
                .statusCode(400);
    }

    @Test
    void typeConflictPreservesTheOriginalVerdictOverHttp() {
        String id = "https://sourcelume.apache.org/records/http-type-conflict-" + UUID.randomUUID();
        String original = "{\"id\":\"" + id + "\",\"type\":\"ProvenanceRecord\"}";
        var verdict = given().contentType(ContentType.JSON)
                .body(original)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"))
                .extract()
                .jsonPath();

        given().contentType(ContentType.JSON)
                .body("{\"id\":\"" + id + "\",\"type\":\"TrainingProvenanceRecord\"}")
                .when()
                .post("/records")
                .then()
                .statusCode(409)
                .body("qualifiedName", equalTo(id))
                .body("entityType", equalTo("sourcelume_dataset"))
                .body("recordStatus", equalTo("INCOMPLETE"))
                .body("reason", containsString("new id"));

        given().urlEncodingEnabled(false)
                .when()
                .get("/records/" + URLEncoder.encode(id, StandardCharsets.UTF_8))
                .then()
                .statusCode(200)
                .body("type", equalTo("ProvenanceRecord"))
                .body("recordStatus", equalTo("INCOMPLETE"))
                .body("sha256", equalTo(verdict.getString("sha256")))
                .body("validationIssues", equalTo(verdict.getString("validationIssues")))
                .body("validatedBy", equalTo(verdict.getString("validatedBy")))
                .body("validatedAt", equalTo(verdict.getString("validatedAt")));
    }
}

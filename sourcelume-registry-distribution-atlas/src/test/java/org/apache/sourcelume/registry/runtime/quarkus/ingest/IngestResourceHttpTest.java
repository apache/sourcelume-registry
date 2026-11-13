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
@io.quarkus.test.junit.TestProfile(org.apache.sourcelume.registry.runtime.quarkus.test.StubStorageProfile.class)
class IngestResourceHttpTest {

    @Test
    void rejectsDocumentsAboveTheStorageLimit() {
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        given().contentType("application/ld+json")
                .body(oversized)
                .post("/records")
                .then()
                .statusCode(413);
    }

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

    /**
     * The bad bytes sit inside a string value of an otherwise valid
     * record: a lenient decode would still parse and store the record,
     * so only the strict charset check answers 400.
     */
    @Test
    void bodyThatIsNotUtf8IsRejectedOverHttp() {
        byte[] head = "{\"id\":\"https://example.org/records/not-utf8\",\"name\":\"a".getBytes(StandardCharsets.UTF_8);
        byte[] tail = "b\"}".getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[head.length + 2 + tail.length];
        System.arraycopy(head, 0, body, 0, head.length);
        body[head.length] = (byte) 0xC0;
        body[head.length + 1] = (byte) 0xC1;
        System.arraycopy(tail, 0, body, head.length + 2, tail.length);

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", equalTo("Request body is not valid UTF-8"));
    }

    @Test
    void invalidUtf8BodyIsRejectedOverHttp() {
        byte[] body = "{\"id\":\"https://sourcelume.apache.org/records/http-utf8\",".getBytes(StandardCharsets.UTF_8);
        // Append two bytes that are not a valid UTF-8 sequence.
        byte[] invalid = new byte[body.length + 2];
        System.arraycopy(body, 0, invalid, 0, body.length);
        invalid[body.length] = (byte) 0xFF;
        invalid[body.length + 1] = (byte) 0xFE;

        given().contentType(ContentType.JSON)
                .body(invalid)
                .when()
                .post("/records")
                .then()
                .statusCode(400);
    }

    @Test
    void documentReturnsTheStoredBodyVerbatimOverHttp() {
        String id = "https://sourcelume.apache.org/records/http-document-" + UUID.randomUUID();
        String original = "{\"id\":\"" + id + "\",\"type\":\"ProvenanceRecord\"}";
        var verdict = given().contentType(ContentType.JSON)
                .body(original)
                .when()
                .post("/records")
                .then()
                .statusCode(201)
                .extract()
                .jsonPath();

        given().urlEncodingEnabled(false)
                .when()
                .get("/records/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/document")
                .then()
                .statusCode(200)
                .contentType(containsString("application/ld+json"))
                .header("ETag", containsString(verdict.getString("sha256")))
                .body(equalTo(original));
    }

    @Test
    void documentOfAnUnknownRecordIs404OverHttp() {
        String id = "https://sourcelume.apache.org/records/http-document-unknown-" + UUID.randomUUID();

        given().urlEncodingEnabled(false)
                .when()
                .get("/records/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + "/document")
                .then()
                .statusCode(404);
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

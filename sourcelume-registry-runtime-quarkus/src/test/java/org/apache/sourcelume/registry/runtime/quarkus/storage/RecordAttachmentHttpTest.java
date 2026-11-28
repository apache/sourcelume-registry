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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(RecordAttachmentHttpTest.OwnStore.class)
class RecordAttachmentHttpTest {
    /** A separate profile gives this test its own in-memory store, so its VALIDATED records stay out of other tests' searches. */
    public static class OwnStore extends MemoryStorageProfile {}

    private static final String VALID_RECORD =
            """
            {
              "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
              "id": "ID",
              "type": "ProvenanceRecord",
              "identifier": "https://example.org/datasets/attachment-test",
              "name": "Attachment test record — ü",
              "license": "https://www.apache.org/licenses/LICENSE-2.0",
              "creator": [ { "type": "schema:Organization", "name": "Example Organization" } ],
              "created": "2026-09-08T00:00:00Z",
              "added": "2026-09-08T00:00:00Z",
              "contentCreated": "2026-09-08T00:00:00Z",
              "origin": "Test record for record attachments.",
              "custodyChain": [ { "agent": "https://example.org/", "action": "created",
                                  "startTime": "2026-09-08T00:00:00Z" } ]
            }""";

    /** Not UTF-8 text: a signature must come back as the exact bytes, not decoded. */
    private static final byte[] SIGNATURE = {0, 1, (byte) 0xC0, (byte) 0xFF, '\r', '\n', 42};

    private static String newId() {
        return "https://sourcelume.apache.org/records/attachment-test-" + UUID.randomUUID();
    }

    private static byte[] valid(String id) {
        return VALID_RECORD.replace("\"ID\"", "\"" + id + "\"").getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] incomplete(String id) {
        return VALID_RECORD
                .replace("\"ID\"", "\"" + id + "\"")
                .replace("\"license\": \"https://www.apache.org/licenses/LICENSE-2.0\",", "")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static RequestSpecification withRecord(byte[] record) {
        return given().multiPart("record", "record.jsonld", record, "application/ld+json");
    }

    private static String path(String id, String suffix) {
        return "/records/" + URLEncoder.encode(id, StandardCharsets.UTF_8) + suffix;
    }

    @Test
    void recordAndSignatureAreStoredAndReturnedExactly() {
        String id = newId();
        byte[] record = valid(id);
        String sha256 = withRecord(record)
                .multiPart("attachment", "record.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("VALIDATED"))
                .extract()
                .path("sha256");

        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments"))
                .then()
                .statusCode(200)
                .body("qualifiedName", equalTo(id))
                .body("sha256", equalTo(sha256))
                .body("attachments.name", contains("record.jws"))
                .body("attachments[0].mediaType", equalTo("application/jose"))
                .body("attachments[0].size", equalTo(SIGNATURE.length));
        byte[] returned = given().urlEncodingEnabled(false)
                .get(path(id, "/attachments/record.jws"))
                .then()
                .statusCode(200)
                .contentType(startsWith("application/jose"))
                .header("Content-Disposition", equalTo("attachment; filename*=UTF-8''record.jws"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Content-Security-Policy", equalTo("sandbox"))
                .extract()
                .asByteArray();
        assertArrayEquals(SIGNATURE, returned);
        byte[] document = given().urlEncodingEnabled(false)
                .get(path(id, "/document"))
                .then()
                .statusCode(200)
                .extract()
                .asByteArray();
        assertArrayEquals(record, document);
    }

    @Test
    void aValidatedRecordKeepsItsAttachments() {
        String id = newId();
        withRecord(valid(id))
                .multiPart("attachment", "record.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(201);

        withRecord(valid(id))
                .multiPart("attachment", "other.jws", new byte[] {7}, "application/jose")
                .post("/records")
                .then()
                .statusCode(409);
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments"))
                .then()
                .statusCode(200)
                .body("attachments.name", contains("record.jws"));
    }

    @Test
    void aResubmissionReplacesTheRecordAndItsAttachmentsTogether() {
        String id = newId();
        withRecord(incomplete(id))
                .multiPart("attachment", "first.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"));

        withRecord(incomplete(id))
                .multiPart("attachment", "second.jws", new byte[] {7}, "application/jose")
                .post("/records")
                .then()
                .statusCode(200);
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments"))
                .then()
                .body("attachments.name", contains("second.jws"));

        // A plain JSON-LD resubmission carries no attachments, so none remain
        // bound to the new bytes.
        given().contentType("application/ld+json")
                .body(incomplete(id))
                .post("/records")
                .then()
                .statusCode(200);
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments"))
                .then()
                .statusCode(200)
                .body("attachments", empty());
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments/second.jws"))
                .then()
                .statusCode(404);
    }

    @Test
    void malformedSubmissionsAreRejectedAndNothingIsStored() {
        String id = newId();
        byte[] record = valid(id);

        given().multiPart("attachment", "record.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("'record'"));
        withRecord(record)
                .multiPart("attachments", "record.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("Unknown form part 'attachments'"));
        withRecord(record)
                .multiPart("attachment", "not a file")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("filename"));
        withRecord(record)
                .multiPart("attachment", "record.jws", SIGNATURE, "application/jose")
                .multiPart("attachment", "record.jws", SIGNATURE, "application/jose")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("Duplicate attachment name"));
        withRecord(record)
                .multiPart("attachment", "empty.jws", new byte[0], "application/jose")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("empty"));
        // A text field loses the exact bytes the digest must cover.
        given().multiPart("record", new String(record, StandardCharsets.UTF_8), "application/ld+json")
                .post("/records")
                .then()
                .statusCode(400)
                .body("message", containsString("as a file"));
        given().multiPart("record", "record.txt", record, "text/plain")
                .post("/records")
                .then()
                .statusCode(415);
        var tooMany = withRecord(record);
        for (int i = 0; i < 17; i++) tooMany.multiPart("attachment", "sig-" + i, new byte[] {1}, "application/jose");
        tooMany.post("/records").then().statusCode(413);
        // Checked on the uploaded sizes, before the content is read.
        withRecord(record)
                .multiPart("attachment", "a.jws", new byte[600 * 1024], "application/jose")
                .multiPart("attachment", "b.jws", new byte[600 * 1024], "application/jose")
                .post("/records")
                .then()
                .statusCode(413);
        byte[] oversized = new byte[4 * 1024 * 1024 + 1];
        java.util.Arrays.fill(oversized, (byte) ' ');
        given().multiPart("record", "record.jsonld", oversized, "application/ld+json")
                .post("/records")
                .then()
                .statusCode(413);

        given().urlEncodingEnabled(false).get(path(id, "/attachments")).then().statusCode(404);
    }

    @Test
    void anHtmlAttachmentIsServedForDownloadNotRendered() {
        String id = newId();
        withRecord(valid(id))
                .multiPart(
                        "attachment",
                        "page.html",
                        "<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8),
                        "text/html")
                .post("/records")
                .then()
                .statusCode(201);
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments/page.html"))
                .then()
                .statusCode(200)
                .header("Content-Disposition", startsWith("attachment"))
                .header("X-Content-Type-Options", equalTo("nosniff"))
                .header("Content-Security-Policy", equalTo("sandbox"));
    }

    @Test
    void unknownRecordsAndNamesAre404() {
        String id = newId();
        given().urlEncodingEnabled(false).get(path(id, "/attachments")).then().statusCode(404);
        withRecord(valid(id)).post("/records").then().statusCode(201);
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments"))
                .then()
                .statusCode(200)
                .body("attachments", empty());
        given().urlEncodingEnabled(false)
                .get(path(id, "/attachments/missing.jws"))
                .then()
                .statusCode(404)
                .body("message", containsString("missing.jws"));
    }
}

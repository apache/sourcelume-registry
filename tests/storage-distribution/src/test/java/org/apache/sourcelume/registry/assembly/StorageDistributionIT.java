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
package org.apache.sourcelume.registry.assembly;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Exercises the packaged application with a ServiceLoader provider and no Atlas dependencies. */
@QuarkusIntegrationTest
class StorageDistributionIT {
    @Test
    void distributionOmitsAtlasLibrariesAndCatalogRoutes() throws Exception {
        try (var libraries = Files.list(Path.of("target/quarkus-app/lib/main"))) {
            var names = libraries.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(name -> name.contains("sourcelume-registry-runtime-quarkus")));
            assertFalse(names.stream().anyMatch(name -> name.contains("atlas") || name.contains("registry-typedefs")));
        }
        given().queryParam("format", "json")
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("paths", hasKey("/records"))
                .body("paths", not(hasKey("/api/v1/datasets")))
                .body("paths", not(hasKey("/api/v1/types/typedefs")));
        given().get("/api/v1/datasets?qualifiedName=urn:assembly:one").then().statusCode(404);
        given().get("/api/v1/types/typedefs").then().statusCode(404);
    }

    @Test
    void packagedProviderSupportsRecordSubmissionCorrectionAndReadiness() {
        given().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.find { it.name == 'memory' }.status", equalTo("UP"));
        String body = "{\"id\":\"urn:assembly:one\",\"type\":\"ProvenanceRecord\",\"name\":\"雪\"}\n";
        given().contentType("application/ld+json")
                .body(body.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"));
        given().get("/records/urn:assembly:one/document").then().statusCode(200).body(equalTo(body));
        String correction = body.replace("雪", "updated");
        given().contentType("application/ld+json")
                .body(correction.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(200)
                .body("recordStatus", equalTo("INCOMPLETE"));
        given().get("/records/urn:assembly:one/document").then().statusCode(200).body(equalTo(correction));
        given().get("/records/urn:assembly:one").then().statusCode(200).body("recordStatus", equalTo("INCOMPLETE"));
        given().contentType("application/json")
                .body(correction)
                .post("/records/validate")
                .then()
                .statusCode(422)
                .body("conforms", equalTo(false));
        given().get("/q/health/ready").then().statusCode(200);
    }

    @Test
    void validatedRecordsAreImmutableAndTrainingReferencesResolve() throws Exception {
        byte[] dataset = fixture("valid-dataset.jsonld");
        given().contentType("application/json")
                .body(dataset)
                .post("/records/validate")
                .then()
                .statusCode(200)
                .body("conforms", equalTo(true));
        String datasetLocation = given().contentType("application/ld+json")
                .body(dataset)
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("VALIDATED"))
                .extract()
                .header("Location");

        String changed =
                new String(dataset, StandardCharsets.UTF_8).replace("Minimal example record", "Changed record");
        given().contentType("application/ld+json")
                .body(changed.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(409)
                .body("recordStatus", equalTo("VALIDATED"));
        String digest =
                HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(dataset));
        byte[] stored = given().urlEncodingEnabled(false)
                .get(datasetLocation + "/document")
                .then()
                .statusCode(200)
                .header("ETag", equalTo("\"" + digest + "\""))
                .extract()
                .asByteArray();
        assertArrayEquals(dataset, stored);

        String trainingLocation = given().contentType("application/ld+json")
                .body(fixture("valid-training.jsonld"))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("VALIDATED"))
                .extract()
                .header("Location");
        given().urlEncodingEnabled(false)
                .get(trainingLocation)
                .then()
                .statusCode(200)
                .body("type", equalTo("TrainingProvenanceRecord"))
                .body("recordStatus", equalTo("VALIDATED"))
                .body("trainingData[0].dataset", equalTo("https://sourcelume.apache.org/records/assembly-valid"))
                .body("trainingData[0].recordStatus", equalTo("VALIDATED"));
    }

    private static byte[] fixture(String name) throws IOException {
        try (var input = StorageDistributionIT.class.getResourceAsStream("/records/" + name)) {
            assertNotNull(input, "Missing fixture: " + name);
            return input.readAllBytes();
        }
    }
}

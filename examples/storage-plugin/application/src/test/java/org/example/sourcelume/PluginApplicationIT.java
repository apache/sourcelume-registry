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
package org.example.sourcelume;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

@QuarkusIntegrationTest
class PluginApplicationIT {
    @Test
    void packagedPluginServesRecordsAndReceivesItsConfiguration() throws Exception {
        try (var libraries = Files.list(Path.of("target/quarkus-app/lib/main"))) {
            var names = libraries.map(path -> path.getFileName().toString()).toList();
            assertTrue(names.stream().anyMatch(name -> name.contains("example-storage-plugin")));
            assertFalse(names.stream()
                    .anyMatch(name -> name.contains("atlas")
                            || name.contains("registry-postgres")
                            || name.contains("spi-tck")
                            || name.contains("junit")));
        }
        given().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.find { it.name == 'example' }.status", equalTo("UP"));
        String draft = "{\"id\":\"urn:example:one\",\"type\":\"ProvenanceRecord\",\"name\":\"雪\"}\n";
        given().contentType("application/ld+json")
                .body(draft.getBytes(StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"));
        given().get("/records/urn:example:one/document").then().statusCode(200).body(equalTo(draft));
        given().contentType("application/ld+json")
                .body(" ".repeat(4097))
                .post("/records")
                .then()
                .statusCode(413);
    }
}

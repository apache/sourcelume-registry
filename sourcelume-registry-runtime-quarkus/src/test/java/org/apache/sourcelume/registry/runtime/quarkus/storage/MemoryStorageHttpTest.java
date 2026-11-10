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
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(MemoryStorageProfile.class)
class MemoryStorageHttpTest {
    @Test
    void runsTheRecordApiAndReadinessWithoutOpeningAtlas() {
        given().get("/q/health/ready")
                .then()
                .statusCode(200)
                .body("checks.find { it.name == 'memory' }.status", equalTo("UP"));
        String body = "{\"id\":\"urn:memory:one\",\"type\":\"ProvenanceRecord\",\"name\":\"雪\"}";
        given().contentType("application/ld+json")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(201)
                .body("recordStatus", equalTo("INCOMPLETE"));
        given().get("/records/urn:memory:one/document").then().statusCode(200).body(equalTo(body));
        given().contentType("application/ld+json")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .post("/records")
                .then()
                .statusCode(200)
                .body("recordStatus", equalTo("INCOMPLETE"));
        given().contentType("application/json")
                .body(body.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .post("/records/validate")
                .then()
                .statusCode(422);
        given().get("/api/v1/datasets?qualifiedName=urn:memory:one").then().statusCode(404);
    }
}

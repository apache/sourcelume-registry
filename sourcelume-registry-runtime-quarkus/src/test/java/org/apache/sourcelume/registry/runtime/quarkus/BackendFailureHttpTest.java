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
package org.apache.sourcelume.registry.runtime.quarkus;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import java.net.ConnectException;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.runtime.quarkus.test.TestAtlasAdapter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@io.quarkus.test.junit.TestProfile(org.apache.sourcelume.registry.runtime.quarkus.test.StubStorageProfile.class)
class BackendFailureHttpTest {

    private static final String RECORD =
            """
            {"@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
             "id": "https://example.org/records/backend-failure", "type": "ProvenanceRecord"}
            """;

    @Inject
    TestAtlasAdapter atlasAdapter;

    @AfterEach
    void backendBack() {
        atlasAdapter.failWith(null);
    }

    @Test
    void everyBackendBackedEndpointAnswers503AsJsonWhenTheBackendIsDown() {
        atlasAdapter.failWith(
                new AtlasAdapterException("I/O error fetching entity: refused", 500, new ConnectException()));
        endpoints().forEach((name, request) -> {
            Response response = answer(name, request, 503);
            assertTrue(response.jsonPath().getString(errorField(name)).contains("unavailable"), name);
        });
    }

    @Test
    void anErrorFromTheBackendIs502WithoutTheBackendsAnswer() {
        atlasAdapter.failWith(
                new AtlasAdapterException("Failed to fetch record entity from Atlas: {\"secret\":1}", 401, null));
        endpoints().forEach((name, request) -> {
            Response response = answer(name, request, 502);
            assertFalse(response.asString().contains("secret"), name);
        });
    }

    private static Response answer(String name, Supplier<Response> request, int expectedStatus) {
        Response response = request.get();
        assertEquals(expectedStatus, response.statusCode(), name);
        assertTrue(response.contentType().startsWith("application/json"), name + ": " + response.contentType());
        assertEquals(Set.of(errorField(name)), response.jsonPath().getMap("").keySet(), name);
        return response;
    }

    /** Each API keeps its own error body: the dataset views answer {"error": ...}. */
    private static String errorField(String endpoint) {
        return endpoint.contains("/api/v1/datasets") ? "error" : "message";
    }

    private static Map<String, Supplier<Response>> endpoints() {
        return Map.of(
                "POST /records",
                () -> given().contentType("application/ld+json").body(RECORD).post("/records"),
                "GET /records/{id}",
                () -> given().get("/records/https%3A%2F%2Fexample.org%2Frecords%2Fbackend-failure"),
                "GET /api/v1/datasets",
                () -> given().queryParam("qualifiedName", "x").get("/api/v1/datasets"),
                "GET /api/v1/datasets/guid/{guid}",
                () -> given().get("/api/v1/datasets/guid/g"),
                "GET /api/v1/datasets/{qualifiedName}",
                () -> given().get("/api/v1/datasets/x"));
    }
}

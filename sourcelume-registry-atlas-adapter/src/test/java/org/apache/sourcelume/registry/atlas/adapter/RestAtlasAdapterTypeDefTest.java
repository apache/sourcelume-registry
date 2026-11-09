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
package org.apache.sourcelume.registry.atlas.adapter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RestAtlasAdapterTypeDefTest {

    private HttpServer server;
    private RestAtlasAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final List<RecordedRequest> recordedRequests = new CopyOnWriteArrayList<>();

    private record RecordedRequest(String method, String path, String body) {}

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new RestAtlasAdapter(stubProperties(baseUrl), objectMapper);
    }

    @AfterEach
    void stopStub() {
        server.stop(0);
    }

    @Test
    void shouldRegisterTypeDefsSuccessfullyOnFirstAttempt() {
        server.createContext("/api/atlas/v2/types/typedefs", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            recordedRequests.add(new RecordedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    new String(body, StandardCharsets.UTF_8)));
            byte[] out = "{\"entityDefs\": [{\"name\": \"sourcelume_dataset\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(201, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        String sampleTypeDefs =
                "{\"enumDefs\": [{\"name\": \"sourcelume_record_status\"}], \"entityDefs\": [{\"name\": \"sourcelume_dataset\"}]}";
        TypeDefinitionModel input =
                new TypeDefinitionModel("sample.json", sampleTypeDefs.getBytes(StandardCharsets.UTF_8), 1);

        TypeDefinitionModel result = adapter.registerOrUpdateTypeDefs(input);

        assertNotNull(result);
        assertEquals(1, result.getEntityDefCount());
        assertEquals(1, recordedRequests.size());
        assertEquals("POST", recordedRequests.get(0).method());
    }

    @Test
    void shouldPartitionNewAndExistingTypesOn409Conflict() {
        List<RecordedRequest> requests = new ArrayList<>();
        server.createContext("/api/atlas/v2/types/typedefs", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            String method = exchange.getRequestMethod();
            requests.add(new RecordedRequest(
                    method, exchange.getRequestURI().getPath(), new String(body, StandardCharsets.UTF_8)));

            if ("POST".equals(method) && requests.size() == 1) {
                // Initial POST fails with 409 Conflict because sourcelume_dataset already exists
                byte[] out =
                        "{\"errorCode\":\"ATLAS-409-00-001\",\"errorMessage\":\"Given type sourcelume_dataset already exists\"}"
                                .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(409, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } else if ("POST".equals(method)) {
                // Partitioned POST for new types (sourcelume_record_status) succeeds
                byte[] out =
                        "{\"enumDefs\": [{\"name\": \"sourcelume_record_status\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            } else if ("PUT".equals(method)) {
                // Partitioned PUT for existing types (sourcelume_dataset) succeeds
                byte[] out = "{\"entityDefs\": [{\"name\": \"sourcelume_dataset\"}]}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            }
        });

        server.createContext("/api/atlas/v2/types/typedefs/headers", exchange -> {
            byte[] out =
                    "[{\"name\": \"sourcelume_dataset\", \"category\": \"ENTITY\"}]".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        String sampleTypeDefs =
                "{\"enumDefs\": [{\"name\": \"sourcelume_record_status\"}], \"entityDefs\": [{\"name\": \"sourcelume_dataset\"}]}";
        TypeDefinitionModel input =
                new TypeDefinitionModel("sample.json", sampleTypeDefs.getBytes(StandardCharsets.UTF_8), 1);

        TypeDefinitionModel result = adapter.registerOrUpdateTypeDefs(input);

        assertNotNull(result);
        assertEquals(1, result.getEntityDefCount());
        // 1 initial POST + 1 partitioned POST for new enums + 1 partitioned PUT for existing entities
        assertEquals(3, requests.size());
        assertEquals("POST", requests.get(0).method());
        assertEquals("POST", requests.get(1).method());
        assertEquals("PUT", requests.get(2).method());

        // Verify POST contains sourcelume_record_status
        JsonNode postBody = parseJson(requests.get(1).body());
        assertEquals(1, postBody.path("enumDefs").size());
        assertEquals(
                "sourcelume_record_status",
                postBody.path("enumDefs").get(0).path("name").asText());
        assertEquals(0, postBody.path("entityDefs").size());

        // Verify PUT contains sourcelume_dataset
        JsonNode putBody = parseJson(requests.get(2).body());
        assertEquals(0, putBody.path("enumDefs").size());
        assertEquals(1, putBody.path("entityDefs").size());
        assertEquals(
                "sourcelume_dataset",
                putBody.path("entityDefs").get(0).path("name").asText());
    }

    private JsonNode parseJson(String json) {
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private SourcelumeAtlasProperties stubProperties(String baseUrl) {
        return new SourcelumeAtlasProperties() {
            @Override
            public String url() {
                return baseUrl;
            }

            @Override
            public String user() {
                return "admin";
            }

            @Override
            public String password() {
                return "secret";
            }

            @Override
            public Optional<String> passwordFile() {
                return Optional.empty();
            }

            @Override
            public String specContextResource() {
                return "context/0.0.1/sourcelume.jsonld";
            }

            @Override
            public String typedefsResource() {
                return "models/sourcelume/sourcelume_model.json";
            }

            @Override
            public boolean bootstrapOnStartup() {
                return false;
            }

            @Override
            public int maxRetries() {
                return 1;
            }

            @Override
            public long retryDelayMs() {
                return 0;
            }
        };
    }
}

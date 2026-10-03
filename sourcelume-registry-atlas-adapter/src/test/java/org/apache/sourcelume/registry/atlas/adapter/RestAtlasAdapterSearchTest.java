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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.exception.AtlasAdapterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP-level tests for the status search and the lifecycle attribute
 * mapping of {@link RestAtlasAdapter}, against a stub Atlas served by
 * the JDK {@code HttpServer} — no Docker, no Quarkus bootstrap.
 */
class RestAtlasAdapterSearchTest {

    private HttpServer server;
    private RestAtlasAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>();

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
    void searchesByStatusAndMapsEntities() throws IOException {
        serve(
                200,
                "{\"entities\": ["
                        + entity("guid-1", "PENDING", "https://example.org/records/one")
                        + ", " + entity("guid-2", "PENDING", "https://example.org/records/two") + "]}");

        List<SourcelumeDatasetDto> found = adapter.findDatasetsByStatus(RecordStatus.PENDING, 10);

        assertEquals(2, found.size());
        assertEquals("guid-1", found.get(0).getGuid());
        assertEquals(RecordStatus.PENDING, found.get(0).getRecordStatus());
        assertEquals("{\"id\": \"one\"}", found.get(0).getRawJsonLd());
        assertNull(found.get(0).getValidationIssues());

        JsonNode body = objectMapper.readTree(lastRequestBody.get());
        assertEquals("sourcelume_dataset", body.path("typeName").asText());
        assertTrue(body.path("excludeDeletedEntities").asBoolean());
        assertEquals(10, body.path("limit").asInt());
        assertEquals(
                "recordStatus", body.path("entityFilters").path("attributeName").asText());
        assertEquals(
                "PENDING", body.path("entityFilters").path("attributeValue").asText());
    }

    @Test
    void emptySearchResultYieldsAnEmptyList() throws IOException {
        serve(200, "{\"queryType\": \"BASIC\", \"searchParameters\": {}}");

        List<SourcelumeDatasetDto> found = adapter.findDatasetsByStatus(RecordStatus.PENDING, 10);

        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    @Test
    void backendErrorFailsLoudly() throws IOException {
        serve(500, "{\"errorMessage\": \"boom\"}");

        assertThrows(AtlasAdapterException.class, () -> adapter.findDatasetsByStatus(RecordStatus.PENDING, 10));
    }

    @Test
    void lookupMapsTheLifecycleFields() throws IOException {
        serve(200, "{\"entities\": [" + entity("guid-9", "ACTIVE", "https://example.org/records/nine") + "]}");

        SourcelumeDatasetDto dto = adapter.getDatasetByQualifiedName("https://example.org/records/nine");

        assertEquals(RecordStatus.ACTIVE, dto.getRecordStatus());
        assertEquals("guid-9", dto.getGuid());
        assertEquals("https://example.org/records/nine", dto.getQualifiedName());
        assertEquals("nine", dto.getName());
        assertEquals("https://example.org/licenses/nine", dto.getLicenseId());
    }

    @Test
    void unknownStatusValueFailsLoudly() throws IOException {
        serve(200, "{\"entities\": [" + entity("guid-x", "SOMEDAY", "https://example.org/records/x") + "]}");

        AtlasAdapterException exception = assertThrows(
                AtlasAdapterException.class, () -> adapter.getDatasetByQualifiedName("https://example.org/records/x"));
        assertTrue(exception.getMessage().contains("SOMEDAY"));
    }

    @Test
    void upsertWritesTheLifecycleAttributes() throws IOException {
        serve(201, "{\"guidAssignments\": {\"https://example.org/records/one\": \"guid-1\"}}");

        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName("https://example.org/records/one");
        dto.setName("one");
        dto.setRecordStatus(RecordStatus.PENDING);
        dto.setRawJsonLd("{\"id\": \"https://example.org/records/one\"}");
        dto.setValidationIssues("[{\"message\": \"m\"}]");

        String guid = adapter.createOrUpdateDatasetEntity(dto);

        assertEquals("guid-1", guid);
        JsonNode attributes = objectMapper
                .readTree(lastRequestBody.get())
                .path("entities")
                .get(0)
                .path("attributes");
        assertEquals("PENDING", attributes.path("recordStatus").asText());
        assertEquals(
                "{\"id\": \"https://example.org/records/one\"}",
                attributes.path("rawJsonLd").asText());
        assertTrue(attributes.has("validationIssues"));
    }

    // --- stub helpers ---

    private void serve(int status, String responseBody) {
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) {
                lastRequestBody.set(new String(body, StandardCharsets.UTF_8));
            }
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
    }

    private String entity(String guid, String recordStatus, String qualifiedName) {
        return "{\"guid\": \"" + guid + "\", \"attributes\": {"
                + "\"qualifiedName\": \"" + qualifiedName + "\","
                + "\"name\": \"" + qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1) + "\","
                + "\"licenseId\": \"https://example.org/licenses/"
                + qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1) + "\","
                + "\"recordStatus\": \"" + recordStatus + "\","
                + "\"rawJsonLd\": \"{\\\"id\\\": \\\"one\\\"}\""
                + "}}";
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

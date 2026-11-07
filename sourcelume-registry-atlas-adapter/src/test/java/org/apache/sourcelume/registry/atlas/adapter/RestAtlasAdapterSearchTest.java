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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
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
    private static final String ONE = "https://example.org/records/one";
    private static final String TWO = "https://example.org/records/two";
    private static final String THREE = "https://example.org/records/three";

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
                        + entity("guid-1", "VALIDATED", "https://example.org/records/one")
                        + ", " + entity("guid-2", "VALIDATED", "https://example.org/records/two") + "]}");

        List<RecordEntityDto> found =
                adapter.findRecordEntitiesByStatus(SourcelumeDatasetDto.TYPE_NAME, RecordStatus.VALIDATED, 10);

        assertEquals(2, found.size());
        assertEquals("guid-1", found.get(0).getGuid());
        assertEquals(RecordStatus.VALIDATED, found.get(0).getRecordStatus());
        assertEquals("{\"id\": \"one\"}", found.get(0).getRawJsonLd());
        assertNull(found.get(0).getValidationIssues());

        JsonNode body = objectMapper.readTree(lastRequestBody.get());
        assertEquals("sourcelume_dataset", body.path("typeName").asText());
        assertTrue(body.path("excludeDeletedEntities").asBoolean());
        assertEquals(10, body.path("limit").asInt());
        assertEquals(
                "recordStatus", body.path("entityFilters").path("attributeName").asText());
        assertEquals(
                "VALIDATED", body.path("entityFilters").path("attributeValue").asText());
        // Without the explicit attributes list Atlas returns headers only
        // and callers would see empty documents.
        JsonNode attributes = body.path("attributes");
        assertTrue(attributes.isArray() && attributes.size() == 11, "the lifecycle attributes must be requested");
        for (String wanted : new String[] {"rawJsonLd", "recordStatus"}) {
            boolean listed = false;
            for (JsonNode attribute : attributes) {
                listed = listed || wanted.equals(attribute.asText());
            }
            assertTrue(listed, wanted + " must be requested from basic search");
        }
    }

    @Test
    void emptySearchResultYieldsAnEmptyList() throws IOException {
        serve(200, "{\"queryType\": \"BASIC\", \"searchParameters\": {}}");

        List<RecordEntityDto> found =
                adapter.findRecordEntitiesByStatus(SourcelumeDatasetDto.TYPE_NAME, RecordStatus.VALIDATED, 10);

        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    @Test
    void bulkLookupSearchesOncePerTypeAndKeepsTheFirstType() throws IOException {
        List<String> bodies = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] request = exchange.getRequestBody().readAllBytes();
            String raw = new String(request, StandardCharsets.UTF_8);
            bodies.add(raw);
            String typeName = objectMapper.readTree(raw).path("typeName").asText();
            String status = SourcelumeModelDto.TYPE_NAME.equals(typeName) ? "INCOMPLETE" : "VALIDATED";
            byte[] out = ("{\"entities\": [" + typedEntity("guid-" + typeName, typeName, status, TWO) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        Map<String, RecordEntityDto> found = adapter.getRecordEntitiesByQualifiedNames(
                List.of(SourcelumeDatasetDto.TYPE_NAME, SourcelumeModelDto.TYPE_NAME), List.of(ONE, TWO, THREE, ONE));

        assertEquals(2, bodies.size(), "one search per entity type, not one per IRI");
        assertEquals(1, found.size());
        assertEquals(SourcelumeDatasetDto.TYPE_NAME, found.get(TWO).getTypeName());
        assertEquals(RecordStatus.VALIDATED, found.get(TWO).getRecordStatus());
        assertNull(found.get(ONE));
        assertNull(found.get(THREE));

        assertEquals(List.of("/api/atlas/v2/search/basic", "/api/atlas/v2/search/basic"), paths);
        for (String raw : bodies) {
            JsonNode body = objectMapper.readTree(raw);
            assertTrue(body.path("excludeDeletedEntities").asBoolean());
            assertEquals(3, body.path("limit").asInt(), "the limit covers each distinct name once");
            JsonNode criterion = body.path("entityFilters").path("criterion");
            assertEquals("OR", body.path("entityFilters").path("condition").asText());
            assertEquals(3, criterion.size());
            assertEquals(ONE, criterion.get(0).path("attributeValue").asText());
            assertEquals(TWO, criterion.get(1).path("attributeValue").asText());
            assertEquals(THREE, criterion.get(2).path("attributeValue").asText());
            assertEquals("qualifiedName", criterion.get(0).path("attributeName").asText());
            assertEquals("=", criterion.get(0).path("operator").asText());
            boolean listsStatus = false;
            for (JsonNode attribute : body.path("attributes")) {
                listsStatus = listsStatus || "recordStatus".equals(attribute.asText());
            }
            assertTrue(listsStatus, "basic search must be asked for recordStatus");
        }
        assertEquals(
                SourcelumeDatasetDto.TYPE_NAME,
                objectMapper.readTree(bodies.get(0)).path("typeName").asText());
        assertEquals(
                SourcelumeModelDto.TYPE_NAME,
                objectMapper.readTree(bodies.get(1)).path("typeName").asText());
    }

    @Test
    void bulkLookupOfNoNamesDoesNotCallTheBackend() {
        Map<String, RecordEntityDto> found =
                adapter.getRecordEntitiesByQualifiedNames(List.of(SourcelumeDatasetDto.TYPE_NAME), List.of());

        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    @Test
    void bulkLookupStopsWhenTheFirstSearchFails() {
        List<String> typeNames = new ArrayList<>();
        server.createContext("/", exchange -> {
            byte[] request = exchange.getRequestBody().readAllBytes();
            typeNames.add(objectMapper
                    .readTree(new String(request, StandardCharsets.UTF_8))
                    .path("typeName")
                    .asText());
            byte[] out = "{\"errorMessage\": \"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThrows(
                AtlasAdapterException.class,
                () -> adapter.getRecordEntitiesByQualifiedNames(
                        List.of(SourcelumeDatasetDto.TYPE_NAME, SourcelumeModelDto.TYPE_NAME), List.of(ONE)));
        assertEquals(List.of(SourcelumeDatasetDto.TYPE_NAME), typeNames);
    }

    @Test
    void backendErrorFailsLoudly() throws IOException {
        serve(500, "{\"errorMessage\": \"boom\"}");

        assertThrows(
                AtlasAdapterException.class,
                () -> adapter.findRecordEntitiesByStatus(SourcelumeDatasetDto.TYPE_NAME, RecordStatus.VALIDATED, 10));
    }

    @Test
    void lookupMapsTheLifecycleFields() throws IOException {
        // The unique-attribute endpoint answers with AtlasEntityWithExtInfo.
        serve(200, "{\"entity\": " + entity("guid-9", "VALIDATED", "https://example.org/records/nine") + "}");

        SourcelumeDatasetDto dto = (SourcelumeDatasetDto) adapter.getRecordEntityByQualifiedName(
                SourcelumeDatasetDto.TYPE_NAME, "https://example.org/records/nine");

        assertEquals(RecordStatus.VALIDATED, dto.getRecordStatus());
        assertEquals("guid-9", dto.getGuid());
        assertEquals("https://example.org/records/nine", dto.getQualifiedName());
        assertEquals("nine", dto.getName());
        assertEquals("https://example.org/licenses/nine", dto.getLicenseId());
    }

    @Test
    void unknownStatusValueFailsLoudly() throws IOException {
        serve(200, "{\"entity\": " + entity("guid-x", "SOMEDAY", "https://example.org/records/x") + "}");

        AtlasAdapterException exception = assertThrows(
                AtlasAdapterException.class,
                () -> adapter.getRecordEntityByQualifiedName(
                        SourcelumeDatasetDto.TYPE_NAME, "https://example.org/records/x"));
        assertTrue(exception.getMessage().contains("SOMEDAY"));
    }

    @Test
    void upsertWritesTheLifecycleAttributes() throws IOException {
        serve(201, "{\"guidAssignments\": {\"https://example.org/records/one\": \"guid-1\"}}");

        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName("https://example.org/records/one");
        dto.setName("one");
        dto.setRecordStatus(RecordStatus.VALIDATED);
        dto.setRawJsonLd("{\"id\": \"https://example.org/records/one\"}");
        dto.setValidationIssues("[{\"message\": \"m\"}]");

        String guid = adapter.createOrUpdateRecordEntity(dto);

        assertEquals("guid-1", guid);
        JsonNode attributes = objectMapper
                .readTree(lastRequestBody.get())
                .path("entities")
                .get(0)
                .path("attributes");
        assertEquals("VALIDATED", attributes.path("recordStatus").asText());
        assertEquals(
                "{\"id\": \"https://example.org/records/one\"}",
                attributes.path("rawJsonLd").asText());
        // The issues attribute must reach the wire with its exact value (see CLEARED_VALIDATION_ISSUES).
        assertEquals(
                "[{\"message\": \"m\"}]", attributes.path("validationIssues").asText());
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

    private String typedEntity(String guid, String typeName, String recordStatus, String qualifiedName) {
        return "{\"guid\": \"" + guid + "\", \"typeName\": \"" + typeName + "\", \"attributes\": {"
                + "\"qualifiedName\": \"" + qualifiedName + "\","
                + "\"name\": \"" + qualifiedName.substring(qualifiedName.lastIndexOf('/') + 1) + "\","
                + "\"recordStatus\": \"" + recordStatus + "\""
                + "}}";
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

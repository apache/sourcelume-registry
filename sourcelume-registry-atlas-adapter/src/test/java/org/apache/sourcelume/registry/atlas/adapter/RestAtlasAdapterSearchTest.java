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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * HTTP-level tests for the status search, the bulk qualified-name
 * lookup, and the lifecycle attribute mapping of {@link RestAtlasAdapter},
 * against a stub Atlas served by the JDK {@code HttpServer} — no Docker,
 * no Quarkus bootstrap.
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
        assertTrue(attributes.isArray() && attributes.size() == 16, "the lifecycle attributes must be requested");
        for (String wanted :
                new String[] {"rawJsonLd", "recordStatus", "registryRevision", "recordAttachments", "recordAttributes"
                }) {
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
    void bulkLookupReadsTheGraphOncePerTypeAndKeepsTheFirstType() {
        List<String> methods = new ArrayList<>();
        List<String> paths = new ArrayList<>();
        List<String> queries = new ArrayList<>();
        server.createContext("/", exchange -> {
            methods.add(exchange.getRequestMethod());
            paths.add(exchange.getRequestURI().getPath());
            queries.add(exchange.getRequestURI().getRawQuery());
            String path = exchange.getRequestURI().getPath();
            String typeName = path.substring(path.lastIndexOf('/') + 1);
            String status = SourcelumeModelDto.TYPE_NAME.equals(typeName) ? "INCOMPLETE" : "VALIDATED";
            // A non-object in the list is skipped; a name Atlas omits is not a failure.
            byte[] out = ("{\"entities\": [null, " + typedEntity("guid-" + typeName, typeName, status, TWO) + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        Map<String, RecordEntityDto> found = adapter.getRecordEntitiesByQualifiedNames(
                List.of(SourcelumeDatasetDto.TYPE_NAME, SourcelumeModelDto.TYPE_NAME), List.of(ONE, TWO, THREE, ONE));

        assertEquals(2, queries.size(), "one graph lookup per entity type, not one per IRI");
        assertEquals(List.of("GET", "GET"), methods);
        assertEquals(1, found.size());
        assertEquals(SourcelumeDatasetDto.TYPE_NAME, found.get(TWO).getTypeName());
        assertEquals(RecordStatus.VALIDATED, found.get(TWO).getRecordStatus());
        assertNull(found.get(ONE));
        assertNull(found.get(THREE));

        String expectedQuery = uniqueAttributeQuery(ONE, TWO, THREE);
        assertEquals(
                List.of(bulkLookupPath(SourcelumeDatasetDto.TYPE_NAME), bulkLookupPath(SourcelumeModelDto.TYPE_NAME)),
                paths);
        assertEquals(List.of(expectedQuery, expectedQuery), queries);
    }

    @Test
    void bulkLookupOfNoNamesDoesNotCallTheBackend() {
        Map<String, RecordEntityDto> found =
                adapter.getRecordEntitiesByQualifiedNames(List.of(SourcelumeDatasetDto.TYPE_NAME), List.of());

        assertNotNull(found);
        assertTrue(found.isEmpty());
    }

    @Test
    void bulkLookupStopsWhenTheFirstRequestFails() {
        List<String> paths = new ArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
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
        assertEquals(List.of(bulkLookupPath(SourcelumeDatasetDto.TYPE_NAME)), paths);
    }

    @Test
    void bulkLookupOfAMissingTypeFailsTheRead() {
        // Atlas omits unknown names from a 200. A 404 is a missing type or a
        // broken endpoint, and must not look like "no records".
        List<String> paths = new ArrayList<>();
        server.createContext("/", exchange -> {
            paths.add(exchange.getRequestURI().getPath());
            byte[] out = "{\"errorMessage\": \"type not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(404, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        AtlasAdapterException exception = assertThrows(
                AtlasAdapterException.class,
                () -> adapter.getRecordEntitiesByQualifiedNames(
                        List.of(SourcelumeDatasetDto.TYPE_NAME, SourcelumeModelDto.TYPE_NAME), List.of(ONE)));
        assertEquals(404, exception.getHttpStatusCode());
        assertEquals(List.of(bulkLookupPath(SourcelumeDatasetDto.TYPE_NAME)), paths);
    }

    @Test
    void qualifiedNamesAreEncodedLikeTheSingleNameLookup() {
        List<String> queries = new ArrayList<>();
        String name = "urn:example:a b+c\"d*e#f?g&h";
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] out = "{\"entities\": []}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        Map<String, RecordEntityDto> found =
                adapter.getRecordEntitiesByQualifiedNames(List.of(SourcelumeDatasetDto.TYPE_NAME), List.of(name));

        assertTrue(found.isEmpty());
        assertEquals(List.of(uniqueAttributeQuery(name)), queries);
        assertTrue(queries.get(0).contains("a+b"), "a space is encoded as '+', matching the single-name lookup");
    }

    @Test
    void batchesStayWithinTheRequestBudget() {
        String type = SourcelumeDatasetDto.TYPE_NAME;
        int twoNames = requestTarget(type, ONE, TWO).length();
        assertEquals(
                List.of(List.of(ONE, TWO), List.of(THREE)),
                RestAtlasAdapter.batchesForUniqueAttributeLookup(type, List.of(ONE, TWO, THREE), twoNames));

        // A budget smaller than every name still keeps each name, alone.
        String longName = "x".repeat(80);
        assertEquals(
                List.of(List.of(longName), List.of(ONE)),
                RestAtlasAdapter.batchesForUniqueAttributeLookup(type, List.of(longName, ONE), 1));
        assertEquals(
                List.of(List.of(ONE, TWO, THREE)),
                RestAtlasAdapter.batchesForUniqueAttributeLookup(
                        type, List.of(ONE, TWO, THREE), RestAtlasAdapter.BULK_LOOKUP_REQUEST_BUDGET));
    }

    @Test
    void aTightBudgetSendsOneRequestPerBatchAndMergesTheEntities() {
        List<String> queries = new ArrayList<>();
        server.createContext("/", exchange -> {
            String rawQuery = exchange.getRequestURI().getRawQuery();
            queries.add(rawQuery);
            String qualifiedName = rawQuery.contains(URLEncoder.encode(ONE, StandardCharsets.UTF_8)) ? ONE : TWO;
            byte[] out = ("{\"entities\": [null, "
                            + typedEntity("guid", SourcelumeDatasetDto.TYPE_NAME, "VALIDATED", qualifiedName)
                            + "]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        List<RecordEntityDto> found =
                adapter.lookupQualifiedNames(SourcelumeDatasetDto.TYPE_NAME, List.of(ONE, TWO), 1);

        assertEquals(List.of(uniqueAttributeParam(0, ONE), uniqueAttributeParam(0, TWO)), queries);
        assertEquals(
                List.of(ONE, TWO),
                found.stream().map(RecordEntityDto::getQualifiedName).toList());
        assertEquals(RecordStatus.VALIDATED, found.get(0).getRecordStatus());
    }

    @Test
    void aFailedBatchIsNotFollowedByAnother() {
        List<String> queries = new ArrayList<>();
        server.createContext("/", exchange -> {
            queries.add(exchange.getRequestURI().getRawQuery());
            byte[] out = "{\"errorMessage\": \"boom\"}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThrows(
                AtlasAdapterException.class,
                () -> adapter.lookupQualifiedNames(SourcelumeDatasetDto.TYPE_NAME, List.of(ONE, TWO), 1));
        assertEquals(List.of(uniqueAttributeParam(0, ONE)), queries);
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

    private static String bulkLookupPath(String entityType) {
        return "/api/atlas/v2/entity/bulk/uniqueAttribute/type/" + entityType;
    }

    private static String requestTarget(String entityType, String... names) {
        return bulkLookupPath(entityType) + "?" + uniqueAttributeQuery(names);
    }

    private static String uniqueAttributeQuery(String... names) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            if (i > 0) {
                query.append('&');
            }
            query.append(uniqueAttributeParam(i, names[i]));
        }
        return query.toString();
    }

    /** Same encoding as {@link RestAtlasAdapter}'s single-name lookup: UTF-8, space as {@code +}. */
    private static String uniqueAttributeParam(int index, String qualifiedName) {
        return "attr_" + index + ":qualifiedName=" + URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
    }

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

    @Test
    void facetFiltersAreSentToAtlasBeforeTheResultLimit() throws Exception {
        serve(200, "{\"entities\":[]}");
        var facets = org.apache.sourcelume.registry.spi.RecordFacets.builder()
                .add(org.apache.sourcelume.registry.spi.RecordFacet.TRAINING_DATASET, "urn:data:A")
                .add(org.apache.sourcelume.registry.spi.RecordFacet.TRAINING_DATASET, "urn:data:B")
                .add(org.apache.sourcelume.registry.spi.RecordFacet.CREATOR_NAME, "Team A")
                .build();
        adapter.findRecordEntities(
                "sourcelume_model",
                org.apache.sourcelume.registry.spi.SearchQuery.builder()
                        .type("TrainingProvenanceRecord")
                        .status(RecordStatus.VALIDATED)
                        .limit(1)
                        .facets(facets)
                        .build());
        JsonNode request = objectMapper.readTree(lastRequestBody.get());
        assertEquals(1, request.path("limit").asInt());
        JsonNode filter = request.path("entityFilters");
        assertEquals("AND", filter.path("condition").asText());
        assertEquals(3, filter.path("criterion").size());
        JsonNode datasets = filter.path("criterion").get(1);
        assertEquals("OR", datasets.path("condition").asText());
        assertEquals(2, datasets.path("criterion").size());
        assertEquals(
                "recordFacetIndex",
                datasets.path("criterion").get(0).path("attributeName").asText());
        assertEquals(
                "contains", datasets.path("criterion").get(0).path("operator").asText());
        assertEquals(
                AtlasFacetIndex.token(org.apache.sourcelume.registry.spi.RecordFacet.TRAINING_DATASET, "urn:data:A"),
                datasets.path("criterion").get(0).path("attributeValue").asText());
    }
}

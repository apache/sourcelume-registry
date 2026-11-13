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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link RestAtlasAdapter}.
 */
class RestAtlasAdapterTest {

    private HttpServer server;
    private RestAtlasAdapter adapter;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress(0), 0);
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        adapter = new RestAtlasAdapter(stubProperties(baseUrl), objectMapper);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void shouldLoadBundledTypeDefs() {
        TypeDefinitionModel td = adapter.loadTypeDefs("models/sourcelume/sourcelume_model.json");
        assertNotNull(td);
        assertEquals("models/sourcelume/sourcelume_model.json", td.getSourceResource());
        assertTrue(td.getRawJson().length > 0);
        assertEquals(
                2,
                td.getEntityDefCount(),
                "expected the dataset and model entity defs (sourcelume_dataset, sourcelume_model)");
        String json = new String(td.getRawJson());
        assertTrue(json.contains("sourcelume_dataset"), "typedef JSON should contain sourcelume_dataset");
        assertTrue(json.contains("sourcelume_model"), "typedef JSON should contain sourcelume_model");
    }

    @Test
    void shouldThrowWhenTypeDefsResourceMissing() {
        assertThrows(AtlasAdapterException.class, () -> adapter.loadTypeDefs("nonexistent/path/model.json"));
    }

    @Test
    void facetTokensDoNotCreateGraphCompositeIndexes() throws Exception {
        var definitions = objectMapper.readTree(
                adapter.loadTypeDefs("models/sourcelume/sourcelume_model.json").getRawJson());
        for (var entity : definitions.path("entityDefs")) {
            var attributes = new java.util.HashMap<String, com.fasterxml.jackson.databind.JsonNode>();
            entity.path("attributeDefs")
                    .forEach(attribute -> attributes.put(attribute.path("name").asText(), attribute));
            var tokens = attributes.get("recordFacetTokens");
            assertNotNull(tokens);
            assertEquals("DEFAULT", tokens.path("indexType").asText());
            assertFalse(tokens.path("isIndexable").asBoolean(true));
            assertFalse(tokens.path("isUnique").asBoolean(true));
            assertFalse(attributes.get("recordFacetIndex").path("isIndexable").asBoolean(true));
        }
    }

    @Test
    void recordWritesMoveFacetTokensAndClearTheLegacyIndex() throws Exception {
        AtomicReference<String> request = new AtomicReference<>();
        server.createContext("/", exchange -> {
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"guidAssignments\":{\"-1\":\"record-guid\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        for (String tokens :
                java.util.List.of(("a".repeat(64) + " ").repeat(161).trim(), "")) {
            SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
            dataset.setQualifiedName("urn:facet-replacement");
            dataset.setName("facets");
            dataset.setRegistryRevision("replacement-revision");
            dataset.setRecordFacetIndex(tokens);
            adapter.createOrUpdateRecordEntity(dataset);
            var attributes =
                    objectMapper.readTree(request.get()).path("entities").get(0).path("attributes");
            assertEquals(tokens, attributes.path("recordFacetTokens").asText());
            assertTrue(attributes.path("recordFacetIndex").isNull(), "The old index must be explicitly cleared");
        }
    }

    @Test
    void testCreateOrUpdateDatasetEntityWithCreatedEntities() {
        serve(
                200,
                "{\"createdEntities\":[{\"guid\":\"new-guid-1\",\"typeName\":\"sourcelume_dataset\"}],\"mutatedEntities\":{}}");
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
        dataset.setQualifiedName("https://rec/new");
        dataset.setName("New Dataset");
        dataset.setDescription("Description");
        dataset.setSourceUri("https://source/uri");
        dataset.setLicenseId("https://spdx.org/licenses/Apache-2.0.html");
        dataset.setRecordStatus(RecordStatus.VALIDATED);
        dataset.setRawJsonLd("{\"id\":\"https://rec/new\"}");
        dataset.setValidationIssues("[]");
        dataset.setSha256("hash123");
        dataset.setValidatedBy("json-schema");
        dataset.setValidatedAt("2026-10-11T12:00:00Z");

        String guid = adapter.createOrUpdateRecordEntity(dataset);
        assertEquals("new-guid-1", guid);
    }

    @Test
    void testCreateOrUpdateDatasetEntityWithUpdatedEntities() {
        serve(
                200,
                "{\"updatedEntities\":[{\"guid\":\"updated-guid-1\",\"typeName\":\"sourcelume_dataset\"}],\"mutatedEntities\":{}}");
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
        dataset.setQualifiedName("https://rec/existing");
        dataset.setName("Existing Dataset");

        String guid = adapter.createOrUpdateRecordEntity(dataset);
        assertEquals("updated-guid-1", guid);
    }

    @Test
    void testCreateOrUpdateDatasetEntityWithGuidAssignments() {
        serve(200, "{\"guidAssignments\":{\"-1\":\"assigned-guid-1\"}}");
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
        dataset.setQualifiedName("https://rec/assigned");
        dataset.setName("Assigned Dataset");

        String guid = adapter.createOrUpdateRecordEntity(dataset);
        assertEquals("assigned-guid-1", guid);
    }

    @Test
    void testCreateOrUpdateDatasetEntityRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> adapter.createOrUpdateRecordEntity(null));
    }

    @Test
    void testCreateOrUpdateDatasetEntityThrowsOnServerError() {
        serve(500, "{\"errorMessage\":\"Server Error\"}");
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
        dataset.setQualifiedName("https://rec/error");
        dataset.setName("Error Dataset");

        assertThrows(AtlasAdapterException.class, () -> adapter.createOrUpdateRecordEntity(dataset));
    }

    @Test
    void testGetDatasetByQualifiedNameFound() {
        serve(
                200,
                "{\"entity\":{\"guid\":\"guid-123\",\"attributes\":{\"qualifiedName\":\"https://rec/1\",\"name\":\"Dataset 1\",\"recordStatus\":\"VALIDATED\"}}}");
        SourcelumeDatasetDto dto = (SourcelumeDatasetDto)
                adapter.getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, "https://rec/1");
        assertNotNull(dto);
        assertEquals("guid-123", dto.getGuid());
        assertEquals("https://rec/1", dto.getQualifiedName());
        assertEquals("Dataset 1", dto.getName());
        assertEquals(RecordStatus.VALIDATED, dto.getRecordStatus());
    }

    @Test
    void testGetDatasetByQualifiedNameReturnsNullOn404() {
        serve(404, "{\"errorMessage\":\"Not Found\"}");
        assertNull(adapter.getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, "https://rec/missing"));
    }

    @Test
    void testGetDatasetByQualifiedNameThrowsOnUnknownStatus() {
        serve(
                200,
                "{\"entity\":{\"guid\":\"guid-123\",\"attributes\":{\"qualifiedName\":\"https://rec/1\",\"recordStatus\":\"UNKNOWN_STATUS\"}}}");
        assertThrows(
                AtlasAdapterException.class,
                () -> adapter.getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, "https://rec/1"));
    }

    @Test
    void testIsServerReady() {
        AtomicInteger statusCode = new AtomicInteger(200);
        server.createContext("/api/atlas/admin/version", exchange -> {
            byte[] out = "{\"Version\":\"2.3.0\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(statusCode.get(), out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertTrue(adapter.isServerReady());

        statusCode.set(500);
        assertFalse(adapter.isServerReady());

        server.stop(0);
        assertFalse(adapter.isServerReady());
    }

    private void serve(int status, String responseBody) {
        server.createContext("/", exchange -> {
            byte[] out = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
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
                return "atlasR0cks!";
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

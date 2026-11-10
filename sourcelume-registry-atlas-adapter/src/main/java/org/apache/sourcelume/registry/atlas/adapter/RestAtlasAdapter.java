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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.net.ConnectException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.spi.RecordFacets;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.apache.sourcelume.registry.spi.SearchQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Atlas REST transport used by {@link AtlasRegistryPlugin}, using the JDK
 * {@link HttpClient} and Jackson. Talks to the Apache Atlas REST API directly,
 * without pulling in {@code atlas-client-v2} or {@code atlas-intg} and their
 * transitive dependency tree.
 *
 * <p>Covers exactly the Atlas endpoints the registry needs:
 * <ul>
 *   <li>{@code GET  /api/atlas/admin/version} — readiness (via isServerReady)</li>
 *   <li>{@code POST /api/atlas/v2/types/typedefs} — create typedefs</li>
 *   <li>{@code PUT  /api/atlas/v2/types/typedefs} — update typedefs (on 409/conflict)</li>
 *   <li>{@code POST /api/atlas/v2/entity/bulk} — create/update a record entity</li>
 *   <li>{@code GET  /api/atlas/v2/entity/uniqueAttribute/type/{typeName}?attr:qualifiedName=...} — lookup</li>
 *   <li>{@code DELETE /api/atlas/v2/entity/uniqueAttribute/type/{typeName}?attr:qualifiedName=...} — delete a stub</li>
 *   <li>{@code POST /api/atlas/v2/search/basic} — find record entities by record status</li>
 *   <li>{@code GET  /api/atlas/v2/entity/bulk/uniqueAttribute/type/{typeName}?attr_0:qualifiedName=...}
 *       — resolve many qualified names against the graph, not the search index</li>
 * </ul>
 *
 * <p>Atlas-specific JSON shapes live here and only here. The storage SPI exposes no Atlas identifiers or type-definition payloads.
 */
public class RestAtlasAdapter implements AtlasAdapter, AutoCloseable {
    @Override
    public void close() {
        httpClient.shutdown();
    }

    private static final Logger log = LoggerFactory.getLogger(RestAtlasAdapter.class);

    /**
     * Characters budget for one bulk unique-attribute request target
     * (path and query). Atlas's Jetty header buffer defaults to 16192
     * ({@code atlas.jetty.request.buffer.size}); a request line past that
     * is rejected before the lookup runs. Twelve thousand leaves room for
     * the request line's method and version and for the headers the JDK
     * client adds.
     */
    static final int BULK_LOOKUP_REQUEST_BUDGET = 12_000;

    private final SourcelumeAtlasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public RestAtlasAdapter(SourcelumeAtlasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public boolean isServerReady() {
        try {
            HttpRequest req =
                    baseRequest("GET", "/api/atlas/admin/version").GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200;
        } catch (Exception e) {
            log.debug("Atlas server connectivity check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public TypeDefinitionModel loadTypeDefs(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = getClass().getClassLoader();
        }
        URL resource = cl.getResource(resourcePath);
        if (resource == null) {
            throw new AtlasAdapterException("Could not find typedefs resource on classpath: " + resourcePath);
        }
        try (InputStream in = resource.openStream()) {
            byte[] bytes = in.readAllBytes();
            JsonNode root = objectMapper.readTree(bytes);
            int entityCount = root.has("entityDefs") && root.get("entityDefs").isArray()
                    ? root.get("entityDefs").size()
                    : 0;
            log.info("Loaded typedefs with {} entity definition(s) from {}", entityCount, resourcePath);
            return new TypeDefinitionModel(resourcePath, bytes, entityCount);
        } catch (IOException e) {
            throw new AtlasAdapterException("Failed to read typedefs resource: " + resourcePath, e);
        }
    }

    @Override
    public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel typeDefs) {
        if (typeDefs == null) {
            throw new IllegalArgumentException("typeDefs cannot be null");
        }
        String body = new String(typeDefs.getRawJson(), StandardCharsets.UTF_8);
        try {
            log.info("Attempting POST typedefs to Atlas at {}", properties.url());
            HttpResponse<String> resp = sendJson("POST", "/api/atlas/v2/types/typedefs", body);
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                log.info("Successfully created Atlas typedefs");
                int count = parseEntityDefCount(resp.body());
                return new TypeDefinitionModel(
                        typeDefs.getSourceResource(), resp.body().getBytes(StandardCharsets.UTF_8), count);
            }
            // Atlas returns 409/conflict when one or more typedefs already exist.
            // When that happens, some types in the payload may be new while others exist.
            // Atlas PUT fails with 404 if given types that do not exist yet,
            // and POST fails with 409 if given types that already exist.
            // We fetch existing typedef headers to split the payload: POST new types first,
            // then PUT existing types second.
            if (resp.statusCode() == 409) {
                log.warn(
                        "create typedefs failed ({}). Attempting partitioned registration/update...",
                        resp.statusCode());
                return registerOrUpdatePartitioned(typeDefs, body);
            }
            throw new AtlasAdapterException(
                    "Failed to register typedefs in Atlas: " + resp.body(), resp.statusCode(), null);
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error registering typedefs: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted registering typedefs", 500, e);
        }
    }

    private TypeDefinitionModel registerOrUpdatePartitioned(TypeDefinitionModel typeDefs, String body)
            throws IOException, InterruptedException {
        HttpRequest headersReq =
                baseRequest("GET", "/api/atlas/v2/types/typedefs/headers").GET().build();
        HttpResponse<String> headersResp = httpClient.send(headersReq, HttpResponse.BodyHandlers.ofString());
        if (headersResp.statusCode() != 200) {
            // If fetching headers fails, try PUT as fallback
            HttpResponse<String> putResp = sendJson("PUT", "/api/atlas/v2/types/typedefs", body);
            if (putResp.statusCode() == 200 || putResp.statusCode() == 204) {
                log.info("Successfully updated Atlas typedefs via fallback PUT");
                int count = parseEntityDefCount(putResp.body());
                return new TypeDefinitionModel(
                        typeDefs.getSourceResource(), putResp.body().getBytes(StandardCharsets.UTF_8), count);
            }
            throw new AtlasAdapterException(
                    "Failed to register or update typedefs in Atlas: " + putResp.body(), putResp.statusCode(), null);
        }

        Set<String> existingTypeNames = new HashSet<>();
        JsonNode headersArray = objectMapper.readTree(headersResp.body());
        if (headersArray.isArray()) {
            for (JsonNode header : headersArray) {
                JsonNode nameNode = header.path("name");
                if (nameNode.isTextual()) {
                    existingTypeNames.add(nameNode.asText());
                }
            }
        }

        JsonNode root = objectMapper.readTree(body);
        ObjectNode newDefs = objectMapper.createObjectNode();
        ObjectNode existingDefs = objectMapper.createObjectNode();
        int newCount = 0;
        int existingCount = 0;

        String[] categories = new String[] {
            "enumDefs", "structDefs", "classificationDefs", "entityDefs", "relationshipDefs", "businessMetadataDefs"
        };

        for (String category : categories) {
            JsonNode defs = root.path(category);
            if (defs.isArray()) {
                ArrayNode newArray = newDefs.putArray(category);
                ArrayNode existingArray = existingDefs.putArray(category);
                for (JsonNode def : defs) {
                    String name = def.path("name").asText(null);
                    if (name != null && existingTypeNames.contains(name)) {
                        existingArray.add(def);
                        existingCount++;
                    } else {
                        newArray.add(def);
                        newCount++;
                    }
                }
            }
        }

        String lastResponseBody = null;
        if (newCount > 0) {
            String newBody = objectMapper.writeValueAsString(newDefs);
            HttpResponse<String> postResp = sendJson("POST", "/api/atlas/v2/types/typedefs", newBody);
            if (postResp.statusCode() != 200 && postResp.statusCode() != 201) {
                throw new AtlasAdapterException(
                        "Failed to register new typedefs in Atlas: " + postResp.body(), postResp.statusCode(), null);
            }
            log.info("Successfully registered {} new type definitions", newCount);
            lastResponseBody = postResp.body();
        }

        if (existingCount > 0) {
            String existingBody = objectMapper.writeValueAsString(existingDefs);
            HttpResponse<String> putResp = sendJson("PUT", "/api/atlas/v2/types/typedefs", existingBody);
            if (putResp.statusCode() != 200 && putResp.statusCode() != 204) {
                throw new AtlasAdapterException(
                        "Failed to update existing typedefs in Atlas: " + putResp.body(), putResp.statusCode(), null);
            }
            log.info("Successfully updated {} existing type definitions", existingCount);
            lastResponseBody = putResp.body();
        }

        int count = parseEntityDefCount(body);
        byte[] responseBytes =
                lastResponseBody != null ? lastResponseBody.getBytes(StandardCharsets.UTF_8) : typeDefs.getRawJson();
        return new TypeDefinitionModel(typeDefs.getSourceResource(), responseBytes, count);
    }

    @Override
    public String createOrUpdateRecordEntity(RecordEntityDto record) {
        if (record == null) {
            throw new IllegalArgumentException("record entity cannot be null");
        }
        ObjectNode entity = objectMapper.createObjectNode();
        entity.put("typeName", record.getTypeName());
        ObjectNode attributes = entity.putObject("attributes");
        attributes.put("qualifiedName", record.getQualifiedName());
        // Atlas' Asset supertype requires a name on every entity, so the
        // ingest path always provides one (a placeholder derived from the
        // record id until validation writes the mapped name); the DTO keeps
        // name optional for callers that are not the ingest path.
        if (record.getName() != null) attributes.put("name", record.getName());
        if (record.getDescription() != null) attributes.put("description", record.getDescription());
        // The domain attributes depend on the entity type — the adapter owns
        // the Atlas attribute vocabulary for each record type it knows.
        if (record instanceof SourcelumeDatasetDto dataset) {
            if (dataset.getSourceUri() != null) attributes.put("sourceUri", dataset.getSourceUri());
            if (dataset.getLicenseId() != null) attributes.put("licenseId", dataset.getLicenseId());
        } else if (record instanceof SourcelumeModelDto model) {
            if (model.getModelUri() != null) attributes.put("modelUri", model.getModelUri());
            if (model.getModelVersion() != null) attributes.put("modelVersion", model.getModelVersion());
            if (model.getDisclosure() != null) attributes.put("disclosure", model.getDisclosure());
        }
        if (record.getRecordStatus() != null)
            attributes.put("recordStatus", record.getRecordStatus().name());
        if (record.getRawJsonLd() != null) attributes.put("rawJsonLd", record.getRawJsonLd());
        if (record.getValidationIssues() != null) {
            attributes.put("validationIssues", record.getValidationIssues());
        }
        if (record.getSha256() != null) attributes.put("sha256", record.getSha256());
        if (record.getValidatedBy() != null) attributes.put("validatedBy", record.getValidatedBy());
        if (record.getValidatedAt() != null) attributes.put("validatedAt", record.getValidatedAt());
        if (record.getRecordAttributes() != null) attributes.put("recordAttributes", record.getRecordAttributes());
        if (record.getRecordFacets() != null) attributes.put("recordFacets", record.getRecordFacets());
        if (record.getRecordFacetIndex() != null) attributes.put("recordFacetIndex", record.getRecordFacetIndex());
        if (record.getRegistryRevision() != null) attributes.put("registryRevision", record.getRegistryRevision());
        if (record.getRecordAttachments() != null) attributes.put("recordAttachments", record.getRecordAttachments());
        // SPI writes replace the complete projection. Explicit null clears attributes that
        // Atlas would otherwise retain during an upsert.
        if (record.getRegistryRevision() != null) {
            for (String field : attributesFor(record.getTypeName())) {
                if (!attributes.has(field)) attributes.putNull(field);
            }
        }

        ObjectNode wrapper = objectMapper.createObjectNode();
        ArrayNode entities = wrapper.putArray("entities");
        entities.add(entity);

        HttpRequest request;
        try {
            String body = objectMapper.writeValueAsString(wrapper);
            request = baseRequest("POST", "/api/atlas/v2/entity/bulk")
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
        } catch (IOException | RuntimeException e) {
            throw AtlasAdapterException.notSent("Cannot prepare Atlas mutation", e);
        }
        try {
            HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new AtlasAdapterException(
                        "Failed to persist record entity to Atlas: " + resp.body(), resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String guid = extractGuid(root);
            log.info("Created/updated record entity '{}' with GUID: {}", record.getQualifiedName(), guid);
            // The caller's DTO is deliberately not mutated with the guid:
            // upserts take DTOs that may be shared snapshots; the guid is
            // returned instead, and reads resolve it via the qualified name.
            return guid;
        } catch (ConnectException | HttpConnectTimeoutException e) {
            throw AtlasAdapterException.notSent("Cannot connect to Atlas for mutation", e);
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error persisting entity: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted persisting entity", 500, e);
        }
    }

    @Override
    public RecordEntityDto getRecordEntityByQualifiedName(String entityType, String qualifiedName) {
        try {
            String q = URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
            // /entity/bulk only resolves guids; the qualified name lookup
            // goes through the unique-attribute endpoint, which answers with
            // an AtlasEntityWithExtInfo ({"entity": {...}}) or 404.
            String path = "/api/atlas/v2/entity/uniqueAttribute/type/" + entityType + "?attr:qualifiedName=" + q;
            HttpRequest req = baseRequest("GET", path).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new AtlasAdapterException(
                        "Failed to fetch record entity from Atlas: " + resp.body(), resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode entity = root.path("entity");
            if (entity.isMissingNode() || entity.isNull()) {
                return null;
            }
            return toRecordEntityDto(entity);
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error fetching entity: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted fetching entity", 500, e);
        }
    }

    @Override
    public void deleteRecordEntity(String entityType, String qualifiedName) {
        try {
            String q = URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
            String path = "/api/atlas/v2/entity/uniqueAttribute/type/" + entityType + "?attr:qualifiedName=" + q;
            HttpRequest req = baseRequest("DELETE", path).DELETE().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            // A 404 means the stub is already gone — the outcome the
            // caller asked for, not a failure.
            if (resp.statusCode() == 404) {
                return;
            }
            if (resp.statusCode() != 200 && resp.statusCode() != 204) {
                throw new AtlasAdapterException(
                        "Failed to delete record entity from Atlas: " + resp.body(), resp.statusCode(), null);
            }
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error deleting entity: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted deleting entity", 500, e);
        }
    }

    @Override
    public RecordEntityDto getRecordEntityByGuid(String guid) {
        if (guid == null || guid.isBlank()) {
            return null;
        }
        try {
            HttpRequest req = baseRequest("GET", "/api/atlas/v2/entity/guid/" + guid)
                    .GET()
                    .build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new AtlasAdapterException(
                        "Failed to fetch record entity from Atlas by GUID: " + resp.body(), resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode entity = root.path("entity");
            if (entity.isMissingNode() || entity.isNull()) {
                return null;
            }
            return toRecordEntityDto(entity);
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error fetching record entity by GUID: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted fetching record entity by GUID", 500, e);
        }
    }

    @Override
    public List<RecordEntityDto> findRecordEntitiesByStatus(String entityType, RecordStatus status, int limit) {
        return findRecordEntities(
                entityType, status, limit, RecordFacets.builder().build());
    }

    @Override
    public List<RecordEntityDto> findRecordEntities(String entityType, SearchQuery query) {
        return findRecordEntities(entityType, query.status(), query.limit(), query.facets());
    }

    private List<RecordEntityDto> findRecordEntities(
            String entityType, RecordStatus status, int limit, RecordFacets facets) {
        if (entityType == null || entityType.isBlank()) {
            throw new IllegalArgumentException("entityType cannot be null or blank");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("typeName", entityType);
        body.put("excludeDeletedEntities", true);
        body.put("limit", limit);
        // Basic search returns entity headers only unless the attributes to
        // load are listed explicitly — without this, rawJsonLd and
        // recordStatus come back null and no record ever gets promoted.
        ArrayNode attributes = body.putArray("attributes");
        for (String attribute : attributesFor(entityType)) {
            attributes.add(attribute);
        }
        ObjectNode filters = body.putObject("entityFilters");
        filters.put("attributeName", "recordStatus");
        filters.put("operator", "=");
        filters.put("attributeValue", status.name());
        if (!facets.isEmpty()) {
            ObjectNode all = objectMapper.createObjectNode().put("condition", "AND");
            ArrayNode conditions = all.putArray("criterion");
            conditions.add(filters);
            facets.values().forEach((facet, values) -> {
                ObjectNode any = objectMapper.createObjectNode().put("condition", "OR");
                ArrayNode alternatives = any.putArray("criterion");
                for (String value : values) {
                    alternatives
                            .addObject()
                            .put("attributeName", "recordFacetIndex")
                            .put("operator", "contains")
                            .put("attributeValue", AtlasFacetIndex.token(facet, value));
                }
                conditions.add(any);
            });
            body.set("entityFilters", all);
        }

        try {
            HttpResponse<String> resp =
                    sendJson("POST", "/api/atlas/v2/search/basic", objectMapper.writeValueAsString(body));
            if (resp.statusCode() != 200) {
                throw new AtlasAdapterException(
                        "Failed to search record entities by status: " + resp.body(), resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            List<RecordEntityDto> result = new ArrayList<>();
            for (JsonNode entity : root.path("entities")) {
                result.add(toRecordEntityDto(entity));
            }
            return result;
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error searching record entities by status: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted searching record entities by status", 500, e);
        }
    }

    /**
     * Graph lookup of every qualified name, one entity type at a time.
     * Each type is read with {@code GET .../bulk/uniqueAttribute}, the
     * bulk form of the single-name lookup, so a record is visible as soon
     * as it is written and a deleted record is simply absent. Names are
     * split so each request target stays within
     * {@link #BULK_LOOKUP_REQUEST_BUDGET}. The first type that holds an
     * id wins. A non-200 aborts the read before the next batch or type.
     */
    @Override
    public Map<String, RecordEntityDto> getRecordEntitiesByQualifiedNames(
            List<String> entityTypes, List<String> qualifiedNames) {
        Map<String, RecordEntityDto> found = new LinkedHashMap<>();
        List<String> names = distinctNames(qualifiedNames);
        if (names.isEmpty() || entityTypes == null || entityTypes.isEmpty()) {
            return found;
        }
        for (String entityType : entityTypes) {
            if (entityType == null || entityType.isBlank()) {
                continue;
            }
            for (RecordEntityDto entity : lookupQualifiedNames(entityType, names, BULK_LOOKUP_REQUEST_BUDGET)) {
                if (entity.getQualifiedName() != null) {
                    found.putIfAbsent(entity.getQualifiedName(), entity);
                }
            }
        }
        return found;
    }

    /** Skips nulls and repeats so each id is asked once per entity type. */
    private static List<String> distinctNames(List<String> qualifiedNames) {
        List<String> names = new ArrayList<>();
        if (qualifiedNames == null) {
            return names;
        }
        for (String name : qualifiedNames) {
            if (name != null && !names.contains(name)) {
                names.add(name);
            }
        }
        return names;
    }

    /**
     * Reads one entity type. Exposed to tests so a tiny budget can force
     * more than one request without building a 12k-character query.
     */
    List<RecordEntityDto> lookupQualifiedNames(String entityType, List<String> qualifiedNames, int requestBudget) {
        List<RecordEntityDto> result = new ArrayList<>();
        for (List<String> batch : batchesForUniqueAttributeLookup(entityType, qualifiedNames, requestBudget)) {
            result.addAll(fetchUniqueAttributeBatch(entityType, batch));
        }
        return result;
    }

    /**
     * Splits names so each bulk unique-attribute request target (path and
     * query) is at most {@code requestBudget} characters. A single name
     * that is already over the budget is left in a batch of its own.
     */
    static List<List<String>> batchesForUniqueAttributeLookup(
            String entityType, List<String> qualifiedNames, int requestBudget) {
        String pathPrefix = "/api/atlas/v2/entity/bulk/uniqueAttribute/type/" + entityType + "?";
        List<List<String>> batches = new ArrayList<>();
        List<String> batch = new ArrayList<>();
        int length = pathPrefix.length();
        for (String name : qualifiedNames) {
            String param = uniqueAttributeParam(batch.size(), name);
            int addition = param.length() + (batch.isEmpty() ? 0 : 1);
            if (!batch.isEmpty() && length + addition > requestBudget) {
                batches.add(batch);
                batch = new ArrayList<>();
                length = pathPrefix.length();
                param = uniqueAttributeParam(0, name);
                addition = param.length();
            }
            batch.add(name);
            length += addition;
        }
        if (!batch.isEmpty()) {
            batches.add(batch);
        }
        return batches;
    }

    private static String uniqueAttributeParam(int index, String qualifiedName) {
        return "attr_" + index + ":qualifiedName=" + URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
    }

    /**
     * One {@code GET /entity/bulk/uniqueAttribute} for the batch. Missing
     * names are omitted from the body; only a non-200 fails the lookup.
     */
    private List<RecordEntityDto> fetchUniqueAttributeBatch(String entityType, List<String> qualifiedNames) {
        StringBuilder query = new StringBuilder();
        for (int i = 0; i < qualifiedNames.size(); i++) {
            if (i > 0) {
                query.append('&');
            }
            query.append(uniqueAttributeParam(i, qualifiedNames.get(i)));
        }
        String path = "/api/atlas/v2/entity/bulk/uniqueAttribute/type/" + entityType + "?" + query;
        try {
            HttpRequest req = baseRequest("GET", path).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new AtlasAdapterException(
                        "Failed to look up record entities by qualified name: " + resp.body(), resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            List<RecordEntityDto> result = new ArrayList<>();
            for (JsonNode entity : root.path("entities")) {
                if (!entity.isObject()) {
                    continue;
                }
                result.add(toRecordEntityDto(entity));
            }
            return result;
        } catch (IOException e) {
            throw new AtlasAdapterException(
                    "I/O error looking up record entities by qualified name: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted looking up record entities by qualified name", 500, e);
        }
    }

    // --- helpers ---

    /** The attributes a basic search must name explicitly per entity type. */
    private static List<String> attributesFor(String entityType) {
        List<String> attributes = new ArrayList<>(List.of("qualifiedName", "name", "description"));
        switch (entityType) {
            case SourcelumeModelDto.TYPE_NAME -> attributes.addAll(List.of("modelUri", "modelVersion", "disclosure"));
            default -> attributes.addAll(List.of("sourceUri", "licenseId"));
        }
        attributes.addAll(List.of(
                "recordStatus",
                "rawJsonLd",
                "validationIssues",
                "sha256",
                "validatedBy",
                "validatedAt",
                "registryRevision",
                "recordAttachments",
                "recordAttributes",
                "recordFacets",
                "recordFacetIndex"));
        return attributes;
    }

    /**
     * Maps one backend entity JSON node onto the DTO matching its
     * {@code typeName}, including the ingest lifecycle fields. A
     * recordStatus the domain does not know is a backend inconsistency
     * and fails loudly instead of silently mapping to null.
     */
    private RecordEntityDto toRecordEntityDto(JsonNode entity) {
        JsonNode attrs = entity.path("attributes");
        String typeName = entity.path("typeName").asText(null);
        RecordEntityDto dto;
        if (SourcelumeModelDto.TYPE_NAME.equals(typeName)) {
            SourcelumeModelDto model = new SourcelumeModelDto();
            model.setModelUri(attrs.path("modelUri").asText(null));
            model.setModelVersion(attrs.path("modelVersion").asText(null));
            model.setDisclosure(attrs.path("disclosure").asText(null));
            dto = model;
        } else {
            SourcelumeDatasetDto dataset = new SourcelumeDatasetDto();
            dataset.setSourceUri(attrs.path("sourceUri").asText(null));
            dataset.setLicenseId(attrs.path("licenseId").asText(null));
            dto = dataset;
        }
        dto.setGuid(entity.path("guid").asText(null));
        dto.setQualifiedName(attrs.path("qualifiedName").asText(null));
        dto.setName(attrs.path("name").asText(null));
        dto.setDescription(attrs.path("description").asText(null));
        dto.setRawJsonLd(attrs.path("rawJsonLd").asText(null));
        dto.setSha256(attrs.path("sha256").asText(null));
        dto.setValidatedBy(attrs.path("validatedBy").asText(null));
        dto.setValidatedAt(attrs.path("validatedAt").asText(null));
        dto.setRegistryRevision(attrs.path("registryRevision").asText(null));
        dto.setRecordAttributes(attrs.path("recordAttributes").asText(null));
        dto.setRecordFacets(attrs.path("recordFacets").asText(null));
        dto.setRecordFacetIndex(attrs.path("recordFacetIndex").asText(null));
        dto.setRecordAttachments(attrs.path("recordAttachments").asText(null));
        dto.setValidationIssues(attrs.path("validationIssues").asText(null));
        JsonNode recordStatus = attrs.path("recordStatus");
        if (recordStatus.isTextual()) {
            try {
                dto.setRecordStatus(RecordStatus.valueOf(recordStatus.asText()));
            } catch (IllegalArgumentException e) {
                throw new AtlasAdapterException(
                        "Unknown record status on stored entity: '" + recordStatus.asText() + "'", 500, e);
            }
        }
        return dto;
    }

    private HttpRequest.Builder baseRequest(String method, String path) {
        String auth = properties.user() + ":" + properties.resolvedPassword();
        String b64 = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        return HttpRequest.newBuilder()
                .uri(URI.create(properties.url() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Basic " + b64)
                .header("Accept", "application/json");
    }

    private HttpResponse<String> sendJson(String method, String path, String body)
            throws IOException, InterruptedException {
        HttpRequest req = baseRequest(method, path)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(body))
                .build();
        return httpClient.send(req, HttpResponse.BodyHandlers.ofString());
    }

    private int parseEntityDefCount(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            return root.has("entityDefs") && root.get("entityDefs").isArray()
                    ? root.get("entityDefs").size()
                    : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private String extractGuid(JsonNode root) {
        JsonNode created = root.path("createdEntities");
        if (created.isArray() && !created.isEmpty()) {
            return created.get(0).path("guid").asText(null);
        }
        JsonNode updated = root.path("updatedEntities");
        if (updated.isArray() && !updated.isEmpty()) {
            return updated.get(0).path("guid").asText(null);
        }
        JsonNode assignments = root.path("guidAssignments");
        if (assignments.isObject() && assignments.size() > 0) {
            return assignments.elements().next().asText(null);
        }
        return null;
    }
}

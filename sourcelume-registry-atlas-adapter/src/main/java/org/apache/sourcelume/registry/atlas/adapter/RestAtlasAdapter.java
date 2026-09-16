/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * Thin REST client implementation of {@link AtlasAdapter} using the JDK
 * {@link HttpClient} and Jackson. Talks to the Apache Atlas REST API directly,
 * without pulling in {@code atlas-client-v2} or {@code atlas-intg} and their
 * transitive dependency tree.
 *
 * <p>Covers exactly the four Atlas endpoints the registry needs:
 * <ul>
 *   <li>{@code GET  /api/atlas/admin/version} — readiness (via isServerReady)</li>
 *   <li>{@code POST /api/atlas/v2/types/typedefs} — create typedefs</li>
 *   <li>{@code PUT  /api/atlas/v2/types/typedefs} — update typedefs (on 409/conflict)</li>
 *   <li>{@code POST /api/atlas/v2/entity} — create/update a dataset entity</li>
 *   <li>{@code GET  /api/atlas/v2/entity/bulk?typeName=...&attr:qualifiedName=...} — lookup</li>
 * </ul>
 *
 * <p>Atlas-specific JSON shapes live here and only here. The interface stays
 * Sourcelume-typed.
 */
@ApplicationScoped
public class RestAtlasAdapter implements AtlasAdapter {

    private static final Logger log = LoggerFactory.getLogger(RestAtlasAdapter.class);

    private final SourcelumeAtlasProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Inject
    public RestAtlasAdapter(SourcelumeAtlasProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @Override
    public boolean isServerReady() {
        try {
            HttpRequest req = baseRequest("GET", "/api/atlas/admin/version").GET().build();
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
                    ? root.get("entityDefs").size() : 0;
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
                return new TypeDefinitionModel(typeDefs.getSourceResource(), resp.body().getBytes(StandardCharsets.UTF_8), count);
            }
            // Atlas returns 409/conflict when a typedef already exists — fall back to PUT (update).
            if (resp.statusCode() == 409 || resp.statusCode() == 400) {
                log.warn("create typedefs failed ({}). Attempting PUT (update)...", resp.statusCode());
                HttpResponse<String> putResp = sendJson("PUT", "/api/atlas/v2/types/typedefs", body);
                if (putResp.statusCode() == 200 || putResp.statusCode() == 204) {
                    log.info("Successfully updated Atlas typedefs");
                    int count = parseEntityDefCount(putResp.body());
                    return new TypeDefinitionModel(typeDefs.getSourceResource(), putResp.body().getBytes(StandardCharsets.UTF_8), count);
                }
                throw new AtlasAdapterException("Failed to register or update typedefs in Atlas: " + putResp.body(),
                        putResp.statusCode(), null);
            }
            throw new AtlasAdapterException("Failed to register typedefs in Atlas: " + resp.body(),
                    resp.statusCode(), null);
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error registering typedefs: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted registering typedefs", 500, e);
        }
    }

    @Override
    public String createOrUpdateDatasetEntity(SourcelumeDatasetDto dataset) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset cannot be null");
        }
        ObjectNode entity = objectMapper.createObjectNode();
        entity.put("typeName", SourcelumeDatasetDto.TYPE_NAME);
        ObjectNode attributes = entity.putObject("attributes");
        attributes.put("qualifiedName", dataset.getQualifiedName());
        attributes.put("name", dataset.getName());
        if (dataset.getDescription() != null) attributes.put("description", dataset.getDescription());
        if (dataset.getSourceUri() != null) attributes.put("sourceUri", dataset.getSourceUri());
        if (dataset.getLicenseId() != null) attributes.put("licenseId", dataset.getLicenseId());

        ObjectNode wrapper = objectMapper.createObjectNode();
        ArrayNode entities = wrapper.putArray("entities");
        entities.add(entity);

        try {
            String body = objectMapper.writeValueAsString(wrapper);
            HttpResponse<String> resp = sendJson("POST", "/api/atlas/v2/entity/bulk", body);
            if (resp.statusCode() != 200 && resp.statusCode() != 201) {
                throw new AtlasAdapterException("Failed to persist dataset entity to Atlas: " + resp.body(),
                        resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            String guid = extractGuid(root);
            log.info("Created/updated dataset entity '{}' with GUID: {}", dataset.getQualifiedName(), guid);
            dataset.setGuid(guid);
            return guid;
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error persisting entity: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted persisting entity", 500, e);
        }
    }

    @Override
    public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
        try {
            String q = URLEncoder.encode(qualifiedName, StandardCharsets.UTF_8);
            String path = "/api/atlas/v2/entity/bulk?typeName=" + SourcelumeDatasetDto.TYPE_NAME
                    + "&attr:qualifiedName=" + q;
            HttpRequest req = baseRequest("GET", path).GET().build();
            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 404) {
                return null;
            }
            if (resp.statusCode() != 200) {
                throw new AtlasAdapterException("Failed to fetch dataset entity from Atlas: " + resp.body(),
                        resp.statusCode(), null);
            }
            JsonNode root = objectMapper.readTree(resp.body());
            JsonNode entities = root.path("entities");
            if (!entities.isArray() || entities.isEmpty()) {
                return null;
            }
            JsonNode entity = entities.get(0);
            JsonNode attrs = entity.path("attributes");
            SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
            dto.setGuid(entity.path("guid").asText(null));
            dto.setQualifiedName(attrs.path("qualifiedName").asText(null));
            dto.setName(attrs.path("name").asText(null));
            dto.setDescription(attrs.path("description").asText(null));
            dto.setSourceUri(attrs.path("sourceUri").asText(null));
            dto.setLicenseId(attrs.path("licenseId").asText(null));
            return dto;
        } catch (IOException e) {
            throw new AtlasAdapterException("I/O error fetching entity: " + e.getMessage(), 500, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AtlasAdapterException("Interrupted fetching entity", 500, e);
        }
    }

    // --- helpers ---

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
                    ? root.get("entityDefs").size() : 0;
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

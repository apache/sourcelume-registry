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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.atlas.AtlasClientV2;
import org.apache.atlas.AtlasServiceException;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.atlas.model.instance.AtlasEntityHeader;
import org.apache.atlas.model.instance.EntityMutationResponse;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Default implementation of AtlasAdapter using AtlasClientV2.
 */
public class DefaultAtlasAdapter implements AtlasAdapter {

    private static final Logger log = LoggerFactory.getLogger(DefaultAtlasAdapter.class);

    private final AtlasClientV2 atlasClient;
    private final SourcelumeAtlasProperties properties;
    private final ObjectMapper objectMapper;

    public DefaultAtlasAdapter(AtlasClientV2 atlasClient,
                               SourcelumeAtlasProperties properties,
                               ObjectMapper objectMapper) {
        this.atlasClient = atlasClient;
        this.properties = properties;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Override
    public boolean isServerReady() {
        try {
            return atlasClient.isServerReady();
        } catch (Exception e) {
            log.debug("Atlas server connectivity check failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public AtlasTypesDef loadTypeDefs(String resourcePath) {
        ClassLoader classLoader = Thread.currentThread().getContextClassLoader();
        if (classLoader == null) {
            classLoader = getClass().getClassLoader();
        }
        URL resource = classLoader.getResource(resourcePath);
        if (resource == null) {
            throw new AtlasAdapterException("Could not find typedefs resource on classpath: " + resourcePath);
        }
        try (InputStream in = resource.openStream()) {
            AtlasTypesDef typesDef = objectMapper.readValue(in, AtlasTypesDef.class);
            log.info("Loaded typedefs with {} entity definition(s) from {}",
                    typesDef.getEntityDefs() != null ? typesDef.getEntityDefs().size() : 0, resourcePath);
            return typesDef;
        } catch (IOException e) {
            throw new AtlasAdapterException("Failed to read typedefs resource: " + resourcePath, e);
        }
    }

    @Override
    public AtlasTypesDef registerOrUpdateTypeDefs(AtlasTypesDef typesDef) {
        if (typesDef == null) {
            throw new IllegalArgumentException("typesDef cannot be null");
        }
        try {
            log.info("Attempting createAtlasTypeDefs in Atlas at {}", properties.getUrl());
            AtlasTypesDef created = atlasClient.createAtlasTypeDefs(typesDef);
            log.info("Successfully created Atlas typedefs");
            return created;
        } catch (AtlasServiceException e) {
            log.warn("createAtlasTypeDefs failed ({}). Attempting updateAtlasTypeDefs...", e.getMessage());
            try {
                AtlasTypesDef updated = atlasClient.updateAtlasTypeDefs(typesDef);
                log.info("Successfully updated Atlas typedefs");
                return updated;
            } catch (AtlasServiceException updateEx) {
                int status = updateEx.getStatus() != null ? updateEx.getStatus().getStatusCode() : 500;
                log.error("Failed to update Atlas typedefs: {}", updateEx.getMessage());
                throw new AtlasAdapterException("Failed to register or update typedefs in Atlas: " + updateEx.getMessage(), status, updateEx);
            }
        }
    }

    @Override
    public AtlasTypesDef registerTypeDefsFromResource(String resourcePath) {
        AtlasTypesDef typesDef = loadTypeDefs(resourcePath);
        return registerOrUpdateTypeDefs(typesDef);
    }

    @Override
    public String createOrUpdateDatasetEntity(SourcelumeDatasetDto dataset) {
        if (dataset == null) {
            throw new IllegalArgumentException("dataset cannot be null");
        }
        AtlasEntity entity = new AtlasEntity(SourcelumeDatasetDto.TYPE_NAME);
        entity.setAttribute("qualifiedName", dataset.getQualifiedName());
        entity.setAttribute("name", dataset.getName());
        if (dataset.getDescription() != null) {
            entity.setAttribute("description", dataset.getDescription());
        }
        if (dataset.getSourceUri() != null) {
            entity.setAttribute("sourceUri", dataset.getSourceUri());
        }
        if (dataset.getLicenseId() != null) {
            entity.setAttribute("licenseId", dataset.getLicenseId());
        }

        AtlasEntity.AtlasEntityWithExtInfo entityWithExtInfo = new AtlasEntity.AtlasEntityWithExtInfo(entity);
        try {
            EntityMutationResponse response = atlasClient.createEntity(entityWithExtInfo);
            List<AtlasEntityHeader> created = response.getCreatedEntities();
            List<AtlasEntityHeader> updated = response.getUpdatedEntities();
            String guid = null;
            if (created != null && !created.isEmpty()) {
                guid = created.get(0).getGuid();
            } else if (updated != null && !updated.isEmpty()) {
                guid = updated.get(0).getGuid();
            } else if (response.getGuidAssignments() != null) {
                guid = response.getGuidAssignments().values().stream().findFirst().orElse(null);
            }
            log.info("Created/updated dataset entity '{}' with GUID: {}", dataset.getQualifiedName(), guid);
            dataset.setGuid(guid);
            return guid;
        } catch (AtlasServiceException e) {
            int status = e.getStatus() != null ? e.getStatus().getStatusCode() : 500;
            throw new AtlasAdapterException("Failed to persist dataset entity to Atlas: " + e.getMessage(), status, e);
        }
    }

    @Override
    public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
        try {
            AtlasEntity.AtlasEntityWithExtInfo entityWithExtInfo =
                    atlasClient.getEntityByAttribute(SourcelumeDatasetDto.TYPE_NAME,
                            Collections.singletonMap("qualifiedName", qualifiedName));
            if (entityWithExtInfo == null || entityWithExtInfo.getEntity() == null) {
                return null;
            }
            AtlasEntity entity = entityWithExtInfo.getEntity();
            SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
            dto.setGuid(entity.getGuid());
            Map<String, Object> attributes = entity.getAttributes();
            if (attributes != null) {
                dto.setQualifiedName((String) attributes.get("qualifiedName"));
                dto.setName((String) attributes.get("name"));
                dto.setDescription((String) attributes.get("description"));
                dto.setSourceUri((String) attributes.get("sourceUri"));
                dto.setLicenseId((String) attributes.get("licenseId"));
            }
            return dto;
        } catch (AtlasServiceException e) {
            if (e.getStatus() != null && e.getStatus().getStatusCode() == 404) {
                return null;
            }
            int status = e.getStatus() != null ? e.getStatus().getStatusCode() : 500;
            throw new AtlasAdapterException("Failed to fetch dataset entity from Atlas: " + e.getMessage(), status, e);
        }
    }

    @Override
    public AtlasClientV2 getAtlasClient() {
        return atlasClient;
    }
}

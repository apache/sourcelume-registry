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
import org.apache.atlas.model.instance.EntityMutations;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultAtlasAdapterTest {

    private AtlasClientV2 atlasClient;
    private SourcelumeAtlasProperties properties;
    private ObjectMapper objectMapper;
    private DefaultAtlasAdapter adapter;

    @BeforeEach
    void setUp() {
        atlasClient = Mockito.mock(AtlasClientV2.class);
        properties = new SourcelumeAtlasProperties();
        objectMapper = new ObjectMapper();
        adapter = new DefaultAtlasAdapter(atlasClient, properties, objectMapper);
    }

    @Test
    void shouldCheckServerReady() throws Exception {
        when(atlasClient.isServerReady()).thenReturn(true);
        assertTrue(adapter.isServerReady());

        when(atlasClient.isServerReady()).thenThrow(new RuntimeException("Connection refused"));
        assertFalse(adapter.isServerReady());
    }

    @Test
    void shouldLoadTypeDefsFromResource() {
        AtlasTypesDef typesDef = adapter.loadTypeDefs("models/sourcelume/sourcelume_model.json");
        assertNotNull(typesDef);
        assertNotNull(typesDef.getEntityDefs());
        assertEquals(1, typesDef.getEntityDefs().size());
        assertEquals("sourcelume_dataset", typesDef.getEntityDefs().get(0).getName());
    }

    @Test
    void shouldThrowWhenTypeDefResourceNotFound() {
        assertThrows(AtlasAdapterException.class, () ->
                adapter.loadTypeDefs("models/nonexistent.json"));
    }

    @Test
    void shouldRegisterTypeDefsOnCreateSuccess() throws Exception {
        AtlasTypesDef input = new AtlasTypesDef();
        AtlasTypesDef created = new AtlasTypesDef();
        when(atlasClient.createAtlasTypeDefs(input)).thenReturn(created);

        AtlasTypesDef result = adapter.registerOrUpdateTypeDefs(input);
        assertEquals(created, result);
        verify(atlasClient).createAtlasTypeDefs(input);
    }

    @Test
    void shouldFallbackToUpdateTypeDefsWhenCreateFails() throws Exception {
        AtlasTypesDef input = new AtlasTypesDef();
        AtlasTypesDef updated = new AtlasTypesDef();
        AtlasServiceException serviceEx = new AtlasServiceException(new Exception("Types already exist"));
        when(atlasClient.createAtlasTypeDefs(input)).thenThrow(serviceEx);
        when(atlasClient.updateAtlasTypeDefs(input)).thenReturn(updated);

        AtlasTypesDef result = adapter.registerOrUpdateTypeDefs(input);
        assertEquals(updated, result);
        verify(atlasClient).createAtlasTypeDefs(input);
        verify(atlasClient).updateAtlasTypeDefs(input);
    }

    @Test
    void shouldCreateOrUpdateDatasetEntity() throws Exception {
        SourcelumeDatasetDto dataset = new SourcelumeDatasetDto(
                "sourcelume.dataset.test@cluster", "test-dataset", "s3://bucket/test", "Apache-2.0");

        EntityMutationResponse response = new EntityMutationResponse();
        AtlasEntityHeader header = new AtlasEntityHeader();
        header.setGuid("guid-1234");
        response.addEntity(EntityMutations.EntityOperation.CREATE, header);

        when(atlasClient.createEntity(any(AtlasEntity.AtlasEntityWithExtInfo.class))).thenReturn(response);

        String guid = adapter.createOrUpdateDatasetEntity(dataset);
        assertEquals("guid-1234", guid);
        assertEquals("guid-1234", dataset.getGuid());
    }

    @Test
    void shouldGetDatasetByQualifiedName() throws Exception {
        AtlasEntity entity = new AtlasEntity("sourcelume_dataset");
        entity.setGuid("guid-5678");
        entity.setAttribute("qualifiedName", "sourcelume.dataset.test@cluster");
        entity.setAttribute("name", "test-dataset");
        entity.setAttribute("sourceUri", "s3://bucket/test");
        entity.setAttribute("licenseId", "Apache-2.0");

        AtlasEntity.AtlasEntityWithExtInfo extInfo = new AtlasEntity.AtlasEntityWithExtInfo(entity);
        when(atlasClient.getEntityByAttribute(eq("sourcelume_dataset"), anyMap())).thenReturn(extInfo);

        SourcelumeDatasetDto dto = adapter.getDatasetByQualifiedName("sourcelume.dataset.test@cluster");
        assertNotNull(dto);
        assertEquals("guid-5678", dto.getGuid());
        assertEquals("sourcelume.dataset.test@cluster", dto.getQualifiedName());
    }

    @Test
    void shouldReturnNullWhenDatasetNotFound() throws Exception {
        when(atlasClient.getEntityByAttribute(eq("sourcelume_dataset"), anyMap())).thenReturn(null);
        SourcelumeDatasetDto dto = adapter.getDatasetByQualifiedName("nonexistent");
        assertNull(dto);
    }
}

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
package org.apache.sourcelume.registry.runtime.quarkus.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.junit.jupiter.api.Test;

class TypeResourceUnitTest {

    @Test
    void testGetDatasetTypeDefIncludesOnlyReferencedEnums() throws Exception {
        TypeResource resource = new TypeResource(
                new StubAtlasAdapter("{\"enumDefs\": [{\"name\": \"used_enum\"}, {\"name\": \"unused_enum\"}],"
                        + " \"entityDefs\": [{\"name\": \"sourcelume_dataset\","
                        + " \"attributeDefs\": [{\"name\": \"status\", \"typeName\": \"used_enum\"}]}]}"),
                new ObjectMapper(),
                "models/sourcelume/sourcelume_model.json");

        JsonNode body = (JsonNode) resource.getDatasetTypeDef().getEntity();

        assertEquals(1, body.path("enumDefs").size());
        assertEquals("used_enum", body.path("enumDefs").get(0).path("name").asText());
    }

    @Test
    void testGetDatasetTypeDefFailsWhenTypeIsMissing() {
        TypeResource resource = new TypeResource(
                new StubAtlasAdapter("{\"enumDefs\": [], \"entityDefs\": [{\"name\": \"other\"}]}"),
                new ObjectMapper(),
                "models/sourcelume/sourcelume_model.json");

        assertThrows(IllegalStateException.class, resource::getDatasetTypeDef);
    }

    @Test
    void testTypeDefsAreLoadedFromTheConfiguredResource() throws Exception {
        StubAtlasAdapter atlasAdapter =
                new StubAtlasAdapter("{\"enumDefs\": [], \"entityDefs\": [{\"name\": \"sourcelume_dataset\"}]}");
        TypeResource resource = new TypeResource(atlasAdapter, new ObjectMapper(), "custom/typedefs.json");

        resource.getTypeDefs();
        assertEquals("custom/typedefs.json", atlasAdapter.loadedResourcePath);

        atlasAdapter.loadedResourcePath = null;
        resource.getDatasetTypeDef();
        assertEquals("custom/typedefs.json", atlasAdapter.loadedResourcePath);
    }

    private static class StubAtlasAdapter implements AtlasAdapter {
        private final String typeDefsJson;
        private String loadedResourcePath;

        StubAtlasAdapter(String typeDefsJson) {
            this.typeDefsJson = typeDefsJson;
        }

        @Override
        public boolean isServerReady() {
            return true;
        }

        @Override
        public TypeDefinitionModel loadTypeDefs(String resourcePath) {
            loadedResourcePath = resourcePath;
            return new TypeDefinitionModel(resourcePath, typeDefsJson.getBytes(StandardCharsets.UTF_8), 1);
        }

        @Override
        public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel model) {
            return model;
        }

        @Override
        public String createOrUpdateRecordEntity(RecordEntityDto record) {
            return null;
        }

        @Override
        public RecordEntityDto getRecordEntityByQualifiedName(String entityType, String qualifiedName) {
            return null;
        }

        @Override
        public void deleteRecordEntity(String entityType, String qualifiedName) {}

        @Override
        public RecordEntityDto getRecordEntityByGuid(String guid) {
            return null;
        }

        @Override
        public List<RecordEntityDto> findRecordEntitiesByStatus(String entityType, RecordStatus status, int limit) {
            return List.of();
        }
    }
}

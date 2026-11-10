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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.tck.RecordStoreContract;
import org.junit.jupiter.api.Test;

class AtlasStoreContractTest extends RecordStoreContract {
    @Override
    protected RegistryPlugin createPlugin() {
        return new AtlasRegistryPlugin(new Entities(), new ObjectMapper(), "unused");
    }

    @Test
    void batchReadRejectsAnIdStoredUnderTwoTypes() {
        var entities = new Entities();
        var mapper = new AtlasRecordMapper(new ObjectMapper());
        for (String type : List.of("ProvenanceRecord", "TrainingProvenanceRecord"))
            entities.createOrUpdateRecordEntity(
                    mapper.write(record("urn:duplicate", type, "{}", RecordStatus.INCOMPLETE), "r1"));
        try (var atlas = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.INVALID_DATA,
                    assertThrows(StorageException.class, () -> atlas.getAll(List.of("urn:duplicate")))
                            .code());
        }
    }

    @Test
    void laterTypeFailureDoesNotReturnAnEarlierPartialBatch() {
        var entities = new Entities() {
            int calls;

            @Override
            public Map<String, RecordEntityDto> getRecordEntitiesByQualifiedNames(
                    List<String> types, List<String> ids) {
                if (++calls == 2)
                    throw new AtlasAdapterException("unavailable", new java.io.IOException("connection lost"));
                return super.getRecordEntitiesByQualifiedNames(types, ids);
            }
        };
        var mapper = new AtlasRecordMapper(new ObjectMapper());
        entities.createOrUpdateRecordEntity(
                mapper.write(record("urn:one", "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE), "r1"));
        try (var atlas = new AtlasRegistryPlugin(entities, new ObjectMapper(), "unused")) {
            assertEquals(
                    StorageException.Code.UNAVAILABLE,
                    assertThrows(StorageException.class, () -> atlas.getAll(List.of("urn:one")))
                            .code());
            assertEquals(2, entities.calls);
        }
    }

    static class Entities implements AtlasAdapter {
        final Map<String, RecordEntityDto> values = new ConcurrentHashMap<>();

        public boolean isServerReady() {
            return true;
        }

        public TypeDefinitionModel loadTypeDefs(String resource) {
            return new TypeDefinitionModel(resource, new byte[0], 2);
        }

        public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel model) {
            return model;
        }

        public String createOrUpdateRecordEntity(RecordEntityDto entity) {
            values.put(entity.getTypeName() + ":" + entity.getQualifiedName(), entity);
            return "guid";
        }

        public RecordEntityDto getRecordEntityByQualifiedName(String type, String id) {
            return values.get(type + ":" + id);
        }

        public void deleteRecordEntity(String type, String id) {
            values.remove(type + ":" + id);
        }

        @Override
        public List<RecordEntityDto> findRecordEntities(String type, SearchQuery query) {
            var mapper = new AtlasRecordMapper(new ObjectMapper());
            return values.values().stream()
                    .filter(e -> type.equals(e.getTypeName()) && e.getRecordStatus() == query.status())
                    .filter(e -> mapper.read(e).record().facets().matches(query.facets()))
                    .limit(query.limit())
                    .toList();
        }

        public List<RecordEntityDto> findRecordEntitiesByStatus(String type, RecordStatus status, int limit) {
            return values.values().stream()
                    .filter(e -> type.equals(e.getTypeName()) && e.getRecordStatus() == status)
                    .limit(limit)
                    .toList();
        }
    }
}

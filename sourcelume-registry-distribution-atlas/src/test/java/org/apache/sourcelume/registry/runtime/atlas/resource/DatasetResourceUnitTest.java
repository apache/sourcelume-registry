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
package org.apache.sourcelume.registry.runtime.atlas.resource;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.ws.rs.core.Response;
import java.util.List;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DatasetResourceUnitTest {

    private DatasetResource resource;
    private StubAtlasAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new StubAtlasAdapter();
        resource = new DatasetResource(adapter);
    }

    @Test
    void testGetDatasetByQueryWithMissingParamReturnsBadRequest() {
        Response r1 = resource.getDatasetByQuery(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        Response r2 = resource.getDatasetByQuery("   ");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r2.getStatus());
    }

    @Test
    void testGetDatasetByQueryFoundAndNotFound() {
        SourcelumeDatasetDto ds = new SourcelumeDatasetDto();
        ds.setQualifiedName("sourcelume://datasets/test1");
        ds.setName("Test 1");
        adapter.storedByQName = ds;

        Response found = resource.getDatasetByQuery("sourcelume://datasets/test1");
        assertEquals(Response.Status.OK.getStatusCode(), found.getStatus());

        adapter.storedByQName = null;
        Response notFound = resource.getDatasetByQuery("sourcelume://datasets/missing");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), notFound.getStatus());
    }

    @Test
    void testGetDatasetByGuidValidationAndLookup() {
        Response r1 = resource.getDatasetByGuid(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        Response r2 = resource.getDatasetByGuid("   ");
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r2.getStatus());

        SourcelumeDatasetDto ds = new SourcelumeDatasetDto();
        ds.setGuid("guid-123");
        adapter.datasetByGuid = ds;

        Response found = resource.getDatasetByGuid("guid-123");
        assertEquals(Response.Status.OK.getStatusCode(), found.getStatus());

        adapter.datasetByGuid = null;
        Response notFound = resource.getDatasetByGuid("guid-missing");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), notFound.getStatus());
    }

    @Test
    void testGetDatasetByPathReconstruction() {
        Response r1 = resource.getDatasetByPath(null);
        assertEquals(Response.Status.BAD_REQUEST.getStatusCode(), r1.getStatus());

        SourcelumeDatasetDto ds = new SourcelumeDatasetDto();
        ds.setQualifiedName("sourcelume://datasets/test1");
        adapter.storedByQName = ds;

        Response foundReconstructed = resource.getDatasetByPath("sourcelume:/datasets/test1");
        assertEquals(Response.Status.OK.getStatusCode(), foundReconstructed.getStatus());

        adapter.storedByQName = null;
        Response notFound = resource.getDatasetByPath("sourcelume:/datasets/missing");
        assertEquals(Response.Status.NOT_FOUND.getStatusCode(), notFound.getStatus());
    }

    private static class StubAtlasAdapter implements AtlasAdapter {
        RecordEntityDto storedByQName;
        SourcelumeDatasetDto datasetByGuid;

        @Override
        public boolean isServerReady() {
            return true;
        }

        @Override
        public TypeDefinitionModel loadTypeDefs(String resourcePath) {
            return null;
        }

        @Override
        public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel model) {
            return null;
        }

        @Override
        public String createOrUpdateRecordEntity(RecordEntityDto record) {
            return "guid-created";
        }

        @Override
        public RecordEntityDto getRecordEntityByQualifiedName(String entityType, String qualifiedName) {
            if (storedByQName != null
                    && entityType.equals(storedByQName.getTypeName())
                    && storedByQName.getQualifiedName().equals(qualifiedName)) {
                return storedByQName;
            }
            return null;
        }

        @Override
        public void deleteRecordEntity(String entityType, String qualifiedName) {}

        @Override
        public RecordEntityDto getRecordEntityByGuid(String guid) {
            return datasetByGuid;
        }

        @Override
        public List<RecordEntityDto> findRecordEntitiesByStatus(String entityType, RecordStatus status, int limit) {
            return List.of();
        }
    }
}

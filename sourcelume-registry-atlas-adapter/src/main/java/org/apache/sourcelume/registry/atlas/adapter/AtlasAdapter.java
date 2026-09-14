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

import org.apache.atlas.AtlasClientV2;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;

/**
 * Narrow domain adapter isolating Apache Atlas client operations from the rest of Sourcelume.
 */
public interface AtlasAdapter {

    /**
     * Checks if the Apache Atlas server is reachable and responsive.
     *
     * @return true if ready, false otherwise
     */
    boolean isServerReady();

    /**
     * Loads typedef definitions from a classpath JSON resource.
     *
     * @param resourcePath classpath resource path
     * @return parsed AtlasTypesDef
     * @throws AtlasAdapterException on failure
     */
    AtlasTypesDef loadTypeDefs(String resourcePath);

    /**
     * Registers or updates typedefs in Apache Atlas.
     *
     * @param typesDef the typedefs to register or update
     * @return the created or updated AtlasTypesDef
     * @throws AtlasAdapterException on failure
     */
    AtlasTypesDef registerOrUpdateTypeDefs(AtlasTypesDef typesDef);

    /**
     * Convenience method to load and register/update typedefs from a classpath resource.
     *
     * @param resourcePath classpath resource path
     * @return the created or updated AtlasTypesDef
     * @throws AtlasAdapterException on failure
     */
    AtlasTypesDef registerTypeDefsFromResource(String resourcePath);

    /**
     * Creates or updates a sourcelume_dataset entity in Atlas.
     *
     * @param dataset dataset DTO
     * @return the assigned GUID
     * @throws AtlasAdapterException on failure
     */
    String createOrUpdateDatasetEntity(SourcelumeDatasetDto dataset);

    /**
     * Fetches a sourcelume_dataset entity by its qualifiedName.
     *
     * @param qualifiedName qualified name of the dataset
     * @return the dataset DTO or null if not found
     * @throws AtlasAdapterException on failure
     */
    SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName);

    /**
     * Direct handle to the underlying AtlasClientV2 for specialized write/batch requirements.
     *
     * @return AtlasClientV2 instance
     */
    AtlasClientV2 getAtlasClient();
}

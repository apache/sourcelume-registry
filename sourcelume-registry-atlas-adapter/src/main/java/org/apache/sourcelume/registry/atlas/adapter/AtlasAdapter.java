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

import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;

/**
 * Backend-neutral adapter isolating Atlas (or any provenance-graph backend) operations
 * from the rest of Sourcelume.
 *
 * <p>The interface exposes only Sourcelume types. The Atlas-specific JSON mapping and
 * REST wire format live entirely in the implementation ({@code RestAtlasAdapter}).
 * This keeps {@code sourcelume-registry-common} and the ingest worker free of any
 * Atlas SDK dependency, and lets other backends implement the same contract.
 *
 * <p>Per Calvin's dev-list note (Sept 2026): "keep Atlas-specific types out of
 * registry-core; the core should expose a backend-neutral interface using
 * Sourcelume types, with the Atlas implementation owning the typedef mapping."
 */
public interface AtlasAdapter {

    /**
     * Checks if the backend server is reachable and responsive.
     *
     * @return true if ready, false otherwise
     */
    boolean isServerReady();

    /**
     * Loads a type definition model from a classpath JSON resource, parsed into the
     * backend's native typedef JSON (the implementation owns the shape).
     *
     * @param resourcePath classpath resource path
     * @return the parsed type definition as a backend-neutral holder
     */
    TypeDefinitionModel loadTypeDefs(String resourcePath);

    /**
     * Registers or updates type definitions in the backend. If a type already
     * exists, the implementation updates it; otherwise it creates it.
     *
     * @param typeDefs the type definitions to register
     * @return the backend's response, parsed into a backend-neutral holder
     */
    TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel typeDefs);

    /**
     * Convenience: load from a resource and register in one call.
     */
    default TypeDefinitionModel registerTypeDefsFromResource(String resourcePath) {
        return registerOrUpdateTypeDefs(loadTypeDefs(resourcePath));
    }

    /**
     * Creates or updates a dataset entity in the backend.
     *
     * @param dataset the dataset to persist
     * @return the backend-assigned identifier (e.g. GUID) for the entity
     */
    String createOrUpdateDatasetEntity(SourcelumeDatasetDto dataset);

    /**
     * Retrieves a dataset by its qualified name, or {@code null} if not found.
     *
     * @param qualifiedName the qualified name to look up
     * @return the dataset DTO, or null
     */
    SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName);

    /**
     * Backend-neutral holder for a parsed type definition model. The Atlas
     * implementation carries the raw JSON-LD/JSON structure; other backends
     * would carry their own. This keeps the Atlas wire shape out of the
     * interface signature while still passing the payload through.
     */
    final class TypeDefinitionModel {
        private final String sourceResource;
        private final byte[] rawJson;
        private final int entityDefCount;

        public TypeDefinitionModel(String sourceResource, byte[] rawJson, int entityDefCount) {
            this.sourceResource = sourceResource;
            this.rawJson = rawJson;
            this.entityDefCount = entityDefCount;
        }

        public String getSourceResource() {
            return sourceResource;
        }

        public byte[] getRawJson() {
            return rawJson;
        }

        public int getEntityDefCount() {
            return entityDefCount;
        }
    }
}

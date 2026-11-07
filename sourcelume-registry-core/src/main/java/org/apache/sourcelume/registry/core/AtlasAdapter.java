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
package org.apache.sourcelume.registry.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.RecordStatus;

/**
 * Backend-neutral adapter isolating Atlas (or any provenance-graph backend) operations
 * from the rest of Sourcelume.
 *
 * <p>The interface exposes only Sourcelume types. Backend-specific wire formats
 * and SDK mapping live entirely in the runtime implementation (for example a
 * thin REST client in a Quarkus runtime module). This keeps the core free of any
 * backend SDK dependency, and lets other backends implement the same contract.
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
     * Creates or updates a record entity in the backend. The entity's own
     * {@link RecordEntityDto#getTypeName() typeName} decides which entity
     * type it is written under (dataset, model, ...), so a DTO can never
     * be persisted under the wrong type.
     *
     * @param entity the record entity to persist
     * @return the backend-assigned identifier (e.g. GUID) for the entity
     */
    String createOrUpdateRecordEntity(RecordEntityDto entity);

    /**
     * Retrieves a record entity of the given type by its qualified name,
     * or {@code null} if not found.
     *
     * @param entityType   the backend entity type (e.g. {@code sourcelume_dataset})
     * @param qualifiedName the qualified name to look up
     * @return the entity DTO, or null
     */
    RecordEntityDto getRecordEntityByQualifiedName(String entityType, String qualifiedName);

    /**
     * Retrieves every record entity stored for the qualified name across the
     * given entity types, in the order given. An id names one record
     * whichever record store it landed in, so guards must look everywhere —
     * a lookup that checks only the store a submission targets stays blind
     * to a sibling store's copy of the same id.
     *
     * @param entityTypes   the entity types to search, in order
     * @param qualifiedName the qualified name to look up
     * @return the entities found for the id, never null
     */
    default List<RecordEntityDto> getRecordEntitiesByQualifiedName(List<String> entityTypes, String qualifiedName) {
        List<RecordEntityDto> found = new ArrayList<>();
        for (String entityType : entityTypes) {
            RecordEntityDto entity = getRecordEntityByQualifiedName(entityType, qualifiedName);
            if (entity != null) {
                found.add(entity);
            }
        }
        return found;
    }

    /**
     * Retrieves the record each qualified name denotes, across the given
     * entity types. An id names one record, so the first entity type that
     * holds it wins and a later type must not replace it. Names the backend
     * does not hold are absent from the map. Never returns null.
     *
     * <p>The default walks the single-name lookup, so a backend without a
     * bulk search still satisfies the contract. One failed lookup aborts
     * the rest of the batch: the caller marks every name instead of asking
     * a backend that just failed about the names that remain.
     *
     * @param entityTypes the entity types to search, in precedence order
     * @param qualifiedNames the qualified names to look up; duplicates are ignored
     * @return the entities found, keyed by qualified name, never null
     */
    default Map<String, RecordEntityDto> getRecordEntitiesByQualifiedNames(
            List<String> entityTypes, List<String> qualifiedNames) {
        Map<String, RecordEntityDto> found = new LinkedHashMap<>();
        if (qualifiedNames == null || qualifiedNames.isEmpty()) {
            return found;
        }
        for (String qualifiedName : qualifiedNames) {
            if (qualifiedName == null || found.containsKey(qualifiedName)) {
                continue;
            }
            List<RecordEntityDto> entities = getRecordEntitiesByQualifiedName(entityTypes, qualifiedName);
            if (!entities.isEmpty()) {
                found.put(qualifiedName, entities.get(0));
            }
        }
        return found;
    }

    /**
     * Deletes the record entity stored under the given type and qualified
     * name. Backends may soft-delete (Atlas marks entities DELETED); the
     * registry's lookups must no longer return the deleted entity.
     *
     * @param entityType    the backend entity type the entity is stored under
     * @param qualifiedName the qualified name of the entity to delete
     */
    void deleteRecordEntity(String entityType, String qualifiedName);

    /**
     * Retrieves a record entity by its backend GUID, or {@code null} if
     * not found. GUIDs are unique across entity types, so the returned
     * DTO's concrete type follows the stored entity's typeName.
     *
     * @param guid the unique entity GUID to look up
     * @return the entity DTO, or null
     */
    default RecordEntityDto getRecordEntityByGuid(String guid) {
        return null;
    }

    /**
     * Returns the entities of the given type currently in the given record
     * status — the search consumers use to filter on VALIDATED records.
     * Implementations never return null; an empty list means nothing matches.
     *
     * @param entityType the backend entity type to search (e.g. {@code sourcelume_dataset})
     * @param status the record status to look for
     * @param limit  the maximum number of entities to return
     * @return the entities in the given status, at most {@code limit}, never null
     */
    List<RecordEntityDto> findRecordEntitiesByStatus(String entityType, RecordStatus status, int limit);

    /**
     * Backend-neutral holder for a parsed type definition model. The Atlas
     * implementation carries the raw JSON-LD/JSON structure; other backends
     * would carry their own. This keeps the backend wire shape out of the
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

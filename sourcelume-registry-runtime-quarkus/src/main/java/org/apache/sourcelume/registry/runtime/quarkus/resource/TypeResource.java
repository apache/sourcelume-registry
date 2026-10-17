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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.AtlasAdapter.TypeDefinitionModel;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * REST endpoint exposing Sourcelume model and type definitions.
 *
 * <ul>
 *   <li>{@code GET /api/v1/types/typedefs} — returns full typedefs model</li>
 *   <li>{@code GET /api/v1/types/dataset} — returns dataset entity definition model</li>
 * </ul>
 */
@Path("/api/v1/types")
@Tag(name = "Types", description = "The Sourcelume type definitions registered in the backend")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class TypeResource {

    private final AtlasAdapter atlasAdapter;
    private final ObjectMapper objectMapper;

    @Inject
    public TypeResource(AtlasAdapter atlasAdapter, ObjectMapper objectMapper) {
        this.atlasAdapter = atlasAdapter;
        this.objectMapper = objectMapper;
    }

    /**
     * Returns the full Sourcelume typedefs registered or bootstrapped in Atlas.
     */
    @GET
    @Path("/typedefs")
    @Operation(summary = "Get the Sourcelume typedefs")
    @APIResponse(
            responseCode = "200",
            description = "The typedefs model",
            content = @Content(schema = @Schema(type = SchemaType.OBJECT)))
    public Response getTypeDefs() {
        TypeDefinitionModel model = atlasAdapter.loadTypeDefs("models/sourcelume/sourcelume_model.json");
        return Response.ok(new String(model.getRawJson(), StandardCharsets.UTF_8))
                .build();
    }

    /**
     * Returns the Sourcelume dataset entity definition and the enums its attributes use.
     */
    @GET
    @Path("/dataset")
    @Operation(summary = "Get the dataset entity definition")
    @APIResponse(
            responseCode = "200",
            description = "The sourcelume_dataset entity definition and the enums its attributes use,"
                    + " in the typedefs format",
            content = @Content(schema = @Schema(type = SchemaType.OBJECT)))
    public Response getDatasetTypeDef() throws IOException {
        TypeDefinitionModel model = atlasAdapter.loadTypeDefs("models/sourcelume/sourcelume_model.json");
        JsonNode typeDefs = objectMapper.readTree(model.getRawJson());
        JsonNode datasetDef = findByName(typeDefs.path("entityDefs"), SourcelumeDatasetDto.TYPE_NAME);
        if (datasetDef == null) {
            throw new IllegalStateException(SourcelumeDatasetDto.TYPE_NAME + " is missing from the typedefs");
        }

        // Exact type names only: collection types (array<...>) and structDefs are not resolved
        Set<String> attributeTypes = new HashSet<>();
        for (JsonNode attributeDef : datasetDef.path("attributeDefs")) {
            attributeTypes.add(attributeDef.path("typeName").asText());
        }
        ObjectNode datasetTypeDef = objectMapper.createObjectNode();
        ArrayNode enumDefs = datasetTypeDef.putArray("enumDefs");
        for (JsonNode enumDef : typeDefs.path("enumDefs")) {
            if (attributeTypes.contains(enumDef.path("name").asText())) {
                enumDefs.add(enumDef);
            }
        }
        datasetTypeDef.putArray("entityDefs").add(datasetDef);
        return Response.ok(datasetTypeDef).build();
    }

    private static JsonNode findByName(JsonNode defs, String name) {
        for (JsonNode def : defs) {
            if (name.equals(def.path("name").asText())) {
                return def;
            }
        }
        return null;
    }
}

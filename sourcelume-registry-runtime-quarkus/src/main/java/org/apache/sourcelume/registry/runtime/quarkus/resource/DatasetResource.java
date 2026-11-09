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

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.runtime.quarkus.BackendFailureMapper;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * REST endpoint exposing read-only dataset views backed by {@link AtlasAdapter}.
 *
 * <p>Read-only views of dataset entities; records are written via {@code POST /records}:
 * <ul>
 *   <li>{@code GET  /api/v1/datasets?qualifiedName=...} — lookup by qualifiedName query param</li>
 *   <li>{@code GET  /api/v1/datasets/{qualifiedName}} — lookup by qualifiedName path param</li>
 *   <li>{@code GET  /api/v1/datasets/guid/{guid}} — lookup by Atlas GUID</li>
 * </ul>
 */
@Path("/api/v1/datasets")
@Tag(
        name = "Datasets",
        description = "Read-only views of dataset entities; provenance records are written via /records")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
public class DatasetResource {

    private final java.util.function.Supplier<AtlasAdapter> atlasAdapter;

    @Inject
    public DatasetResource(
            org.apache.sourcelume.registry.runtime.quarkus.storage.AtlasCompatibilityProducer compatibility) {
        this.atlasAdapter = compatibility::adapter;
    }

    DatasetResource(AtlasAdapter adapter) {
        this.atlasAdapter = () -> adapter;
    }

    /**
     * Query dataset by query parameters, e.g. {@code /api/v1/datasets?qualifiedName=...}.
     */
    @GET
    @Operation(summary = "Get a dataset by qualifiedName")
    @APIResponse(
            responseCode = "200",
            description = "The dataset",
            content = @Content(schema = @Schema(implementation = SourcelumeDatasetDto.class)))
    @APIResponse(
            responseCode = "400",
            description = "Missing qualifiedName",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "404",
            description = "No dataset for this qualifiedName",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response getDatasetByQuery(
            @Parameter(description = "The dataset's qualified name", required = true) @QueryParam("qualifiedName")
                    String qualifiedName) {
        if (qualifiedName != null && !qualifiedName.isBlank()) {
            SourcelumeDatasetDto dto = getDatasetByQualifiedName(qualifiedName);
            if (dto == null) {
                return Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorResponse("Dataset not found for qualifiedName: " + qualifiedName))
                        .build();
            }
            return Response.ok(dto).build();
        }
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(new ErrorResponse("Query parameter 'qualifiedName' is required"))
                .build();
    }

    /**
     * Retrieves a dataset by its Atlas GUID.
     */
    @GET
    @Path("/guid/{guid}")
    @Operation(summary = "Get a dataset by its backend GUID")
    @APIResponse(
            responseCode = "200",
            description = "The dataset",
            content = @Content(schema = @Schema(implementation = SourcelumeDatasetDto.class)))
    @APIResponse(
            responseCode = "404",
            description = "No dataset for this GUID",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response getDatasetByGuid(@PathParam("guid") String guid) {
        if (guid == null || guid.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("guid is required"))
                    .build();
        }
        // A GUID can point at any record entity; the dataset API only
        // answers for dataset entities.
        RecordEntityDto stored = atlasAdapter.get().getRecordEntityByGuid(guid);
        if (!(stored instanceof SourcelumeDatasetDto)) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Dataset not found for guid: " + guid))
                    .build();
        }
        return Response.ok(stored).build();
    }

    /**
     * Retrieves a dataset by its qualified name passed in the path.
     */
    @GET
    @Path("/{qualifiedName: .+}")
    @Operation(summary = "Get a dataset by qualifiedName in the path")
    @APIResponse(
            responseCode = "200",
            description = "The dataset",
            content = @Content(schema = @Schema(implementation = SourcelumeDatasetDto.class)))
    @APIResponse(
            responseCode = "404",
            description = "No dataset for this qualifiedName",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response getDatasetByPath(@PathParam("qualifiedName") String qualifiedName) {
        if (qualifiedName == null || qualifiedName.isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST)
                    .entity(new ErrorResponse("qualifiedName is required"))
                    .build();
        }
        SourcelumeDatasetDto dto = getDatasetByQualifiedName(qualifiedName);
        if (dto == null && qualifiedName.contains(":/") && !qualifiedName.contains("://")) {
            // Handle URI schemes where HTTP path normalization collapsed '//' to '/'
            String reconstructed = qualifiedName.replaceFirst(":/", "://");
            dto = getDatasetByQualifiedName(reconstructed);
        }
        if (dto == null) {
            return Response.status(Response.Status.NOT_FOUND)
                    .entity(new ErrorResponse("Dataset not found for qualifiedName: " + qualifiedName))
                    .build();
        }
        return Response.ok(dto).build();
    }

    private SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
        RecordEntityDto stored =
                atlasAdapter.get().getRecordEntityByQualifiedName(SourcelumeDatasetDto.TYPE_NAME, qualifiedName);
        return stored instanceof SourcelumeDatasetDto ? (SourcelumeDatasetDto) stored : null;
    }

    /** Backend failures get this API's own error body, not the ingest API's. */
    @ServerExceptionMapper
    public Response mapBackendFailure(AtlasAdapterException e) {
        return BackendFailureMapper.respond(e, ErrorResponse::new);
    }

    /** Wire DTO for error responses; named apart from the ingest API's ErrorResponse in the OpenAPI document. */
    @Schema(name = "DatasetErrorResponse")
    public record ErrorResponse(String error) {}
}

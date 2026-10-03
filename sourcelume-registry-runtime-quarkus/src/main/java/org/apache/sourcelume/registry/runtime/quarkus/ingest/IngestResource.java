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
package org.apache.sourcelume.registry.runtime.quarkus.ingest;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.AtlasAdapter;

/**
 * Ingest endpoints. {@code POST /records} stores a submitted record as a
 * PENDING entity — byte-identical, keyed by the record IRI — and returns
 * immediately; the ingest worker validates and promotes it asynchronously.
 * {@code GET /records/{id}} reports where the record is in that lifecycle.
 *
 * <p>The API never validates: a record's validity is a state, not a
 * gate. The guarantee is "no record is ACTIVE without validation".
 *
 * <p>Status codes: {@code 202} with a Location header on acceptance; a
 * record that is already PENDING or ACTIVE is rejected with {@code 409};
 * an INCOMPLETE record accepts a corrected resubmission (returning it to
 * PENDING); a document without a usable {@code id} is {@code 400}; an
 * unknown record on GET is {@code 404}.
 */
@Path("/records")
public class IngestResource {

    private final AtlasAdapter atlasAdapter;

    @Inject
    IngestResource(AtlasAdapter atlasAdapter) {
        this.atlasAdapter = atlasAdapter;
    }

    @POST
    @Consumes({MediaType.APPLICATION_JSON, "application/ld+json"})
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(String rawJsonLd) {
        String recordId;
        try {
            recordId = ProvenanceRecordMapper.extractRecordId(rawJsonLd);
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }

        SourcelumeDatasetDto existing = atlasAdapter.getDatasetByQualifiedName(recordId);
        if (existing != null && existing.getRecordStatus() != RecordStatus.INCOMPLETE) {
            return Response.status(409)
                    .entity(new ConflictResponse(recordId, existing.getRecordStatus()))
                    .build();
        }

        SourcelumeDatasetDto pending = new SourcelumeDatasetDto();
        pending.setQualifiedName(recordId);
        pending.setRecordStatus(RecordStatus.PENDING);
        pending.setRawJsonLd(rawJsonLd);
        atlasAdapter.createOrUpdateDatasetEntity(pending);

        return Response.status(202)
                .location(locationOf(recordId))
                .entity(new AcceptedResponse(recordId, RecordStatus.PENDING))
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String id) {
        SourcelumeDatasetDto dataset = atlasAdapter.getDatasetByQualifiedName(id);
        if (dataset == null) {
            return Response.status(404)
                    .entity(new ErrorResponse("No record for id: " + id))
                    .build();
        }
        return Response.ok(RecordResponse.of(dataset)).build();
    }

    private static URI locationOf(String recordId) {
        String encoded = URLEncoder.encode(recordId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("/records/" + encoded);
    }

    /** Wire DTO for the 202 response: what was accepted, and where to watch it. */
    public record AcceptedResponse(String qualifiedName, RecordStatus recordStatus) {}

    /** Wire DTO for the 409 response: what is in the way, and in which state. */
    public record ConflictResponse(String qualifiedName, RecordStatus recordStatus) {}

    /** Wire DTO for the status view of a stored record. */
    public record RecordResponse(
            String qualifiedName,
            RecordStatus recordStatus,
            String name,
            String licenseId,
            String sourceUri,
            String validationIssues) {

        static RecordResponse of(SourcelumeDatasetDto dataset) {
            return new RecordResponse(
                    dataset.getQualifiedName(),
                    dataset.getRecordStatus(),
                    dataset.getName(),
                    dataset.getLicenseId(),
                    dataset.getSourceUri(),
                    dataset.getValidationIssues());
        }
    }

    /** Wire DTO for error responses. */
    public record ErrorResponse(String message) {}
}

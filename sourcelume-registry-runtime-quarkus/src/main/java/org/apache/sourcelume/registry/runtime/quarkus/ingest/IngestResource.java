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

package org.apache.sourcelume.registry.runtime.quarkus.ingest;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.AtlasAdapter;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/**
 * Ingest endpoints. {@code POST /records} validates a submitted record
 * synchronously through the ingest pipeline and stores it with its
 * verdict — byte-identical, keyed by the record IRI — answering with
 * {@code 201} (new record) or {@code 200} (corrected resubmission of an
 * INCOMPLETE record) and the verdict in the body. {@code GET /records/{id}}
 * reports the stored state.
 *
 * <p>Every stored record carries its validation verdict: VALIDATED is
 * published (consumers filter on it), INCOMPLETE carries its issues and
 * stays curatable.
 *
 * <p>Status codes: {@code 201}/{@code 200} with a Location header; a
 * record that is already VALIDATED is rejected with {@code 409}; a
 * document without a usable {@code id} is {@code 400}; an unknown record
 * on GET is {@code 404}.
 */
@Path("/records")
public class IngestResource {

    private final AtlasAdapter atlasAdapter;
    private final IngestService ingestService;
    private final ObjectMapper objectMapper;

    @Inject
    IngestResource(AtlasAdapter atlasAdapter, IngestService ingestService, ObjectMapper objectMapper) {
        this.atlasAdapter = atlasAdapter;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    @POST
    @Consumes({MediaType.APPLICATION_JSON, "application/ld+json"})
    @Produces(MediaType.APPLICATION_JSON)
    public Response submit(String rawJsonLd) {
        String recordId;
        try {
            recordId = ProvenanceRecordMapper.extractRecordId(rawJsonLd);
        } catch (IllegalArgumentException e) {
            return Response.status(400).entity(new ErrorResponse(e.getMessage())).build();
        }

        SourcelumeDatasetDto existing = atlasAdapter.getDatasetByQualifiedName(recordId);
        if (existing != null && existing.getRecordStatus() == RecordStatus.VALIDATED) {
            return Response.status(409)
                    .entity(new ConflictResponse(recordId, existing.getRecordStatus()))
                    .build();
        }

        // Inline validation: the same IngestService a queue consumer
        // would call the day a slow validation step makes a real queue
        // worth its price. The record reaches the store only with its
        // verdict.
        SourcelumeDatasetDto submitted = new SourcelumeDatasetDto();
        submitted.setQualifiedName(recordId);
        submitted.setRawJsonLd(rawJsonLd);
        // Atlas requires a name on every entity write; a conforming
        // record's promotion overwrites the placeholder with the mapped
        // name, an INCOMPLETE verdict keeps it.
        submitted.setName(placeholderName(recordId));
        if (existing != null) {
            submitted.setGuid(existing.getGuid());
        }
        IngestResult result = ingestService.process(submitted);

        return Response.status(existing == null ? 201 : 200)
                .location(locationOf(recordId))
                .entity(IngestResponse.of(result, objectMapper))
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    public Response status(@PathParam("id") String id) {
        SourcelumeDatasetDto dataset = atlasAdapter.getDatasetByQualifiedName(id);
        if (dataset == null) {
            return Response.status(404).entity(new ErrorResponse("No record for id: " + id)).build();
        }
        return Response.ok(RecordResponse.of(dataset)).build();
    }

    /**
     * Atlas' Asset supertype requires a name on every entity, so the
     * PENDING write carries a placeholder derived from the record id;
     * the promotion overwrites it with the mapped name.
     */
    private static String placeholderName(String recordId) {
        int lastSegment = recordId.lastIndexOf('/');
        String candidate = lastSegment >= 0 ? recordId.substring(lastSegment + 1) : recordId;
        return candidate.isBlank() ? recordId : candidate;
    }

    private static URI locationOf(String recordId) {
        String encoded = URLEncoder.encode(recordId, StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create("/records/" + encoded);
    }

    /**
     * Wire DTO for the verdict response: the record's id, its status, and
     * — for an INCOMPLETE record — the issues to fix.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record IngestResponse(String qualifiedName, RecordStatus recordStatus,
            String validationIssues) {

        static IngestResponse of(IngestResult result, ObjectMapper objectMapper) {
            String issues = result.validation().issues().isEmpty() ? null
                    : toIssuesJson(result.validation(), objectMapper);
            return new IngestResponse(result.qualifiedName(), result.status(), issues);
        }

        private static String toIssuesJson(ValidationResult validation, ObjectMapper objectMapper) {
            try {
                return objectMapper.writeValueAsString(validation.issues());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not serialize validation issues", e);
            }
        }
    }

    /** Wire DTO for the 409 response: what is in the way, and in which state. */
    public record ConflictResponse(String qualifiedName, RecordStatus recordStatus) {
    }

    /**
     * Wire DTO for the status view of a stored record. Absent fields mean
     * "not set on the record yet"; issues are only reported for INCOMPLETE —
     * the cleared sentinel is an internal storage detail.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordResponse(String qualifiedName, RecordStatus recordStatus, String name,
            String licenseId, String sourceUri, String validationIssues) {

        static RecordResponse of(SourcelumeDatasetDto dataset) {
            String issues = dataset.getValidationIssues();
            if (SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES.equals(issues)) {
                issues = null;
            }
            return new RecordResponse(dataset.getQualifiedName(), dataset.getRecordStatus(),
                    dataset.getName(), dataset.getLicenseId(), dataset.getSourceUri(), issues);
        }
    }

    /** Wire DTO for error responses. */
    public record ErrorResponse(String message) {
    }
}

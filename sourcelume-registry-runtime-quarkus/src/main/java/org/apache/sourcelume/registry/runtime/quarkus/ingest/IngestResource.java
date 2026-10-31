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
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.sourcelume.registry.common.dto.CustodyEventDto;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.common.dto.TrainingProvenanceRecordDto.TrainingDataEntryDto;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.common.mapper.RecordJson;
import org.apache.sourcelume.registry.common.mapper.TrainingProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.enums.SchemaType;
import org.eclipse.microprofile.openapi.annotations.headers.Header;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.Parameter;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Ingest endpoints. {@code POST /records} validates a submitted record
 * synchronously through the ingest pipeline and stores it with its
 * verdict — byte-identical, keyed by the record IRI — answering with
 * {@code 201} (new record) or {@code 200} (corrected resubmission of an
 * INCOMPLETE record) and the verdict in the body. {@code GET /records/{id}}
 * reports the stored state; {@code GET /records/{id}/document} returns the
 * stored bytes verbatim, for consumers that check a signature or the
 * record's digest over the document itself.
 *
 * <p>Every stored record carries its validation verdict: VALIDATED is
 * published (consumers filter on it), INCOMPLETE carries its issues and
 * stays curatable.
 *
 * <p>Status codes: {@code 201}/{@code 200} with a Location header; a
 * record that is already VALIDATED is rejected with {@code 409}; a
 * document without a usable {@code id} is {@code 400}; an unknown record
 * on GET is {@code 404}; a failed backend call is {@code 503} or
 * {@code 502} (see {@code BackendFailureMapper}).
 */
@Path("/records")
@Tag(name = "Records", description = "Submit provenance records and read their verdicts")
public class IngestResource {

    private static final Logger log = LoggerFactory.getLogger(IngestResource.class);

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
    @Operation(
            summary = "Submit a provenance record",
            description = "Validates the record synchronously and stores it byte-identical with its verdict:"
                    + " VALIDATED (published, no longer changeable) or INCOMPLETE (carries its issues,"
                    + " corrected by resubmitting).")
    @RequestBody(
            required = true,
            description = "A dataset or model provenance record as JSON-LD. Records with a nonblank id that fail"
                    + " spec validation are also accepted and stored as INCOMPLETE with their validation issues.",
            content = {
                @Content(
                        mediaType = "application/ld+json",
                        schema = @Schema(ref = "#/components/schemas/RecordSubmission"),
                        examples = {
                            @ExampleObject(name = "dataset", ref = "#/components/examples/DatasetRecord"),
                            @ExampleObject(name = "model", ref = "#/components/examples/TrainingRecord"),
                            @ExampleObject(name = "incomplete", ref = "#/components/examples/IncompleteRecord")
                        }),
                @Content(
                        mediaType = MediaType.APPLICATION_JSON,
                        schema = @Schema(ref = "#/components/schemas/RecordSubmission"),
                        examples = {
                            @ExampleObject(name = "dataset", ref = "#/components/examples/DatasetRecord"),
                            @ExampleObject(name = "model", ref = "#/components/examples/TrainingRecord"),
                            @ExampleObject(name = "incomplete", ref = "#/components/examples/IncompleteRecord")
                        })
            })
    @APIResponse(
            responseCode = "201",
            description = "New record stored with its verdict",
            headers =
                    @Header(
                            name = "Location",
                            description = "The record's status view",
                            schema = @Schema(type = SchemaType.STRING)),
            content = @Content(schema = @Schema(implementation = IngestResponse.class)))
    @APIResponse(
            responseCode = "200",
            description = "Existing record resubmitted and stored with its new verdict",
            headers =
                    @Header(
                            name = "Location",
                            description = "The record's status view",
                            schema = @Schema(type = SchemaType.STRING)),
            content = @Content(schema = @Schema(implementation = IngestResponse.class)))
    @APIResponse(
            responseCode = "400",
            description = "Unparseable document or no usable id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "409",
            description = "The record is already VALIDATED, or the id is taken under a different record type",
            content = @Content(schema = @Schema(implementation = ConflictResponse.class)))
    @APIResponse(responseCode = "415", description = "Unsupported content type")
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response submit(byte[] received) {
        String rawJsonLd = new String(received, StandardCharsets.UTF_8);
        String recordId;
        try {
            recordId = RecordJson.extractRecordId(rawJsonLd);
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }

        // The record's type term selects its store: ProvenanceRecord →
        // sourcelume_dataset, TrainingProvenanceRecord → sourcelume_model.
        // A document without a known type still lands on the dataset
        // entity — the chain will mark it INCOMPLETE and it stays curatable.
        String recordTypeTerm = RecordJson.extractRecordType(rawJsonLd).orElse(null);
        String entityType = RecordType.entityTypeFor(recordTypeTerm);

        // An id names one record of one type for its whole life, including
        // while INCOMPLETE. Only an unpublished record in the same store
        // can be corrected; conflicts must leave the stored record intact.
        List<RecordEntityDto> stored =
                atlasAdapter.getRecordEntitiesByQualifiedName(RecordType.entityTypeNames(), recordId);
        RecordEntityDto existing = null;
        for (RecordEntityDto entity : stored) {
            if (entity.getRecordStatus() == RecordStatus.VALIDATED || !entityType.equals(entity.getTypeName())) {
                return conflict(recordId, entity);
            }
            existing = entity;
        }

        // Inline validation: the same IngestService a queue consumer
        // would call the day a slow validation step makes a real queue
        // worth its price. The record reaches the store only with its
        // verdict.
        RecordEntityDto submitted = RecordType.fromTypeName(recordTypeTerm)
                .map(RecordType::newEntity)
                .orElseGet(SourcelumeDatasetDto::new);
        submitted.setQualifiedName(recordId);
        submitted.setRawJsonLd(rawJsonLd);
        // The digest covers the received bytes, not the decoded string —
        // "byte for byte" for real, e.g. for later signature checks.
        submitted.setSha256(sha256Hex(received));
        // Atlas requires a name on every entity write; a conforming
        // record's promotion overwrites the placeholder with the mapped
        // name, an INCOMPLETE verdict keeps it.
        submitted.setName(placeholderName(recordId));
        if (existing != null) {
            submitted.setGuid(existing.getGuid());
        }
        IngestResult result = ingestService.process(submitted);

        return Response.status(stored.isEmpty() ? 201 : 200)
                .location(locationOf(recordId))
                .entity(IngestResponse.of(result, objectMapper))
                .build();
    }

    @GET
    @Path("/{id}")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Get a record's stored state and verdict",
            description = "For a VALIDATED TrainingProvenanceRecord, each trainingData entry also reports the"
                    + " current status of the referenced dataset record; for a VALIDATED ProvenanceRecord, each"
                    + " custodyChain event reports the current status of the record its agent names, if any."
                    + " Both are looked up at read time.")
    @APIResponse(
            responseCode = "200",
            description = "The stored record",
            content = @Content(schema = @Schema(implementation = RecordResponse.class)))
    @APIResponse(
            responseCode = "404",
            description = "No record for this id",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response status(
            @Parameter(description = "The record's id (IRI); encode it when calling the API directly") @PathParam("id")
                    String id) {
        // The id's owning entity type is discovered, not stored — check
        // each record type's store until one answers.
        List<RecordEntityDto> found = atlasAdapter.getRecordEntitiesByQualifiedName(RecordType.entityTypeNames(), id);
        RecordEntityDto stored = found.isEmpty() ? null : found.get(0);
        if (stored == null) {
            return Response.status(404)
                    .entity(new ErrorResponse("No record for id: " + id))
                    .build();
        }
        List<TrainingDataResponse> trainingData = null;
        List<CustodyEventResponse> custodyChain = null;
        if (stored.getRecordStatus() == RecordStatus.VALIDATED && stored.getRawJsonLd() != null) {
            if (stored instanceof SourcelumeModelDto) {
                trainingData = resolveTrainingData(stored.getRawJsonLd());
            } else if (stored instanceof SourcelumeDatasetDto) {
                custodyChain = resolveCustodyChain(stored.getRawJsonLd());
            }
        }
        return Response.ok(RecordResponse.of(stored, trainingData, custodyChain))
                .build();
    }

    @GET
    @Path("/{id}/document")
    @Produces({"application/ld+json", MediaType.APPLICATION_JSON})
    @Operation(
            summary = "Get the record's stored document",
            description = "Returns the JSON-LD document byte-identical to the received request body — the same"
                    + " bytes the record's sha256 digest (reported as ETag and on the status view) covers."
                    + " Nothing is mapped, resolved or reserialized, so a signature or digest over the"
                    + " submitted bytes can be checked against this answer. The document is returned for"
                    + " records of any verdict, VALIDATED or INCOMPLETE.")
    @APIResponse(
            responseCode = "200",
            description = "The stored document, as posted",
            headers =
                    @Header(
                            name = "ETag",
                            description = "The record's sha256 digest — a strong validator for the returned bytes",
                            schema = @Schema(type = SchemaType.STRING)),
            content =
                    @Content(
                            mediaType = "application/ld+json",
                            schema = @Schema(ref = "#/components/schemas/RecordSubmission")))
    @APIResponse(
            responseCode = "404",
            description = "No record for this id, or the entity under it carries no stored document",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response document(
            @Parameter(description = "The record's id (IRI); encode it when calling the API directly") @PathParam("id")
                    String id) {
        List<RecordEntityDto> found = atlasAdapter.getRecordEntitiesByQualifiedName(RecordType.entityTypeNames(), id);
        RecordEntityDto stored = found.isEmpty() ? null : found.get(0);
        if (stored == null) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("No record for id: " + id))
                    .build();
        }
        // A plain entity under the id (no provenance submission) carries
        // no document at all — the endpoint has nothing to return.
        if (stored.getRawJsonLd() == null) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("The record carries no stored document for id: " + id))
                    .build();
        }
        Response.ResponseBuilder ok = Response.ok(stored.getRawJsonLd()).type("application/ld+json");
        if (stored.getSha256() != null) {
            ok.tag(stored.getSha256());
        }
        return ok.build();
    }

    /**
     * Resolves a model record's {@code trainingData} references at read
     * time, as the spec asks: each referenced dataset gets the state the
     * registry holds its record in now. Nothing about it is stored on
     * the model entity, so a dataset record published or corrected later
     * shows up on the next read. Null when the record lists no datasets
     * (an {@code undisclosed} claim).
     */
    private List<TrainingDataResponse> resolveTrainingData(String rawJsonLd) {
        List<TrainingDataEntryDto> entries =
                TrainingProvenanceRecordMapper.map(rawJsonLd).getTrainingData();
        if (entries == null) {
            return null;
        }
        // The spec makes trainingData entries dataset records.
        Map<String, RecordState> states = lookUpRecords(
                entries.stream().map(TrainingDataEntryDto::getDataset).toList(),
                List.of(SourcelumeDatasetDto.TYPE_NAME));
        List<TrainingDataResponse> resolved = new ArrayList<>();
        for (TrainingDataEntryDto entry : entries) {
            RecordState state = states.get(entry.getDataset());
            resolved.add(new TrainingDataResponse(
                    entry.getDataset(),
                    entry.getPhase(),
                    entry.getTokens(),
                    entry.getShare(),
                    state.recordStatus(),
                    state.lookupFailedFlag()));
        }
        return resolved;
    }

    /**
     * Resolves a dataset record's {@code custodyChain} agents at read
     * time: an agent may be the id of any Sourcelume record — a dataset,
     * or a model that rewrote the data — so each agent gets the state of
     * the one record its IRI denotes, whatever its type, absent for an
     * agent that is not a record, such as an organization's homepage.
     * Nothing is stored on the dataset entity.
     */
    private List<CustodyEventResponse> resolveCustodyChain(String rawJsonLd) {
        List<CustodyEventDto> events = ProvenanceRecordMapper.map(rawJsonLd).getCustodyChain();
        if (events == null) {
            return null;
        }
        Map<String, RecordState> states =
                lookUpRecords(events.stream().map(CustodyEventDto::getAgent).toList(), RecordType.entityTypeNames());
        List<CustodyEventResponse> resolved = new ArrayList<>();
        for (CustodyEventDto event : events) {
            RecordState state = states.get(event.getAgent());
            resolved.add(new CustodyEventResponse(
                    event.getAgent(),
                    event.getAction(),
                    event.getStartTime(),
                    state.recordStatus(),
                    state.lookupFailedFlag()));
        }
        return resolved;
    }

    /**
     * Looks up the record each IRI denotes among the given entity types,
     * as it stands now. An IRI listed more than once is looked up once.
     *
     * <p>One lookup per distinct IRI and entity type, made one after
     * another: the read costs grow with the number of IRIs. A failed
     * lookup marks its IRI instead of failing the read, and the IRIs
     * after it are marked without asking the backend: a backend that
     * just timed out would make every further lookup wait out its own
     * timeout.
     */
    private Map<String, RecordState> lookUpRecords(List<String> iris, List<String> entityTypes) {
        Map<String, RecordState> states = new HashMap<>();
        boolean backendFailed = false;
        for (String iri : iris) {
            if (!states.containsKey(iri)) {
                RecordState state = backendFailed ? RecordState.FAILED : lookUpRecord(iri, entityTypes);
                backendFailed |= state.lookupFailed();
                states.put(iri, state);
            }
        }
        return states;
    }

    private RecordState lookUpRecord(String iri, List<String> entityTypes) {
        try {
            // One IRI denotes one record, so at most one store holds it.
            List<RecordEntityDto> found = atlasAdapter.getRecordEntitiesByQualifiedName(entityTypes, iri);
            return new RecordState(found.isEmpty() ? null : found.get(0).getRecordStatus(), false);
        } catch (AtlasAdapterException e) {
            log.warn("Could not look up the record for '{}': {}", iri, e.getMessage());
            return RecordState.FAILED;
        }
    }

    private record RecordState(RecordStatus recordStatus, boolean lookupFailed) {
        static final RecordState FAILED = new RecordState(null, true);

        /** The wire form of {@link #lookupFailed}: true, or absent. */
        Boolean lookupFailedFlag() {
            return lookupFailed ? Boolean.TRUE : null;
        }
    }

    /**
     * Atlas' Asset supertype requires a name on every entity write: an
     * INCOMPLETE verdict keeps the placeholder derived from the record
     * id, a VALIDATED verdict overwrites it with the mapped name.
     */
    private static String placeholderName(String recordId) {
        int lastSegment = recordId.lastIndexOf('/');
        String candidate = lastSegment >= 0 ? recordId.substring(lastSegment + 1) : recordId;
        return candidate.isBlank() ? recordId : candidate;
    }

    /** Hex SHA-256 over the received bytes. */
    private static String sha256Hex(byte[] received) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(received);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
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
    public record IngestResponse(
            String qualifiedName,
            String type,
            RecordStatus recordStatus,
            String validationIssues,
            String sha256,
            String validatedBy,
            String validatedAt) {

        static IngestResponse of(IngestResult result, ObjectMapper objectMapper) {
            String issues =
                    result.validation().issues().isEmpty() ? null : toIssuesJson(result.validation(), objectMapper);
            return new IngestResponse(
                    result.qualifiedName(),
                    result.recordType(),
                    result.status(),
                    issues,
                    result.sha256(),
                    result.validatedBy(),
                    result.validatedAt());
        }

        private static String toIssuesJson(ValidationResult validation, ObjectMapper objectMapper) {
            try {
                return objectMapper.writeValueAsString(validation.issues());
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Could not serialize validation issues", e);
            }
        }
    }

    /** The 409 for an id that an existing entity keeps this submission from taking. */
    private static Response conflict(String recordId, RecordEntityDto blocking) {
        return Response.status(409)
                .entity(new ConflictResponse(
                        recordId,
                        blocking.getRecordStatus(),
                        blocking.getTypeName(),
                        conflictReason(blocking.getRecordStatus())))
                .build();
    }

    private static String conflictReason(RecordStatus status) {
        if (status == RecordStatus.VALIDATED) {
            return "The id belongs to a VALIDATED record, which never changes; publish a changed record under a new id";
        }
        if (status == RecordStatus.INCOMPLETE) {
            return "The id belongs to an INCOMPLETE record of another type; "
                    + "a record's type never changes, so submit the other record under a new id";
        }
        return "The id is taken by a plain dataset entity that is not a provenance record; "
                + "POST /records cannot replace it under another record type";
    }

    /**
     * Wire DTO for the 409 response: what is in the way — its state (absent
     * for a plain dataset that carries no record status) and the store
     * (entity type) it lives in — and why that blocks the submission.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConflictResponse(String qualifiedName, RecordStatus recordStatus, String entityType, String reason) {}

    /**
     * Wire DTO for the status view of a stored record. Absent fields mean
     * "not set on the record yet"; issues are only reported for INCOMPLETE —
     * the cleared sentinel is an internal storage detail.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record RecordResponse(
            String qualifiedName,
            String type,
            RecordStatus recordStatus,
            String name,
            String licenseId,
            String sourceUri,
            String modelUri,
            String modelVersion,
            String disclosure,
            List<TrainingDataResponse> trainingData,
            List<CustodyEventResponse> custodyChain,
            String validationIssues,
            String sha256,
            String validatedBy,
            String validatedAt) {

        static RecordResponse of(
                RecordEntityDto record,
                List<TrainingDataResponse> trainingData,
                List<CustodyEventResponse> custodyChain) {
            String issues = record.getValidationIssues();
            if (RecordEntityDto.CLEARED_VALIDATION_ISSUES.equals(issues)) {
                issues = null;
            }
            String recordType = RecordType.fromEntityTypeName(record.getTypeName())
                    .map(RecordType::typeName)
                    .orElse(record.getTypeName());
            String licenseId = null;
            String sourceUri = null;
            String modelUri = null;
            String modelVersion = null;
            String disclosure = null;
            if (record instanceof SourcelumeDatasetDto dataset) {
                licenseId = dataset.getLicenseId();
                sourceUri = dataset.getSourceUri();
            } else if (record instanceof SourcelumeModelDto model) {
                modelUri = model.getModelUri();
                modelVersion = model.getModelVersion();
                disclosure = model.getDisclosure();
            }
            return new RecordResponse(
                    record.getQualifiedName(),
                    recordType,
                    record.getRecordStatus(),
                    record.getName(),
                    licenseId,
                    sourceUri,
                    modelUri,
                    modelVersion,
                    disclosure,
                    trainingData,
                    custodyChain,
                    issues,
                    record.getSha256(),
                    record.getValidatedBy(),
                    record.getValidatedAt());
        }
    }

    /**
     * Wire DTO for one {@code trainingData} entry of a model record: the
     * producer's claim as submitted, plus the referenced dataset record's
     * current state. {@code recordStatus} is absent when the registry
     * holds no provenance record for the IRI — the spec allows any
     * dataset IRI there, not only registered records — and
     * {@code lookupFailed} is true instead when the backend could not be
     * asked.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TrainingDataResponse(
            String dataset, String phase, Long tokens, Double share, RecordStatus recordStatus, Boolean lookupFailed) {}

    /**
     * Wire DTO for one {@code custodyChain} event of a dataset record: the
     * event as submitted, plus the state of the record its agent's IRI
     * denotes, of any record type. {@code recordStatus} is absent when the
     * agent is not a record the registry holds — an external identifier
     * such as an organization's homepage — and {@code lookupFailed} is true
     * instead when the backend could not be asked.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CustodyEventResponse(
            String agent, String action, String startTime, RecordStatus recordStatus, Boolean lookupFailed) {}

    /** Wire DTO for error responses. */
    public record ErrorResponse(String message) {}
}

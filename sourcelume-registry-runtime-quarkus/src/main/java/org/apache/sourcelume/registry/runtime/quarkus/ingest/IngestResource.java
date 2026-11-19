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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.apache.sourcelume.registry.common.dto.CustodyEventDto;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.common.dto.TrainingProvenanceRecordDto.TrainingDataEntryDto;
import org.apache.sourcelume.registry.common.mapper.ProvenanceRecordMapper;
import org.apache.sourcelume.registry.common.mapper.RecordJson;
import org.apache.sourcelume.registry.common.mapper.TrainingProvenanceRecordMapper;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.ingest.RecordSubmission;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.runtime.quarkus.RequestBodies;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.RecordStatus;
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
import org.jboss.resteasy.reactive.server.multipart.FormValue;
import org.jboss.resteasy.reactive.server.multipart.MultipartFormDataInput;
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
 * record's digest over the document itself. A multipart {@code POST
 * /records} stores files such as a detached signature next to the record;
 * {@code GET /records/{id}/attachments} lists them and
 * {@code GET /records/{id}/attachments/{name}} returns one verbatim.
 *
 * <p>Every stored record carries its validation verdict: VALIDATED is
 * published (consumers filter on it), INCOMPLETE carries its issues and
 * stays curatable.
 *
 * <p>Status codes: {@code 201}/{@code 200} with a Location header; a
 * record that is already VALIDATED is rejected with {@code 409}; a
 * document without a usable {@code id} or a body that is not valid
 * UTF-8 is {@code 400}; an unknown record on GET is {@code 404}; a
 * failed backend call is {@code 503} or {@code 502} (see
 * {@code StorageFailureMapper}).
 */
@Path("/records")
@Tag(name = "Records", description = "Submit provenance records and read their verdicts")
public class IngestResource {

    private static final Logger log = LoggerFactory.getLogger(IngestResource.class);

    private final RecordStore store;
    private final RecordSearch search;
    private final IngestService ingestService;
    private final ObjectMapper objectMapper;

    @Inject
    IngestResource(RecordStore store, RecordSearch search, IngestService ingestService, ObjectMapper objectMapper) {
        this.store = store;
        this.search = search;
        this.ingestService = ingestService;
        this.objectMapper = objectMapper;
    }

    /**
     * Lists stored records of one logical type and validation status, with
     * optional exact facet filters. Runs entirely on the storage plugin's
     * {@code RecordSearch}, so every backend answers it the same way.
     *
     * <p>Deliberately does not resolve {@code trainingData} or
     * {@code custodyChain} references: that would cost one backend lookup per
     * reference per hit. Use {@code GET /records/{id}} for a resolved view.
     */
    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Search stored records by type, status and facets",
            description = "Returns the records of one logical type in one validation status. Facet values of"
                    + " the same facet match any of them (OR), different facets must all match (AND)."
                    + " Filtering happens before the limit is applied. References are not resolved here;"
                    + " use GET /records/{id} for a record with its trainingData or custodyChain state.")
    @APIResponse(
            responseCode = "200",
            description = "The matching records, up to the requested limit",
            content = @Content(schema = @Schema(implementation = SearchResponse.class)))
    @APIResponse(
            responseCode = "400",
            description = "Missing or invalid type, status, limit or facet value",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response searchRecords(
            @Parameter(description = "The record's logical type", required = true) @QueryParam("type") String type,
            @Parameter(description = "The record's validation status", required = true) @QueryParam("status")
                    String status,
            @Parameter(description = "Maximum number of records to return (1-1000, default 100)") @QueryParam("limit")
                    String limit,
            @Parameter(description = "Exact license id; repeat to match any of several") @QueryParam("license")
                    List<String> licenses,
            @Parameter(description = "Exact trainingData IRI; repeat to match any of several")
                    @QueryParam("trainingDataset")
                    List<String> trainingDatasets,
            @Parameter(description = "Exact creator name; repeat to match any of several") @QueryParam("creatorName")
                    List<String> creatorNames,
            @Parameter(description = "Exact custodyChain agent IRI; repeat to match any of several")
                    @QueryParam("custodyAgent")
                    List<String> custodyAgents) {
        RecordFacets.Builder facets = RecordFacets.builder();
        try {
            addFacetValues(facets, RecordFacet.LICENSE, licenses);
            addFacetValues(facets, RecordFacet.TRAINING_DATASET, trainingDatasets);
            addFacetValues(facets, RecordFacet.CREATOR_NAME, creatorNames);
            addFacetValues(facets, RecordFacet.CUSTODY_AGENT, custodyAgents);
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        SearchQuery query;
        try {
            query = SearchQuery.builder()
                    .type(requireType(type))
                    .status(requireStatus(status))
                    .limit(parseLimit(limit))
                    .facets(facets.build())
                    .build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        List<RecordResponse> found = new ArrayList<>();
        for (StoredRecord stored : search.search(query)) {
            found.add(RecordResponse.of(stored.record(), null, null, objectMapper));
        }
        return Response.ok(new SearchResponse(found, query.limit())).build();
    }

    private static void addFacetValues(RecordFacets.Builder facets, RecordFacet facet, List<String> values) {
        if (values == null) return;
        for (String value : values) {
            facets.add(facet, value);
        }
    }

    /**
     * The type is required and must be one the registry knows. It is not
     * defaulted: a wrong default would silently hide records from callers
     * that forgot the parameter.
     */
    private static String requireType(String type) {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'type' is required; one of " + knownTypes());
        }
        for (RecordType known : RecordType.values()) {
            if (known.typeName().equals(type)) return type;
        }
        throw new IllegalArgumentException("Unknown record type '" + type + "'; one of " + knownTypes());
    }

    /**
     * The status is required for the same reason: defaulting to VALIDATED
     * would hide INCOMPLETE records from curators, defaulting to INCOMPLETE
     * would hide published records from consumers.
     */
    private static RecordStatus requireStatus(String status) {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'status' is required; one of VALIDATED, INCOMPLETE");
        }
        try {
            return RecordStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown status '" + status + "'; one of VALIDATED, INCOMPLETE");
        }
    }

    /**
     * Parsed here rather than bound by JAX-RS: an unconvertible query parameter
     * would answer {@code 404}, which is the wrong signal for a bad request.
     */
    private static int parseLimit(String limit) {
        if (limit == null || limit.isBlank()) return 100;
        try {
            return Integer.parseInt(limit.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Query parameter 'limit' must be an integer between 1 and 1000");
        }
    }

    private static String knownTypes() {
        List<String> names = new ArrayList<>();
        for (RecordType known : RecordType.values()) {
            names.add(known.typeName());
        }
        return String.join(", ", names);
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(message))
                .build();
    }

    @POST
    @Consumes({MediaType.APPLICATION_JSON, "application/ld+json"})
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Submit a provenance record",
            description = "Validates the record synchronously and stores it byte-identical with its verdict:"
                    + " VALIDATED (published, no longer changeable) or INCOMPLETE (carries its issues,"
                    + " corrected by resubmitting). As multipart/form-data, the record comes with attached"
                    + " files, such as a detached signature, stored next to it and bound to its exact bytes:"
                    + " a resubmission of an INCOMPLETE record replaces the record and its attachments"
                    + " together, and a VALIDATED record never changes, attachments included. The registry"
                    + " stores attachments as opaque bytes and does not verify them.")
    @RequestBody(
            required = true,
            description = "A dataset or model provenance record as JSON-LD. Records with a nonblank id that fail"
                    + " spec validation are also accepted and stored as INCOMPLETE with their validation issues.",
            content = {
                @Content(
                        mediaType = MediaType.MULTIPART_FORM_DATA,
                        schema = @Schema(implementation = RecordWithAttachments.class)),
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
            description = "Not valid UTF-8, an unparseable document, or no usable id; for multipart, also a"
                    + " missing or repeated record part, an unknown part, an attachment without a filename"
                    + " or Content-Type, an empty attachment, or a duplicate attachment name",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "409",
            description = "The record is already VALIDATED, or the id is taken under a different record type",
            content = @Content(schema = @Schema(implementation = ConflictResponse.class)))
    @APIResponse(
            responseCode = "413",
            description = "The document or the attachments exceed the storage limits",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "415",
            description = "Unsupported content type, or a multipart record part that is not"
                    + " application/ld+json or application/json")
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable or the write outcome is unknown",
            headers =
                    @Header(
                            name = "X-Sourcelume-Error",
                            description = "UNKNOWN_WRITE requires reconciliation before retrying",
                            schema = @Schema(type = SchemaType.STRING)),
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response submit(byte[] received) {
        return accept(received, List.of());
    }

    /**
     * The multipart form of {@code POST /records}. Hidden from OpenAPI: both methods
     * share one operation, documented on {@link #submit}, and scanning this
     * method's framework parameter type would add unused internal schemas.
     */
    @POST
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(hidden = true)
    public Response submitWithAttachments(MultipartFormDataInput input) {
        FormValue record = null;
        List<AttachmentPart> parts = new ArrayList<>();
        try {
            for (Map.Entry<String, java.util.Collection<FormValue>> entry :
                    input.getValues().entrySet()) {
                switch (entry.getKey()) {
                    case "record" -> {
                        if (entry.getValue().size() != 1) return badRequest("Send exactly one 'record' part");
                        record = entry.getValue().iterator().next();
                    }
                    case "attachment" -> {
                        for (FormValue value : entry.getValue()) parts.add(AttachmentPart.of(value));
                    }
                    // An unknown part is rejected rather than dropped, so a
                    // misspelled 'attachment' cannot lose a signature silently.
                    default -> {
                        return badRequest(
                                "Unknown form part '" + entry.getKey() + "'; expected 'record' and 'attachment'");
                    }
                }
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
        if (record == null) return badRequest("Missing 'record' part");
        // A plain form field is decoded to text by the framework; only a
        // file part keeps the exact bytes the record's digest must cover.
        if (!record.isFileItem()) return badRequest("Send the 'record' part as a file");
        String recordType = baseType(record.getHeaders().getFirst("Content-Type"));
        if (!"application/ld+json".equals(recordType) && !MediaType.APPLICATION_JSON.equals(recordType)) {
            return Response.status(415)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("The 'record' part must be application/ld+json or application/json"))
                    .build();
        }
        return accept(read(record), parts);
    }

    private Response accept(byte[] received, List<AttachmentPart> parts) {
        if (received != null && received.length > store.limits().maxDocumentBytes()) {
            return Response.status(413)
                    .entity(new ErrorResponse("Document exceeds the storage size limit"))
                    .build();
        }
        // Decode strictly: the lenient String constructor swaps invalid
        // input for U+FFFD, which would leave the stored document
        // disagreeing with the digest over the received bytes.
        String rawJsonLd = RequestBodies.utf8(received);
        if (rawJsonLd == null) {
            return Response.status(400)
                    .entity(new ErrorResponse("Request body is not valid UTF-8"))
                    .build();
        }
        String recordId;
        try {
            recordId = RecordJson.extractRecordId(rawJsonLd);
        } catch (IllegalArgumentException e) {
            return Response.status(400)
                    .entity(new ErrorResponse(e.getMessage()))
                    .build();
        }

        StoreLimits limits = store.limits();
        long attachmentBytes =
                parts.stream().mapToLong(part -> part.bytes().length).sum();
        if (parts.size() > limits.maxAttachments() || attachmentBytes > limits.maxAttachmentBytes()) {
            return Response.status(413)
                    .entity(new ErrorResponse("Attachments exceed the storage limits: at most "
                            + limits.maxAttachments() + " files, " + limits.maxAttachmentBytes()
                            + " bytes in total"))
                    .build();
        }
        RawDocument document = RawDocument.builder().bytes(received).build();
        List<RecordAttachment> attachments = new ArrayList<>();
        var names = new HashSet<String>();
        try {
            for (AttachmentPart part : parts) {
                if (!names.add(part.name()))
                    throw new IllegalArgumentException("Duplicate attachment name: " + part.name());
                attachments.add(RecordAttachment.builder()
                        .name(part.name())
                        .mediaType(part.mediaType())
                        .documentSha256(document.sha256())
                        .bytes(part.bytes())
                        .build());
            }
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }

        try {
            IngestResult result = ingestService.process(RecordSubmission.builder()
                    .document(document)
                    .attachments(attachments)
                    .build());
            return Response.status(result.disposition() == WriteResult.Disposition.CREATED ? 201 : 200)
                    .location(locationOf(recordId))
                    .entity(IngestResponse.of(result, objectMapper))
                    .build();
        } catch (RecordConflictException e) {
            if (e.current() == null)
                return Response.status(409)
                        .entity(new ErrorResponse("Record changed during submission"))
                        .build();
            return conflict(recordId, e.current().record(), e.reason());
        }
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
        RecordEnvelope stored = store.get(id).map(StoredRecord::record).orElse(null);
        if (stored == null) {
            return Response.status(404)
                    .entity(new ErrorResponse("No record for id: " + id))
                    .build();
        }
        if (stored.validation() == null
                || stored.validation().status() != RecordStatus.VALIDATED
                || stored.document() == null) {
            return Response.ok(RecordResponse.of(stored, null, null, objectMapper))
                    .build();
        }
        if (RecordType.TRAINING_PROVENANCE_RECORD.typeName().equals(stored.type())) {
            return Response.ok(RecordResponse.of(
                            stored, resolveTrainingData(stored.document().text()), null, objectMapper))
                    .build();
        }
        if (RecordType.PROVENANCE_RECORD.typeName().equals(stored.type())) {
            return Response.ok(RecordResponse.of(
                            stored, null, resolveCustodyChain(stored.document().text()), objectMapper))
                    .build();
        }
        return Response.ok(RecordResponse.of(stored, null, null, objectMapper)).build();
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
        RecordEnvelope stored = store.get(id).map(StoredRecord::record).orElse(null);
        if (stored == null) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("No record for id: " + id))
                    .build();
        }
        // A plain entity under the id (no provenance submission) carries
        // no document at all — the endpoint has nothing to return.
        if (stored.document() == null) {
            return Response.status(404)
                    .type(MediaType.APPLICATION_JSON)
                    .entity(new ErrorResponse("The record carries no stored document for id: " + id))
                    .build();
        }
        Response.ResponseBuilder ok = Response.ok(stored.document().text()).type("application/ld+json");
        if (stored.document().sha256() != null) {
            ok.tag(stored.document().sha256());
        }
        return ok.build();
    }

    @GET
    @Path("/{id}/attachments")
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "List the files attached to a record",
            description = "Names, media types and sizes of the files stored with the record, such as a"
                    + " detached signature. Every attachment is bound to the document digest reported here.")
    @APIResponse(
            responseCode = "200",
            description = "The record's attachments; an empty list when it has none",
            content = @Content(schema = @Schema(implementation = AttachmentsResponse.class)))
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
    public Response attachments(
            @Parameter(description = "The record's id (IRI); encode it when calling the API directly") @PathParam("id")
                    String id) {
        RecordEnvelope stored = store.get(id).map(StoredRecord::record).orElse(null);
        if (stored == null) return notFound("No record for id: " + id);
        List<AttachmentInfo> files = new ArrayList<>();
        for (RecordAttachment attachment : stored.attachments()) {
            files.add(new AttachmentInfo(attachment.name(), attachment.mediaType(), attachment.size()));
        }
        String sha256 = stored.document() == null ? null : stored.document().sha256();
        return Response.ok(new AttachmentsResponse(stored.id(), sha256, files)).build();
    }

    @GET
    @Path("/{id}/attachments/{name}")
    @Operation(
            summary = "Get one file attached to a record",
            description = "Returns the attachment byte-identical to the uploaded part, with the media type it"
                    + " was uploaded with, as a download (Content-Disposition: attachment, nosniff,"
                    + " Content-Security-Policy: sandbox). Verify it against GET /records/{id}/document,"
                    + " whose bytes it is bound to.")
    @APIResponse(responseCode = "200", description = "The attachment, as uploaded")
    @APIResponse(
            responseCode = "404",
            description = "No record for this id, or no attachment of this name",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "502",
            description = "The backend answered with an error or with data the registry could not read",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @APIResponse(
            responseCode = "503",
            description = "The backend is unavailable; retry later",
            content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    public Response attachment(
            @Parameter(description = "The record's id (IRI); encode it when calling the API directly") @PathParam("id")
                    String id,
            @Parameter(description = "The attachment's name") @PathParam("name") String name) {
        RecordEnvelope stored = store.get(id).map(StoredRecord::record).orElse(null);
        if (stored == null) return notFound("No record for id: " + id);
        for (RecordAttachment attachment : stored.attachments()) {
            if (attachment.name().equals(name)) {
                // Served for download only: the uploader picks the media type,
                // so a text/html attachment must not render on the registry's
                // origin. A verifier reads the bytes and is unaffected.
                return Response.ok(attachment.bytes())
                        .type(attachment.mediaType())
                        .header("Content-Disposition", "attachment; filename*=UTF-8''" + rfc5987(name))
                        .header("X-Content-Type-Options", "nosniff")
                        .header("Content-Security-Policy", "sandbox")
                        .build();
            }
        }
        return notFound("No attachment '" + name + "' on record: " + id);
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
                List.of(RecordType.PROVENANCE_RECORD.typeName()));
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
        Map<String, RecordState> states = lookUpRecords(
                events.stream().map(CustodyEventDto::getAgent).toList(),
                java.util.Arrays.stream(RecordType.values())
                        .map(RecordType::typeName)
                        .toList());
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

    /** Resolves distinct references through authoritative batch reads; a failure marks the whole read. */
    private Map<String, RecordState> lookUpRecords(List<String> iris, List<String> entityTypes) {
        var distinct = new java.util.LinkedHashSet<>(iris);
        Map<String, RecordState> states = new HashMap<>();
        Map<String, StoredRecord> found;
        try {
            found = store.getAll(distinct);
        } catch (StorageException e) {
            log.warn("Could not look up {} record reference(s): {}", distinct.size(), e.getMessage());
            for (String iri : distinct) states.put(iri, RecordState.FAILED);
            return states;
        }
        for (String iri : distinct) {
            StoredRecord stored = found.get(iri);
            if (stored == null
                    || !entityTypes.contains(stored.record().type())
                    || stored.record().validation() == null) {
                states.put(iri, new RecordState(null, false));
                continue;
            }
            states.put(iri, new RecordState(stored.record().validation().status(), false));
        }
        return states;
    }

    private record RecordState(RecordStatus recordStatus, boolean lookupFailed) {
        static final RecordState FAILED = new RecordState(null, true);

        /** The wire form of {@link #lookupFailed}: true, or absent. */
        Boolean lookupFailedFlag() {
            return lookupFailed ? Boolean.TRUE : null;
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
    private static Response conflict(String recordId, RecordEnvelope blocking, RecordConflictException.Reason reason) {
        RecordStatus status =
                blocking.validation() == null ? null : blocking.validation().status();
        String message = conflictReason(status);
        if (reason == RecordConflictException.Reason.STALE_REVISION
                || reason == RecordConflictException.Reason.ALREADY_EXISTS) {
            message = "Record changed during submission; read it before retrying";
        }
        return Response.status(409)
                .entity(new ConflictResponse(recordId, status, RecordType.entityTypeFor(blocking.type()), message))
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
                RecordEnvelope record,
                List<TrainingDataResponse> trainingData,
                List<CustodyEventResponse> custodyChain,
                ObjectMapper objectMapper) {
            ValidationMetadata validation = record.validation();
            String issues = null;
            if (validation != null && !validation.issues().isEmpty()) {
                try {
                    issues = objectMapper.writeValueAsString(validation.issues());
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            RecordAttributes attributes = record.attributes();
            return new RecordResponse(
                    record.id(),
                    record.type(),
                    validation == null ? null : validation.status(),
                    attributes.name(),
                    attributes.licenseId(),
                    attributes.sourceUri(),
                    attributes.modelUri(),
                    attributes.modelVersion(),
                    attributes.disclosure(),
                    trainingData,
                    custodyChain,
                    issues,
                    record.document() == null ? null : record.document().sha256(),
                    validation == null ? null : validation.validatedBy(),
                    validation == null ? null : validation.validatedAt());
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

    /**
     * Wire DTO for a search answer: the matching records and the limit that
     * was applied, so a caller can tell a full page from a truncated one.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record SearchResponse(List<RecordResponse> records, int limit) {}

    private static Response notFound(String message) {
        return Response.status(404)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(message))
                .build();
    }

    /** Percent-encodes a filename for {@code filename*} (RFC 5987): unreserved characters stay. */
    private static String rfc5987(String name) {
        StringBuilder encoded = new StringBuilder();
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            char c = (char) (b & 0xFF);
            if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || "-._~".indexOf(c) >= 0) {
                encoded.append(c);
            } else {
                encoded.append('%').append(String.format("%02X", b & 0xFF));
            }
        }
        return encoded.toString();
    }

    /** The media type without parameters, lower-cased; null when absent. */
    private static String baseType(String contentType) {
        if (contentType == null) return null;
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .trim()
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static byte[] read(FormValue value) {
        try (InputStream in = value.getFileItem().getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** One uploaded attachment: its filename becomes its name, its part Content-Type its media type. */
    private record AttachmentPart(String name, String mediaType, byte[] bytes) {
        static AttachmentPart of(FormValue value) {
            String name = value.getFileName();
            if (!value.isFileItem() || name == null || name.isBlank())
                throw new IllegalArgumentException(
                        "Send each 'attachment' part as a file; its filename becomes the attachment's name");
            String mediaType = value.getHeaders().getFirst("Content-Type");
            if (mediaType == null || mediaType.isBlank())
                throw new IllegalArgumentException("Attachment '" + name + "' has no Content-Type");
            // Rejected here rather than on download, where an unparseable
            // stored type could no longer be sent back.
            MediaType.valueOf(mediaType);
            byte[] bytes = read(value);
            if (bytes.length == 0) throw new IllegalArgumentException("Attachment '" + name + "' is empty");
            return new AttachmentPart(name, mediaType, bytes);
        }
    }

    /** OpenAPI shape of the multipart submission. */
    @Schema(name = "RecordWithAttachments")
    public static final class RecordWithAttachments {
        @Schema(
                required = true,
                type = SchemaType.STRING,
                format = "binary",
                description = "The record as a file part, Content-Type application/ld+json or application/json")
        public String record;

        @Schema(
                type = SchemaType.ARRAY,
                implementation = BinaryPart.class,
                description = "Zero or more file parts. The filename becomes the attachment's name, the part's"
                        + " Content-Type its media type.")
        public List<String> attachment;
    }

    /** OpenAPI shape of one binary file part. */
    @Schema(name = "BinaryPart", type = SchemaType.STRING, format = "binary")
    public static final class BinaryPart {}

    /** Wire DTO listing a record's attachments and the document digest they are bound to. */
    public record AttachmentsResponse(String qualifiedName, String sha256, List<AttachmentInfo> attachments) {}

    /** Wire DTO for one attachment's metadata. */
    public record AttachmentInfo(String name, String mediaType, int size) {}

    /** Wire DTO for error responses. */
    public record ErrorResponse(String message) {}
}

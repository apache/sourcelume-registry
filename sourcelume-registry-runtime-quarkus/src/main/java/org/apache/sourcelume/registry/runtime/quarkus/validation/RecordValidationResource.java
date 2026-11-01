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
package org.apache.sourcelume.registry.runtime.quarkus.validation;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.apache.sourcelume.registry.runtime.quarkus.RequestBodies;
import org.apache.sourcelume.registry.runtime.quarkus.ingest.IngestResource;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.media.Content;
import org.eclipse.microprofile.openapi.annotations.media.ExampleObject;
import org.eclipse.microprofile.openapi.annotations.media.Schema;
import org.eclipse.microprofile.openapi.annotations.parameters.RequestBody;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Pre-flight validation endpoint: {@code POST /records/validate} runs the
 * full validator chain (JSON Schema stage, then SHACL) over a submitted
 * record and reports the outcome — without touching the backend.
 *
 * <p>Producers use this to check a record before submitting it; the
 * registry enforces the same chain during ingest. The response is a wire
 * DTO mirroring the core {@link ValidationResult} ({@code conforms} plus
 * {@code issues} found) — deliberately not the core type itself, so the
 * HTTP contract stays stable while core evolves (and so core stays free of
 * serialization concerns). The chain is fail-fast, so a non-conforming
 * response carries the issues of the first failing validator only.
 *
 * <p>Status codes: {@code 200} for a conforming document, {@code 422} for
 * a well-formed document that fails validation. Malformed JSON (a parse
 * failure inside the chain) is also reported as a 422 validation issue —
 * callers get one consistent error contract per validator path. A body
 * that is not valid UTF-8 is {@code 400}, decoded strictly like {@code
 * POST /records} so a record cannot pass the check and then fail on
 * publish.
 */
@Path("/records/validate")
@Tag(name = "Records")
public class RecordValidationResource {

    private final ValidatorChain validatorChain;

    @Inject
    RecordValidationResource(ValidatorChain validatorChain) {
        this.validatorChain = validatorChain;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Validate a provenance record without storing it",
            description = "Runs the validator chain (JSON Schema, then SHACL), stopping at the first failing stage.")
    @RequestBody(
            required = true,
            description = "A dataset or model provenance record as JSON-LD. Non-conforming records, including"
                    + " records without an id, return 422 with validation issues.",
            content =
                    @Content(
                            schema = @Schema(ref = "#/components/schemas/RecordValidationRequest"),
                            examples = {
                                @ExampleObject(name = "dataset", ref = "#/components/examples/DatasetRecord"),
                                @ExampleObject(name = "model", ref = "#/components/examples/TrainingRecord"),
                                @ExampleObject(name = "incomplete", ref = "#/components/examples/IncompleteRecord"),
                                @ExampleObject(name = "missingId", ref = "#/components/examples/MissingRecordId")
                            }))
    @APIResponse(
            responseCode = "200",
            description = "The record conforms",
            content = @Content(schema = @Schema(implementation = ValidationResponse.class)))
    @APIResponse(
            responseCode = "422",
            description = "The record does not conform, or is not valid JSON",
            content = @Content(schema = @Schema(implementation = ValidationResponse.class)))
    @APIResponse(
            responseCode = "400",
            description = "The request body is not valid UTF-8",
            content = @Content(schema = @Schema(implementation = IngestResource.ErrorResponse.class)))
    public Response validate(byte[] body) {
        String jsonLd = RequestBodies.utf8(body);
        if (jsonLd == null) {
            return Response.status(400)
                    .entity(new IngestResource.ErrorResponse("Request body is not valid UTF-8"))
                    .build();
        }
        ValidationResult result = validatorChain.validate(jsonLd);
        // 422 Unprocessable Entity: well-formed request, semantic rejection —
        // the int literal is used because the resolved jakarta.ws.rs API
        // does not carry the UNPROCESSABLE_ENTITY enum constant
        int status = result.conforms() ? 200 : 422;
        return Response.status(status).entity(ValidationResponse.of(result)).build();
    }

    /** Wire DTO for the validation outcome; keeps the HTTP contract decoupled from the core record. */
    public record ValidationResponse(boolean conforms, List<Issue> issues) {

        static ValidationResponse of(ValidationResult result) {
            List<Issue> issues = result.issues().stream()
                    .map(i -> new Issue(i.validatorId(), i.severity().name(), i.path(), i.message()))
                    .toList();
            return new ValidationResponse(result.conforms(), issues);
        }

        /** One reported issue; severity is the enum name (VIOLATION/WARNING/INFO). */
        public record Issue(String validatorId, String severity, String path, String message) {}
    }
}

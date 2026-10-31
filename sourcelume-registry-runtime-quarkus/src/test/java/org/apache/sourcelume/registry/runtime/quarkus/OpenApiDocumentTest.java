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
package org.apache.sourcelume.registry.runtime.quarkus;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.dialect.Dialects;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.junit.jupiter.api.Test;

/**
 * The generated OpenAPI document is the API contract: it must list every
 * endpoint with the status codes the resources actually return, and match
 * the committed snapshot so API changes show up as diffs in review.
 */
@QuarkusTest
class OpenApiDocumentTest {

    /** The committed contract, relative to this module. */
    private static final Path SNAPSHOT = Path.of("../docs/openapi.yaml");

    @Inject
    ObjectMapper objectMapper;

    @Test
    void shouldPublishTheValidatorSchemasWithoutLosingConstraints() throws IOException {
        JsonNode schemas = openApiDocument().path("components").path("schemas");
        for (RecordType type : RecordType.values()) {
            JsonNode spec = objectMapper.readTree(SpecResourceLoader.loadResourceString(type.schemaResource()));
            assertEquals(spec, schemas.path(type.typeName()), "OpenAPI must preserve the spec schema for " + type);
        }
    }

    @Test
    void shouldDescribeSubmissionAndPreflightSeparatelyWithSharedExamples() throws IOException {
        JsonNode document = openApiDocument();
        JsonNode schemas = document.path("components").path("schemas");
        assertEquals(
                objectMapper.readTree(
                        """
                        [
                          {"$ref":"#/components/schemas/ProvenanceRecord"},
                          {"$ref":"#/components/schemas/TrainingProvenanceRecord"},
                          {"$ref":"#/components/schemas/IncompleteRecordSubmission"}
                        ]
                        """),
                schemas.path("RecordSubmission").path("anyOf"));
        assertEquals(
                objectMapper.readTree("[\"id\"]"),
                schemas.path("IncompleteRecordSubmission").path("required"));

        for (String path : List.of("/records", "/records/validate")) {
            boolean submission = path.equals("/records");
            JsonNode content = document.path("paths")
                    .path(path)
                    .path("post")
                    .path("requestBody")
                    .path("content");
            for (JsonNode media : content) {
                assertEquals(
                        "#/components/schemas/" + (submission ? "RecordSubmission" : "RecordValidationRequest"),
                        media.path("schema").path("$ref").asText());
                assertEquals(submission ? 3 : 4, media.path("examples").size());
                for (JsonNode example : media.path("examples")) {
                    assertFalse(example.has("value"), "Examples must reference shared components");
                    exampleValue(document, example);
                }
            }
        }
    }

    @Test
    void documentedExamplesShouldValidateAndIngestAsDescribed() throws IOException {
        JsonNode document = openApiDocument();
        JsonNode examples = document.path("paths")
                .path("/records")
                .path("post")
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("examples");
        for (String name : List.of("dataset", "model", "incomplete")) {
            JsonNode record = exampleValue(document, examples.path(name));
            boolean conforms = !"incomplete".equals(name);
            given().contentType(ContentType.JSON)
                    .body(record.toString())
                    .when()
                    .post("/records/validate")
                    .then()
                    .statusCode(conforms ? 200 : 422)
                    .body("conforms", equalTo(conforms));
            given().contentType(ContentType.JSON)
                    .body(record.toString())
                    .when()
                    .post("/records")
                    .then()
                    .statusCode(201)
                    .body("recordStatus", equalTo(conforms ? "VALIDATED" : "INCOMPLETE"));
        }
    }

    @Test
    void preflightShouldAcceptJsonWithoutAUsableIdWhileSubmissionRequiresOne() throws IOException {
        JsonNode document = openApiDocument();
        Schema preflight = requestSchema(document, "/records/validate");
        Schema submission = requestSchema(document, "/records");
        JsonNode missingId = exampleValue(
                document,
                document.path("paths")
                        .path("/records/validate")
                        .path("post")
                        .path("requestBody")
                        .path("content")
                        .path("application/json")
                        .path("examples")
                        .path("missingId"));
        for (String json : List.of(missingId.toString(), "{}", "{\"id\":\" \"}", "[]", "null", "42", "\"text\"")) {
            JsonNode body = objectMapper.readTree(json);
            assertTrue(preflight.validate(body).isEmpty(), "Preflight input schema must allow: " + json);
            assertFalse(submission.validate(body).isEmpty(), "Submission schema must require a usable id: " + json);
            given().contentType(ContentType.JSON)
                    .body(json)
                    .when()
                    .post("/records/validate")
                    .then()
                    .statusCode(422)
                    .body("conforms", equalTo(false));
            given().contentType(ContentType.JSON)
                    .body(json)
                    .when()
                    .post("/records")
                    .then()
                    .statusCode(400);
        }
    }

    private Schema requestSchema(JsonNode document, String path) {
        String ref = document.path("paths")
                .path(path)
                .path("post")
                .path("requestBody")
                .path("content")
                .path("application/json")
                .path("schema")
                .path("$ref")
                .asText();
        var schema = objectMapper.createObjectNode().put("$ref", ref);
        schema.set("components", document.path("components"));
        var dialect = Dialects.getDraft202012();
        return new SchemaContext(dialect, SchemaRegistry.withDialect(dialect))
                .newSchema(SchemaLocation.DOCUMENT, schema, null);
    }

    private JsonNode exampleValue(JsonNode document, JsonNode example) {
        String ref = example.path("$ref").asText();
        assertTrue(ref.startsWith("#/components/examples/"), "Expected a shared example reference: " + example);
        JsonNode resolved = document.at(ref.substring(1));
        assertTrue(resolved.has("value"), "Unresolved example: " + ref);
        return resolved.path("value");
    }

    @Test
    void shouldDescribeDatasetsAsReadOnlyViews() throws IOException {
        JsonNode document = openApiDocument();
        for (String path :
                List.of("/api/v1/datasets", "/api/v1/datasets/guid/{guid}", "/api/v1/datasets/{qualifiedName}")) {
            JsonNode operations = document.path("paths").path(path);
            assertTrue(operations.has("get"), "GET missing on " + path);
            for (String method : List.of("post", "put", "patch", "delete")) {
                assertFalse(operations.has(method), method + " must not be offered on " + path);
            }
        }
        assertFalse(
                document.path("components").path("schemas").has("DatasetRequest"),
                "the dataset write contract is gone");
        // The response DTO still describes stored lifecycle fields.
        assertTrue(document.path("components")
                .path("schemas")
                .path("SourcelumeDatasetDto")
                .path("properties")
                .has("recordStatus"));
    }

    @Test
    void shouldGiveEveryResponseHeaderASchema() throws IOException {
        // OpenAPI 3.x requires a header object to carry a schema (or content);
        // a header with only a description makes the whole document invalid.
        List<String> withoutSchema = new java.util.ArrayList<>();
        openApiDocument().path("paths").properties().forEach(path -> path.getValue()
                .properties()
                .forEach(operation -> operation
                        .getValue()
                        .path("responses")
                        .properties()
                        .forEach(response -> response.getValue()
                                .path("headers")
                                .properties()
                                .forEach(header -> {
                                    if (!header.getValue().has("schema")
                                            && !header.getValue().has("content")) {
                                        withoutSchema.add(path.getKey() + " " + operation.getKey() + " "
                                                + response.getKey() + " " + header.getKey());
                                    }
                                }))));
        assertTrue(withoutSchema.isEmpty(), "response headers without a schema: " + withoutSchema);
    }

    private JsonNode openApiDocument() throws IOException {
        return objectMapper.readTree(given().queryParam("format", "json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .extract()
                .asString());
    }

    @Test
    void shouldDocumentRecordEndpoints() {
        given().queryParam("format", "json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Apache Sourcelume Registry API"))
                .body(
                        "paths.'/records'.post.responses.keySet()",
                        hasItems("200", "201", "400", "409", "415", "502", "503"))
                .body("paths.'/records'.post.requestBody.content", hasKey("application/ld+json"))
                .body("paths.'/records/{id}'.get.responses.keySet()", hasItems("200", "404", "502", "503"))
                .body("paths.'/records/{id}/document'.get.responses.keySet()", hasItems("200", "404", "502", "503"))
                .body("paths.'/records/{id}/document'.get.responses.'200'.content", hasKey("application/ld+json"))
                .body("paths.'/records/validate'.post.responses.keySet()", containsInAnyOrder("200", "422"));
    }

    @Test
    void shouldDocumentDatasetAndTypeEndpoints() {
        given().queryParam("format", "json")
                .when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .body("paths.'/api/v1/datasets'.get.responses.keySet()", hasItems("200", "400", "404", "502", "503"))
                .body(
                        "paths.'/api/v1/datasets/guid/{guid}'.get.responses.keySet()",
                        hasItems("200", "404", "502", "503"))
                .body(
                        "paths.'/api/v1/datasets/{qualifiedName}'.get.responses.keySet()",
                        hasItems("200", "404", "502", "503"))
                .body("paths.'/api/v1/types/typedefs'.get.responses", hasKey("200"));
    }

    /**
     * Fails when the API changes without the snapshot. To update it, run
     * {@code mvn -pl sourcelume-registry-runtime-quarkus test -Dtest=OpenApiDocumentTest -Dopenapi.snapshot.update=true}
     * from the repository root and commit docs/openapi.yaml.
     */
    @Test
    void shouldMatchCommittedSnapshot() throws IOException {
        String generated = given().when()
                .get("/q/openapi")
                .then()
                .statusCode(200)
                .extract()
                .asString();

        if (Boolean.getBoolean("openapi.snapshot.update")) {
            String header = licenseHeader(readSnapshot());
            Files.writeString(SNAPSHOT, header + generated.strip() + "\n", StandardCharsets.UTF_8);
        }

        String committed = readSnapshot();
        assertEquals(
                committed.substring(licenseHeader(committed).length()).strip(),
                generated.strip(),
                "docs/openapi.yaml is out of date; regenerate it with: mvn -pl sourcelume-registry-runtime-quarkus"
                        + " test -Dtest=OpenApiDocumentTest -Dopenapi.snapshot.update=true");
    }

    /** The snapshot with LF line endings, as a Windows checkout (core.autocrlf) may carry CRLF. */
    private static String readSnapshot() throws IOException {
        return Files.readString(SNAPSHOT, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The leading comment block (the ASF header RAT requires), kept across updates. */
    private static String licenseHeader(String snapshot) {
        StringBuilder header = new StringBuilder();
        for (String line : snapshot.split("\n", -1)) {
            if (!line.startsWith("#")) {
                break;
            }
            header.append(line).append('\n');
        }
        return header.toString();
    }
}

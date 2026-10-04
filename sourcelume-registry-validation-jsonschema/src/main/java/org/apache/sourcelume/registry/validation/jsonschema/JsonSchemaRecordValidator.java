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
package org.apache.sourcelume.registry.validation.jsonschema;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaContext;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SchemaRegistryConfig;
import com.networknt.schema.dialect.Dialect;
import com.networknt.schema.dialect.Dialects;
import java.util.List;
import java.util.Locale;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.apache.sourcelume.registry.core.validation.RecordValidator;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;

/**
 * Structural validation stage of the RecordValidator chain: checks the raw
 * JSON document against the sourcelume-spec JSON Schema (draft 2020-12).
 *
 * <p>Format assertions ("date-time", "uri") are enabled explicitly. Since JSON
 * Schema draft 2019-09 the "format" keyword only annotates by default; the
 * Python reference validator (tools/validate.py) enforces formats via
 * jsonschema[format], and this stage matches that behavior for parity.
 *
 * <p>Not a JSON-LD-aware check: aliased or full-IRI properties that the schema
 * does not know are invisible here (the spec schema allows additional
 * properties). That gap is closed by the SHACL stage
 * (sourcelume-registry-validation-shacl).
 */
public final class JsonSchemaRecordValidator implements RecordValidator {

    /** Stable plugin identifier used in chains and META-INF/services. */
    public static final String ID = "json-schema";

    /**
     * Structural checks run first; semantic (RDF-level) stages follow.
     */
    public static final int ORDER = 100;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Schema schema;

    /**
     * Creates a validator using the default schema bundled in sourcelume-spec.
     */
    public JsonSchemaRecordValidator() {
        this(SpecResourceLoader.DEFAULT_SCHEMA_RESOURCE);
    }

    /**
     * Creates a validator for a specific schema classpath resource. Visible
     * for tests and for future multi-version support.
     *
     * @param schemaResource classpath resource path of the JSON Schema
     */
    JsonSchemaRecordValidator(String schemaResource) {
        String schemaJson = SpecResourceLoader.loadResourceString(schemaResource);
        JsonNode schemaNode = readTree(schemaJson);
        SchemaRegistryConfig config = SchemaRegistryConfig.builder()
                .formatAssertionsEnabled(true)
                .locale(Locale.ROOT)
                .build();
        Dialect dialect = Dialects.getDraft202012();
        SchemaRegistry registry = SchemaRegistry.withDialect(dialect, builder -> builder.schemaRegistryConfig(config));
        SchemaContext context = new SchemaContext(dialect, registry);
        this.schema = context.newSchema(SchemaLocation.DOCUMENT, schemaNode, null);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public int order() {
        return ORDER;
    }

    @Override
    public ValidationResult validate(String jsonLd) {
        JsonNode document;
        try {
            document = MAPPER.readTree(jsonLd);
        } catch (JsonProcessingException e) {
            return ValidationResult.failed(
                    List.of(ValidationIssue.violation(ID, "/", "Not valid JSON: " + e.getOriginalMessage())));
        }
        List<Error> errors = schema.validate(document);
        if (errors.isEmpty()) {
            return ValidationResult.OK;
        }
        List<ValidationIssue> issues = errors.stream()
                .map(error -> ValidationIssue.violation(ID, instancePath(error), error.getMessage()))
                .toList();
        return ValidationResult.failed(issues);
    }

    /**
     * networknt reports the root document as an empty JSON pointer; this
     * stage normalizes it to "/" so callers get a uniform path vocabulary.
     */
    private static String instancePath(Error error) {
        if (error.getInstanceLocation() == null) {
            return "/";
        }
        String path = error.getInstanceLocation().toString();
        return path.isEmpty() ? "/" : path;
    }

    private static JsonNode readTree(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Spec schema resource is not valid JSON", e);
        }
    }
}

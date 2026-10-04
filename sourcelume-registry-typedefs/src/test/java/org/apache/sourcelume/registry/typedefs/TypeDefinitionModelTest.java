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
package org.apache.sourcelume.registry.typedefs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Structural assertions on the Sourcelume type model. The model is loaded
 * from the classpath exactly as the runtime's bootstrap loads it, so a
 * broken model fails here rather than at Atlas registration time.
 */
class TypeDefinitionModelTest {

    private static final String MODEL_RESOURCE = "/models/sourcelume/sourcelume_model.json";

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void loadsFromTheClasspath() throws IOException {
        assertNotNull(modelRoot());
    }

    @Test
    void declaresTheRecordStatusEnum() throws IOException {
        JsonNode enumDefs = modelRoot().path("enumDefs");
        assertTrue(enumDefs.isArray(), "enumDefs must be an array");
        assertEquals(1, enumDefs.size(), "exactly one enum is expected");
        JsonNode recordStatus = enumDefs.get(0);
        assertEquals("sourcelume_record_status", recordStatus.path("name").asText());
        // Atlas enum elements carry ordinal/value, not name — the real
        // backend rejects name-based elements (found in the first run
        // against Atlas).
        List<String> values = new ArrayList<>();
        int expectedOrdinal = 0;
        for (JsonNode element : recordStatus.path("elementDefs")) {
            assertEquals(expectedOrdinal++, element.path("ordinal").asInt());
            values.add(element.path("value").asText());
        }
        assertEquals(List.of("INCOMPLETE", "VALIDATED"), values);
    }

    @Test
    void datasetCarriesTheIngestLifecycleAttributes() throws IOException {
        JsonNode attributes = datasetEntity().path("attributeDefs");
        JsonNode recordStatus = attribute(attributes, "recordStatus");
        assertEquals("sourcelume_record_status", recordStatus.path("typeName").asText());
        // Optional on purpose: the ingest path always writes a status, but
        // Atlas refuses to add a mandatory attribute to an existing type
        // (CANNOT_ADD_MANDATORY_ATTRIBUTE), so the bootstrap must be able
        // to extend a pre-existing sourcelume_dataset.
        assertTrue(
                recordStatus.path("isOptional").asBoolean(),
                "mandatory attributes cannot be added to an existing type in Atlas");
        assertTrue(recordStatus.path("isIndexable").asBoolean(), "the worker polls by status");

        JsonNode rawJsonLd = attribute(attributes, "rawJsonLd");
        assertEquals("string", rawJsonLd.path("typeName").asText());
        assertTrue(rawJsonLd.path("isOptional").asBoolean(), "same schema-evolution constraint as recordStatus");
        assertFalse(rawJsonLd.path("isIndexable").asBoolean());

        JsonNode sha256 = attribute(attributes, "sha256");
        assertEquals("string", sha256.path("typeName").asText());
        assertTrue(sha256.path("isOptional").asBoolean());
        assertFalse(sha256.path("isIndexable").asBoolean());

        JsonNode validatedBy = attribute(attributes, "validatedBy");
        assertEquals("string", validatedBy.path("typeName").asText());
        assertTrue(validatedBy.path("isOptional").asBoolean());

        JsonNode validatedAt = attribute(attributes, "validatedAt");
        assertEquals("string", validatedAt.path("typeName").asText());
        assertTrue(validatedAt.path("isOptional").asBoolean());

        JsonNode validationIssues = attribute(attributes, "validationIssues");
        assertEquals("string", validationIssues.path("typeName").asText());
        assertTrue(validationIssues.path("isOptional").asBoolean(), "issues only exist once a record was validated");
        assertFalse(validationIssues.path("isIndexable").asBoolean());
    }

    @Test
    void datasetKeepsItsExistingAttributes() throws IOException {
        JsonNode attributes = datasetEntity().path("attributeDefs");
        assertNotNull(attribute(attributes, "sourceUri"));
        assertNotNull(attribute(attributes, "licenseId"));
    }

    @Test
    void qualifiedNameStaysInheritedFromTheAtlasSupertype() throws IOException {
        // Atlas derives qualifiedName (and unique-attribute lookups) from the
        // Referenceable super type via DataSet; redeclaring it here would be
        // rejected by the Atlas type system as a duplicate attribute.
        JsonNode attributes = datasetEntity().path("attributeDefs");
        assertFalse(
                attributes.isArray() && hasAttribute(attributes, "qualifiedName"),
                "qualifiedName is inherited from Referenceable and must not be redeclared");
    }

    private JsonNode modelRoot() throws IOException {
        try (InputStream in = getClass().getResourceAsStream(MODEL_RESOURCE)) {
            assertNotNull(in, "model resource not found: " + MODEL_RESOURCE);
            return objectMapper.readTree(in);
        }
    }

    private JsonNode datasetEntity() throws IOException {
        for (JsonNode entity : modelRoot().path("entityDefs")) {
            if ("sourcelume_dataset".equals(entity.path("name").asText())) {
                return entity;
            }
        }
        throw new AssertionError("entityDef sourcelume_dataset not found");
    }

    private JsonNode attribute(JsonNode attributes, String name) {
        JsonNode attribute = findAttribute(attributes, name);
        assertNotNull(attribute, "attribute not found: " + name);
        return attribute;
    }

    private boolean hasAttribute(JsonNode attributes, String name) {
        return findAttribute(attributes, name) != null;
    }

    private JsonNode findAttribute(JsonNode attributes, String name) {
        for (JsonNode attribute : attributes) {
            if (name.equals(attribute.path("name").asText())) {
                return attribute;
            }
        }
        return null;
    }
}

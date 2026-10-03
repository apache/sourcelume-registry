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
        List<String> values = new ArrayList<>();
        for (JsonNode element : recordStatus.path("elementDefs")) {
            values.add(element.path("name").asText());
        }
        assertEquals(List.of("PENDING", "INCOMPLETE", "ACTIVE"), values);
    }

    @Test
    void datasetCarriesTheIngestLifecycleAttributes() throws IOException {
        JsonNode attributes = datasetEntity().path("attributeDefs");
        JsonNode recordStatus = attribute(attributes, "recordStatus");
        assertEquals("sourcelume_record_status", recordStatus.path("typeName").asText());
        assertFalse(recordStatus.path("isOptional").asBoolean(), "a record always has a status");
        assertTrue(recordStatus.path("isIndexable").asBoolean(), "the worker polls by status");

        JsonNode rawJsonLd = attribute(attributes, "rawJsonLd");
        assertEquals("string", rawJsonLd.path("typeName").asText());
        assertFalse(rawJsonLd.path("isOptional").asBoolean(), "a stored record always has its raw JSON-LD");
        assertFalse(rawJsonLd.path("isIndexable").asBoolean());

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

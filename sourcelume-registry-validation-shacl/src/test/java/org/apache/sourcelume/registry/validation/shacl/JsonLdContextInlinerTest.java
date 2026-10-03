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
package org.apache.sourcelume.registry.validation.shacl;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JsonLdContextInlinerTest {

    private static final String CONTEXT_RESOURCE = "context/0.0.1/sourcelume.jsonld";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonLdContextInliner inliner;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode payload =
                MAPPER.readTree("{\"license\": {\"@id\": \"http://purl.org/dc/terms/license\", \"@type\": \"@id\"}}");
        inliner = new JsonLdContextInliner(CONTEXT_RESOURCE, payload);
    }

    @Test
    void relativeReferenceIsReplaced() throws Exception {
        JsonLdContextInliner.InlineResult result =
                inliner.inline("{\"@context\": \"../" + CONTEXT_RESOURCE + "\", \"id\": \"https://example.org/r\"}");
        assertTrue(result.hasAnyContext());
        assertTrue(result.unknownContexts().isEmpty());
        JsonNode context = MAPPER.readTree(result.document()).get("@context");
        assertTrue(context.isObject());
        assertNotNull(context.get("license"));
    }

    @Test
    void absoluteReferenceIsReplaced() throws Exception {
        JsonLdContextInliner.InlineResult result =
                inliner.inline("{\"@context\": \"https://sourcelume.apache.org/" + CONTEXT_RESOURCE + "\"}");
        assertTrue(MAPPER.readTree(result.document()).get("@context").isObject());
    }

    @Test
    void arrayFormReferenceIsReplacedElementWise() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline("{\"@context\": [\"https://sourcelume.apache.org/"
                + CONTEXT_RESOURCE + "\", {\"name\": \"https://schema.org/name\"}]}");
        JsonNode context = MAPPER.readTree(result.document()).get("@context");
        assertTrue(context.isArray());
        assertTrue(context.get(0).isObject());
        assertEquals("https://schema.org/name", context.get(1).get("name").asText());
    }

    @Test
    void inlineContextObjectIsPassedThrough() throws Exception {
        String doc = "{\"@context\": {\"name\": \"https://schema.org/name\"}}";
        JsonLdContextInliner.InlineResult result = inliner.inline(doc);
        assertEquals(MAPPER.readTree(doc), MAPPER.readTree(result.document()));
    }

    @Test
    void unknownRemoteContextIsReported() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline("{\"@context\": \"https://schema.org\"}");
        assertTrue(result.hasAnyContext());
        assertNull(result.document());
        assertEquals(java.util.List.of("https://schema.org"), result.unknownContexts());
    }

    @Test
    void documentWithoutContextIsReported() throws Exception {
        JsonLdContextInliner.InlineResult result =
                inliner.inline("{\"id\": \"https://example.org/r\", \"name\": \"no context\"}");
        assertFalse(result.hasAnyContext());
        assertNull(result.document());
    }

    @Test
    void nestedNodeObjectsAreHandled() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline("{\"@context\": \"../" + CONTEXT_RESOURCE + "\","
                + " \"creator\": [{\"name\": \"x\", \"@context\": \"../" + CONTEXT_RESOURCE + "\"}]}");
        JsonNode node = MAPPER.readTree(result.document());
        assertTrue(node.get("@context").isObject());
        assertTrue(node.at("/creator/0/@context").isObject());
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(JsonProcessingException.class, () -> inliner.inline("{ not json "));
    }
}

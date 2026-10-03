/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file distributed with
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
package org.apache.sourcelume.registry.validation.shacl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonLdContextInlinerTest {

    private static final String CONTEXT_RESOURCE = "context/0.0.1/sourcelume.jsonld";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonLdContextInliner inliner;

    @BeforeEach
    void setUp() throws Exception {
        JsonNode payload = MAPPER.readTree(
                "{\"license\": {\"@id\": \"http://purl.org/dc/terms/license\", \"@type\": \"@id\"}}");
        inliner = new JsonLdContextInliner(CONTEXT_RESOURCE, payload);
    }

    @Test
    void relativeReferenceIsReplaced() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": \"../" + CONTEXT_RESOURCE + "\", \"id\": \"https://example.org/r\"}");
        assertTrue(result.hasAnyContext());
        assertTrue(result.refusals().isEmpty());
        JsonNode context = MAPPER.readTree(result.document()).get("@context");
        assertTrue(context.isObject());
        assertNotNull(context.get("license"));
    }

    @Test
    void absoluteReferenceIsReplaced() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": \"https://sourcelume.apache.org/" + CONTEXT_RESOURCE + "\"}");
        assertTrue(MAPPER.readTree(result.document()).get("@context").isObject());
    }

    @Test
    void arrayFormReferenceIsReplacedElementWise() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": [\"https://sourcelume.apache.org/" + CONTEXT_RESOURCE
                        + "\", {\"name\": \"https://schema.org/name\"}]}");
        assertTrue(result.refusals().isEmpty());
        JsonNode context = MAPPER.readTree(result.document()).get("@context");
        assertTrue(context.isArray());
        assertTrue(context.get(0).isObject());
        assertEquals("https://schema.org/name", context.get(1).get("name").asText());
    }

    @Test
    void inlineContextObjectIsPassedThrough() throws Exception {
        String doc = "{\"@context\": {\"name\": \"https://schema.org/name\"}}";
        JsonLdContextInliner.InlineResult result = inliner.inline(doc);
        assertTrue(result.refusals().isEmpty());
        assertEquals(MAPPER.readTree(doc), MAPPER.readTree(result.document()));
    }

    @Test
    void unknownRemoteContextIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": \"https://schema.org\"}");
        assertTrue(result.hasAnyContext());
        assertNull(result.document());
        assertEquals(1, result.refusals().size());
        assertTrue(result.refusals().get(0).contains("https://schema.org"));
    }

    @Test
    void importInsideInlineContextIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": {\"@import\": \"https://attacker.example/c.jsonld\"}}");
        assertNull(result.document());
        assertTrue(result.refusals().get(0).contains("@import"));
        assertTrue(result.refusals().get(0).contains("https://attacker.example/c.jsonld"));
    }

    @Test
    void importInsideContextArrayElementIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": [\"../" + CONTEXT_RESOURCE
                        + "\", {\"@import\": \"https://attacker.example/c.jsonld\"}]}");
        assertNull(result.document());
        assertTrue(result.refusals().get(0).contains("@import"));
    }

    @Test
    void termScopedContextReferenceIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": {\"license\": {\"@id\": \"http://purl.org/dc/terms/license\","
                        + " \"@context\": \"https://attacker.example/c.jsonld\"}}}");
        assertNull(result.document());
        assertTrue(result.refusals().get(0).contains("term-scoped"));
        assertTrue(result.refusals().get(0).contains("https://attacker.example/c.jsonld"));
    }

    /** C1 regression: scoped contexts as arrays of references fetch remotely too. */
    @Test
    void termScopedContextArrayReferenceIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": {\"license\": {\"@id\": \"http://purl.org/dc/terms/license\","
                        + " \"@context\": [\"https://attacker.example/c.jsonld\"]}}}");
        assertNull(result.document());
        assertTrue(result.refusals().get(0).contains("term-scoped"));
        assertTrue(result.refusals().get(0).contains("https://attacker.example/c.jsonld"));
    }

    @Test
    void nestedScopedContextObjectIsScannedRecursively() throws Exception {
        // term definition -> scoped context object -> term definition with
        // another scoped context reference: every level must be covered
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": {\"name\": {\"@id\": \"https://schema.org/name\","
                        + " \"@context\": {\"givenName\": {\"@id\": \"https://schema.org/givenName\","
                        + " \"@context\": \"https://attacker.example/deep.jsonld\"}}}}}");
        assertNull(result.document());
        assertTrue(result.refusals().get(0).contains("term-scoped"));
        assertTrue(result.refusals().get(0).contains("https://attacker.example/deep.jsonld"));
    }

    @Test
    void nullContextValueIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": null, \"id\": \"https://example.org/r\"}");
        assertNull(result.document());
        assertTrue(result.hasAnyContext());
        assertTrue(result.refusals().get(0).contains("must be a string, array, or object"));
    }

    @Test
    void scalarContextValueIsRefused() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": 5}");
        assertTrue(result.hasAnyContext());
        assertTrue(result.refusals().get(0).contains("must be a string, array, or object"));
    }

    @Test
    void documentWithoutContextIsReported() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"id\": \"https://example.org/r\", \"name\": \"no context\"}");
        assertFalse(result.hasAnyContext());
        assertNull(result.document());
        assertTrue(result.refusals().isEmpty());
    }

    @Test
    void replacementCapIsEnforced() throws Exception {
        StringBuilder doc = new StringBuilder("[");
        for (int i = 0; i < JsonLdContextInliner.MAX_CONTEXT_REPLACEMENTS + 2; i++) {
            if (i > 0) {
                doc.append(',');
            }
            doc.append("{\"@context\": \"../").append(CONTEXT_RESOURCE).append("\"}");
        }
        doc.append(']');
        JsonLdContextInliner.InlineResult result = inliner.inline(doc.toString());
        assertNull(result.document());
        assertTrue(result.hasAnyContext());
        assertEquals(1, result.refusals().size());
        assertTrue(result.refusals().get(0).contains("more than"));
    }

    @Test
    void nestedNodeObjectsAreHandled() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": \"../" + CONTEXT_RESOURCE + "\","
                        + " \"creator\": [{\"name\": \"x\", \"@context\": \"../" + CONTEXT_RESOURCE + "\"}]}");
        assertTrue(result.refusals().isEmpty());
        JsonNode node = MAPPER.readTree(result.document());
        assertTrue(node.get("@context").isObject());
        assertTrue(node.at("/creator/0/@context").isObject());
    }

    /** I1 regression: many distinct unknown references must not amplify into refusals. */
    @Test
    void refusalReportingIsCapped() throws Exception {
        StringBuilder doc = new StringBuilder("{");
        doc.append("\"@context\": \"").append(CONTEXT_RESOURCE).append("\",");
        doc.append("\"creator\": [");
        int distinctUnknown = JsonLdContextInliner.MAX_REPORTED_REFUSALS + 5;
        for (int i = 0; i < distinctUnknown; i++) {
            if (i > 0) {
                doc.append(',');
            }
            doc.append("{\"@context\": \"https://unknown-").append(i).append(".example/ctx\"}");
        }
        doc.append("]}");
        JsonLdContextInliner.InlineResult result = inliner.inline(doc.toString());
        assertNull(result.document());
        assertEquals(JsonLdContextInliner.MAX_REPORTED_REFUSALS + 1,
                result.refusals().size());
        assertTrue(result.refusals().get(JsonLdContextInliner.MAX_REPORTED_REFUSALS)
                .contains("more refusals suppressed"));
    }

    @Test
    void malformedJsonIsRejected() {
        assertThrows(JsonProcessingException.class, () -> inliner.inline("{ not json "));
    }

    @Test
    void oneRefusalIsReportedPerUnknownReference() throws Exception {
        JsonLdContextInliner.InlineResult result = inliner.inline(
                "{\"@context\": \"https://schema.org\","
                        + " \"creator\": [{\"@context\": \"https://xmlns.com/foaf/0.1/\"}]}");
        assertEquals(2, result.refusals().size());
        assertTrue(result.refusals().get(0).contains("https://schema.org"));
        assertTrue(result.refusals().get(1).contains("https://xmlns.com/foaf/0.1/"));
    }
}

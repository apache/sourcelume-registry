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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;

/**
 * Inlines the bundled sourcelume JSON-LD context into a document before
 * RDF expansion, so SHACL validation runs fully offline.
 *
 * <p>Documents reference the context by IRI (the spec currently defines no
 * canonical absolute context IRI, so examples use a relative path). Fetching
 * whatever IRI an ingested document names would make validation dependent
 * on network access and would allow ingest payloads to direct the validator
 * at arbitrary hosts (SSRF). Instead, any reference ending in the bundled
 * context resource path is replaced with the context carried in the spec
 * artifact; any other string reference is reported as unknown and the
 * document is refused.
 *
 * <p>Inline context objects are passed through untouched - they are already
 * local and their term definitions are visible to the RDF parser.
 */
final class JsonLdContextInliner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The result of inlining a document.
     *
     * @param document        the inlined document, or {@code null} if inlining was refused
     * @param unknownContexts string context references that could not be resolved locally
     * @param hasAnyContext   whether the document contained any {@code @context} at all
     */
    record InlineResult(String document, List<String> unknownContexts, boolean hasAnyContext) {
        static InlineResult unknown(List<String> unknownContexts, boolean hasAnyContext) {
            return new InlineResult(null, List.copyOf(unknownContexts), hasAnyContext);
        }

        static InlineResult inlined(String document) {
            return new InlineResult(document, List.of(), true);
        }
    }

    private final String contextResource;
    private final JsonNode contextPayload;

    /**
     * @param contextResource the bundled context resource path, used to recognize
     *                        references to it (relative or absolute; matched by suffix)
     * @param contextPayload  the {@code @context} payload (the value of the context
     *                        document's top-level {@code @context} member, not the
     *                        whole context document)
     */
    JsonLdContextInliner(String contextResource, JsonNode contextPayload) {
        this.contextResource = contextResource;
        this.contextPayload = contextPayload;
    }

    /**
     * Inlines the bundled context into every reference to it.
     *
     * @param jsonLd the raw document
     * @return the inlined document, or the list of unknown references
     * @throws JsonProcessingException if the document is not valid JSON
     */
    InlineResult inline(String jsonLd) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(jsonLd);
        List<String> unknown = new ArrayList<>();
        boolean[] hasContext = {false};
        walkValue(root, unknown, hasContext);
        if (!hasContext[0]) {
            return InlineResult.unknown(unknown, false);
        }
        if (!unknown.isEmpty()) {
            return InlineResult.unknown(unknown, true);
        }
        return InlineResult.inlined(MAPPER.writeValueAsString(root));
    }

    private void walk(ObjectNode node, List<String> unknown, boolean[] hasContext) {
        JsonNode context = node.get("@context");
        if (context != null) {
            hasContext[0] = true;
            node.replace("@context", processContext(context, unknown));
        }
        node.fieldNames().forEachRemaining(name -> {
            if (!"@context".equals(name)) {
                walkValue(node.get(name), unknown, hasContext);
            }
        });
    }

    private void walkValue(JsonNode value, List<String> unknown, boolean[] hasContext) {
        if (value.isObject()) {
            walk((ObjectNode) value, unknown, hasContext);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                walkValue(element, unknown, hasContext);
            }
        }
    }

    private JsonNode processContext(JsonNode context, List<String> unknown) {
        if (context.isTextual()) {
            return resolveReference(context.asText(), context, unknown);
        }
        if (context.isArray()) {
            ArrayNode resolved = MAPPER.createArrayNode();
            for (JsonNode element : (ArrayNode) context) {
                resolved.add(element.isTextual() ? resolveReference(element.asText(), element, unknown) : element);
            }
            return resolved;
        }
        return context;
    }

    private JsonNode resolveReference(String reference, JsonNode original, List<String> unknown) {
        if (reference.endsWith(contextResource)) {
            return contextPayload;
        }
        unknown.add(reference);
        return original;
    }
}

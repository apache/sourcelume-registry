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
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

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
 * artifact; any other context reference is refused.
 *
 * <p>Refused - not just unrecognized - are all constructs that would make the
 * RDF parser retrieve a remote document, so the offline guarantee is
 * structural rather than pattern-based. A JSON-LD processor only ever
 * retrieves documents for string {@code @context} references, for
 * {@code @import} inside contexts, and for term-scoped {@code @context}
 * references inside term definitions; all three are covered here:
 * <ul>
 *   <li>string {@code @context} references anywhere in the document must end
 *       in the bundled context resource path (anything else is refused),</li>
 *   <li>{@code @import} inside an inline context is refused outright, and</li>
 *   <li>term-scoped {@code @context} references are refused (scoped context
 *       <em>objects</em> are legal and scanned recursively).</li>
 * </ul>
 *
 * <p>Inline context objects are passed through unchanged otherwise: they are
 * already local, and their term definitions are visible to the RDF parser.
 * The number of replacements is capped: an ingest-controlled document made
 * of repeated context references would otherwise be amplified by the full
 * context payload per occurrence and exhaust heap before validation runs.
 * Instances are stateless; all per-document state lives in the
 * {@link #inline} call.
 */
final class JsonLdContextInliner {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Hard cap on how often the bundled context may be inlined into one
     * document. Legitimate records use one; a document near the cap is
     * either hostile or broken, and refusing bounds the memory the
     * inlined document can occupy.
     */
    static final int MAX_CONTEXT_REPLACEMENTS = 64;

    /**
     * The result of inlining a document.
     *
     * @param document      the inlined document, or {@code null} if inlining was refused
     * @param refusals      human-readable reasons why the document was refused,
     *                      empty if inlining succeeded
     * @param hasAnyContext whether the document contained any {@code @context} at all
     */
    record InlineResult(String document, List<String> refusals, boolean hasAnyContext) {
        static InlineResult refused(List<String> refusals, boolean hasAnyContext) {
            return new InlineResult(null, List.copyOf(refusals), hasAnyContext);
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
     * @return the inlined document, or the reasons it was refused
     * @throws JsonProcessingException if the document is not valid JSON
     */
    InlineResult inline(String jsonLd) throws JsonProcessingException {
        JsonNode root = MAPPER.readTree(jsonLd);
        List<String> refusals = new ArrayList<>();
        boolean[] hasContext = {false};
        int[] replacements = {0};
        walkValue(root, refusals, hasContext, replacements);
        if (!hasContext[0]) {
            return InlineResult.refused(refusals, false);
        }
        if (!refusals.isEmpty()) {
            return InlineResult.refused(refusals, true);
        }
        return InlineResult.inlined(MAPPER.writeValueAsString(root));
    }

    private void walkValue(JsonNode value, List<String> refusals,
                           boolean[] hasContext, int[] replacements) {
        if (value.isObject()) {
            walk((ObjectNode) value, refusals, hasContext, replacements);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                walkValue(element, refusals, hasContext, replacements);
            }
        }
    }

    private void walk(ObjectNode node, List<String> refusals,
                      boolean[] hasContext, int[] replacements) {
        JsonNode context = node.get("@context");
        if (context != null) {
            hasContext[0] = true;
            node.replace("@context",
                    processContext(context, refusals, replacements));
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!"@context".equals(field.getKey())) {
                walkValue(field.getValue(), refusals, hasContext, replacements);
            }
        }
    }

    /**
     * Handles one {@code @context} value: replaces references to the bundled
     * context, scans inline context objects for retrieval-triggering
     * constructs, and refuses anything unexpected.
     *
     * @return the processed context value (replaced reference, or the
     *         original for pass-through and refused cases)
     */
    private JsonNode processContext(JsonNode context, List<String> refusals,
                                    int[] replacements) {
        if (context.isTextual()) {
            return resolveReference(context.asText(), refusals, replacements);
        }
        if (context.isArray()) {
            ArrayNode resolved = MAPPER.createArrayNode();
            for (JsonNode element : (ArrayNode) context) {
                if (element.isTextual()) {
                    resolved.add(resolveReference(element.asText(), refusals, replacements));
                } else if (element.isObject()) {
                    scanContextObject((ObjectNode) element, refusals);
                    resolved.add(element);
                } else {
                    refusals.add("@context array elements must be strings or objects, not "
                            + element.getNodeType());
                    resolved.add(element);
                }
            }
            return resolved;
        }
        if (context.isObject()) {
            scanContextObject((ObjectNode) context, refusals);
            return context;
        }
        refusals.add("@context must be a string, array, or object, not "
                + context.getNodeType());
        return context;
    }

    /**
     * Recursively scans an inline context object. Refuses everything that
     * would make the RDF parser retrieve a remote document: {@code @import}
     * at any depth, and term-scoped {@code @context} references (scoped
     * context objects are legal and scanned in turn).
     */
    private void scanContextObject(ObjectNode context, List<String> refusals) {
        if (context.has("@import")) {
            refusals.add("@import inside inline context: '"
                    + context.get("@import") + "' (nothing is fetched)");
        }
        for (Map.Entry<String, JsonNode> field : context.properties()) {
            JsonNode value = field.getValue();
            if ("@context".equals(field.getKey()) && value.isTextual()) {
                refusals.add("term-scoped context reference '" + value.asText()
                        + "' (nothing is fetched)");
            } else {
                scanContextValue(value, refusals);
            }
        }
    }

    /**
     * Recursion into the values of an inline context (term definitions and
     * scoped context objects nest arbitrarily).
     */
    private void scanContextValue(JsonNode value, List<String> refusals) {
        if (value.isObject()) {
            scanContextObject((ObjectNode) value, refusals);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                scanContextValue(element, refusals);
            }
        }
    }

    /**
     * Resolves one string context reference by inlining the bundled
     * context payload (subject to the replacement cap). Anything else is
     * refused - the returned value does not matter then, because a refusal
     * rejects the whole document and the tree is never serialized.
     */
    private JsonNode resolveReference(String reference, List<String> refusals,
                                      int[] replacements) {
        if (!reference.endsWith(contextResource)) {
            refusals.add("unknown context reference '" + reference
                    + "' (only the bundled sourcelume context is accepted)");
        } else if (++replacements[0] > MAX_CONTEXT_REPLACEMENTS
                && replacements[0] == MAX_CONTEXT_REPLACEMENTS + 1) {
            // report the cap breach once, not per additional reference
            refusals.add("more than " + MAX_CONTEXT_REPLACEMENTS
                    + " context references in one document");
        }
        return contextPayload;
    }
}

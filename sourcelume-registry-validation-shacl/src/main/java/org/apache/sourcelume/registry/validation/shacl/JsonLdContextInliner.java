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
 * retrieves documents for string {@code @context} references (including the
 * elements of array-valued contexts), for {@code @import} inside contexts,
 * and for term-scoped {@code @context} references inside term definitions
 * (string or array form); all of these are covered here:
 * <ul>
 *   <li>string {@code @context} references anywhere in the document must end
 *       in the bundled context resource path (anything else is refused),</li>
 *   <li>{@code @import} inside an inline context is refused outright, and</li>
 *   <li>term-scoped {@code @context} references are refused in both string
 *       and array form (scoped context <em>objects</em> are legal and
 *       scanned recursively).</li>
 * </ul>
 *
 * <p>Inline context objects are passed through unchanged otherwise: they are
 * already local, and their term definitions are visible to the RDF parser.
 * Both amplification channels are bounded: the number of replacements (an
 * ingest-controlled document made of repeated context references would
 * otherwise be amplified by the full context payload per occurrence) and
 * the number of reported refusals (an ingest-controlled document made of
 * many distinct unknown references would otherwise allocate unbounded
 * refusal strings). Instances are stateless; all per-document state lives
 * in the {@link #inline} call.
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
     * Hard cap on how many refusals are reported per document; further
     * ones are counted, not stored, so a hostile document cannot amplify
     * into the refusal list (and from there into validation issues and
     * the HTTP response).
     */
    static final int MAX_REPORTED_REFUSALS = 10;

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
        InlineState state = new InlineState();
        walkValue(root, state);
        if (!state.hasContext) {
            return InlineResult.refused(state.reportedRefusals(), false);
        }
        if (!state.refusals.isEmpty() || state.suppressedRefusals > 0) {
            return InlineResult.refused(state.reportedRefusals(), true);
        }
        return InlineResult.inlined(MAPPER.writeValueAsString(root));
    }

    /**
     * All per-document mutable state of one {@link #inline} call; the inliner
     * itself stays stateless and thread-safe.
     */
    private final class InlineState {
        final List<String> refusals = new ArrayList<>();
        int suppressedRefusals;
        int replacements;
        boolean hasContext;

        void refuse(String reason) {
            if (refusals.size() < MAX_REPORTED_REFUSALS) {
                refusals.add(reason);
            } else {
                suppressedRefusals++;
            }
        }

        /** The reported refusals, with suppressed ones summarized in a final line. */
        List<String> reportedRefusals() {
            if (suppressedRefusals == 0) {
                return refusals;
            }
            List<String> reported = new ArrayList<>(refusals);
            reported.add("... and " + suppressedRefusals + " more refusals suppressed");
            return reported;
        }
    }

    private void walkValue(JsonNode value, InlineState state) {
        if (value.isObject()) {
            walk((ObjectNode) value, state);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                walkValue(element, state);
            }
        }
    }

    private void walk(ObjectNode node, InlineState state) {
        JsonNode context = node.get("@context");
        if (context != null) {
            state.hasContext = true;
            node.replace("@context", processContext(context, state));
        }
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (!"@context".equals(field.getKey())) {
                walkValue(field.getValue(), state);
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
    private JsonNode processContext(JsonNode context, InlineState state) {
        if (context.isTextual()) {
            return resolveReference(context.asText(), state);
        }
        if (context.isArray()) {
            ArrayNode resolved = MAPPER.createArrayNode();
            for (JsonNode element : (ArrayNode) context) {
                if (element.isTextual()) {
                    resolved.add(resolveReference(element.asText(), state));
                } else if (element.isObject()) {
                    scanContextObject((ObjectNode) element, state);
                    resolved.add(element);
                } else {
                    state.refuse("@context array elements must be strings or objects, not " + element.getNodeType());
                    resolved.add(element);
                }
            }
            return resolved;
        }
        if (context.isObject()) {
            scanContextObject((ObjectNode) context, state);
            return context;
        }
        state.refuse("@context must be a string, array, or object, not " + context.getNodeType());
        return context;
    }

    /**
     * Recursively scans an inline context object. Refuses everything that
     * would make the RDF parser retrieve a remote document: {@code @import}
     * at any depth, and term-scoped {@code @context} references (string or
     * array form; scoped context <em>objects</em> are legal and scanned in
     * turn).
     */
    private void scanContextObject(ObjectNode context, InlineState state) {
        if (context.has("@import")) {
            state.refuse("@import inside inline context: '" + context.get("@import") + "' (nothing is fetched)");
        }
        for (Map.Entry<String, JsonNode> field : context.properties()) {
            if ("@context".equals(field.getKey())) {
                scanScopedContextValue(field.getValue(), state);
            } else {
                scanContextValue(field.getValue(), state);
            }
        }
    }

    /**
     * A term-scoped {@code @context} value: string references and arrays of
     * references are retrieval triggers in both forms, so both are refused.
     * Only objects are legal here (and scanned recursively).
     */
    private void scanScopedContextValue(JsonNode value, InlineState state) {
        if (value.isTextual()) {
            state.refuse("term-scoped context reference '" + value.asText() + "' (nothing is fetched)");
        } else if (value.isObject()) {
            scanContextObject((ObjectNode) value, state);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                scanScopedContextValue(element, state);
            }
        } else {
            state.refuse("term-scoped @context must be a string, array, or object, not " + value.getNodeType());
        }
    }

    /**
     * Recursion into the values of an inline context (term definitions and
     * scoped context objects nest arbitrarily).
     */
    private void scanContextValue(JsonNode value, InlineState state) {
        if (value.isObject()) {
            scanContextObject((ObjectNode) value, state);
        } else if (value.isArray()) {
            for (JsonNode element : (ArrayNode) value) {
                scanContextValue(element, state);
            }
        }
    }

    /**
     * Resolves one string context reference by inlining the bundled
     * context payload (subject to the replacement cap). Anything else is
     * refused - the returned value does not matter then, because a refusal
     * rejects the whole document and the tree is never serialized.
     */
    private JsonNode resolveReference(String reference, InlineState state) {
        if (!reference.endsWith(contextResource)) {
            state.refuse(
                    "unknown context reference '" + reference + "' (only the bundled sourcelume context is accepted)");
        } else if (++state.replacements > MAX_CONTEXT_REPLACEMENTS
                && state.replacements == MAX_CONTEXT_REPLACEMENTS + 1) {
            // report the cap breach once, not per additional reference
            state.refuse("more than " + MAX_CONTEXT_REPLACEMENTS + " context references in one document");
        }
        return contextPayload;
    }
}

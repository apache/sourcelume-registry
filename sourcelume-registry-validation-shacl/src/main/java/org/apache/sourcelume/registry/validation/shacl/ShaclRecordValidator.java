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
import org.apache.jena.graph.Graph;
import org.apache.jena.graph.Node;
import org.apache.jena.graph.NodeFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFParser;
import org.apache.jena.riot.RiotException;
import org.apache.jena.shacl.ShaclValidator;
import org.apache.jena.shacl.Shapes;
import org.apache.jena.shacl.ValidationReport;
import org.apache.jena.shacl.validation.ReportEntry;
import org.apache.jena.shacl.validation.Severity;
import org.apache.jena.vocabulary.RDF;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.apache.sourcelume.registry.core.validation.RecordValidator;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;

import java.util.ArrayList;
import java.util.List;

/**
 * The "shacl" validation stage (order 200): validates the document as an
 * RDF graph against the SHACL shapes shipped in the sourcelume spec
 * artifact, mirroring the SHACL stage of the Python reference validator
 * (tools/validate.py, pyshacl).
 *
 * <p>Why SHACL in addition to the JSON Schema stage? The spec schema
 * allows additional properties, so term aliases cannot be constrained at
 * the JSON level: a record that claims an Apache-2.0 license under
 * "license" and a GPL-3.0 license under the full dct:license IRI passes
 * JSON Schema but fails SHACL cardinality and node-kind constraints on
 * the expanded RDF graph. SHACL is the only stage that sees the graph the
 * registry actually stores and queries.
 *
 * <p>The bundled context is inlined before expansion (see
 * {@link JsonLdContextInliner}); documents referencing remote contexts or
 * carrying no context at all are refused rather than silently expanded
 * with the wrong terms. A graph with no sourcelume terms would trivially
 * conform, which would be worse than a rejected document.
 */
public final class ShaclRecordValidator implements RecordValidator {

    /** Stable stage id, also used to register the plugin. */
    public static final String ID = "shacl";

    /** Runs after the "json-schema" stage. */
    public static final int ORDER = 200;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The class the SHACL shapes target. Kept in sync with the spec context
     * (which maps "ProvenanceRecord" to this IRI): after expansion, a graph
     * without a node of this type would trivially conform to the shapes -
     * for example when an inline context redefines the "type" term mapping.
     * Such graphs are refused instead (see validate).
     */
    static final String PROVENANCE_RECORD_CLASS =
            "https://sourcelume.apache.org/ns/0.0.1#ProvenanceRecord";

    private final Shapes shapes;
    private final JsonLdContextInliner contextInliner;

    /**
     * Loads the SHACL shapes and the JSON-LD context from the sourcelume
     * spec artifact on the classpath.
     */
    public ShaclRecordValidator() {
        this(SpecResourceLoader.DEFAULT_SHACL_RESOURCE, SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE);
    }

    /**
     * Constructor for tests that need explicit spec resources.
     *
     * @param shaclResource  classpath location of the SHACL shapes (Turtle)
     * @param contextResource classpath location of the JSON-LD context
     */
    ShaclRecordValidator(String shaclResource, String contextResource) {
        Graph shapesGraph = RDFParser.create()
                .fromString(SpecResourceLoader.loadResourceString(shaclResource))
                .lang(Lang.TURTLE)
                .toGraph();
        this.shapes = Shapes.parse(shapesGraph);
        JsonNode contextDocument;
        try {
            contextDocument = MAPPER.readTree(
                    SpecResourceLoader.loadResourceString(contextResource));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(
                    "Bundled context is not valid JSON: " + contextResource, e);
        }
        JsonNode payload = contextDocument.get("@context");
        if (payload == null) {
            throw new IllegalStateException(
                    "Bundled context has no top-level @context member: " + contextResource);
        }
        this.contextInliner = new JsonLdContextInliner(contextResource, payload);
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
        JsonLdContextInliner.InlineResult inlineResult;
        try {
            inlineResult = contextInliner.inline(jsonLd);
        } catch (JsonProcessingException e) {
            return ValidationResult.failed(List.of(
                    ValidationIssue.violation(ID, "/", "Not valid JSON: " + e.getOriginalMessage())));
        }

        if (!inlineResult.hasAnyContext()) {
            return ValidationResult.failed(List.of(
                    ValidationIssue.violation(ID, "/@context",
                            "Document has no @context; the sourcelume terms could not be expanded")));
        }
        if (!inlineResult.refusals().isEmpty()) {
            List<ValidationIssue> issues = new ArrayList<>();
            for (String refusal : inlineResult.refusals()) {
                issues.add(ValidationIssue.violation(ID, "/@context",
                        "Context refused: " + refusal));
            }
            return ValidationResult.failed(issues);
        }

        Graph graph;
        try {
            graph = RDFParser.create()
                    .fromString(inlineResult.document())
                    .lang(Lang.JSONLD)
                    .toGraph();
        } catch (RiotException e) {
            return ValidationResult.failed(List.of(
                    ValidationIssue.violation(ID, "/", "Not valid JSON-LD: " + e.getMessage())));
        }

        // Trivial-conformance guard: the shapes target sl:ProvenanceRecord
        // nodes; a document whose context games (empty inline context,
        // overridden "type" mapping) expand to no such node and would
        // conform vacuously. Refuse instead of validating an empty focus set.
        if (!graph.contains(Node.ANY, RDF.type.asNode(),
                NodeFactory.createURI(PROVENANCE_RECORD_CLASS))) {
            return ValidationResult.failed(List.of(
                    ValidationIssue.violation(ID, "/@type",
                            "Document expands to no node of type " + PROVENANCE_RECORD_CLASS
                                    + "; SHACL validation would trivially conform")));
        }

        ValidationReport report = ShaclValidator.get().validate(shapes, graph);
        List<ValidationIssue> issues = new ArrayList<>();
        for (ReportEntry entry : report.getEntries()) {
            issues.add(new ValidationIssue(
                    ID,
                    severityOf(entry),
                    pathOf(entry),
                    entry.message()));
        }
        if (report.conforms()) {
            return new ValidationResult(true, List.copyOf(issues));
        }
        return ValidationResult.failed(issues.isEmpty()
                ? List.of(ValidationIssue.violation(ID, "/", "SHACL validation failed"))
                : issues);
    }

    private static String pathOf(ReportEntry entry) {
        return entry.resultPath() == null ? "/" : entry.resultPath().toString();
    }

    private static ValidationIssue.Severity severityOf(ReportEntry entry) {
        if (Severity.Violation.equals(entry.severity())) {
            return ValidationIssue.Severity.VIOLATION;
        }
        if (Severity.Warning.equals(entry.severity())) {
            return ValidationIssue.Severity.WARNING;
        }
        return ValidationIssue.Severity.INFO;
    }
}

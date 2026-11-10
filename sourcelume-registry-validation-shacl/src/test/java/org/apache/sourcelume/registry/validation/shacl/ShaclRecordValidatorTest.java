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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.spi.ValidationIssue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class ShaclRecordValidatorTest {

    private static ShaclRecordValidator validator;

    @BeforeAll
    static void setUp() {
        validator = new ShaclRecordValidator();
    }

    @Test
    void idAndOrderAreStable() {
        assertEquals("shacl", validator.id());
        assertEquals(200, validator.order());
    }

    /**
     * The class the trivial-conformance guard looks for must be the one the
     * bundled context expands {@code ProvenanceRecord} to. If the spec moves
     * its namespace and this constant is not updated, every valid record
     * would be refused, so the two are compared directly.
     */
    @Test
    void provenanceRecordClassMatchesTheBundledContextNamespace() throws IOException {
        JsonNode context = new ObjectMapper()
                .readTree(SpecResourceLoader.loadResourceString(SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE))
                .get("@context");
        assertEquals(context.get("sl").asText() + "ProvenanceRecord", ShaclRecordValidator.PROVENANCE_RECORD_CLASS);
    }

    @Test
    void validRecordConforms() {
        ValidationResult result = validator.validate(record("valid-minimal.jsonld"));
        assertTrue(result.conforms(), () -> String.valueOf(result.issues()));
    }

    @Test
    void validTrainingRecordConforms() {
        ValidationResult result = validator.validate(record("valid-training-minimal.jsonld"));
        assertTrue(result.conforms(), () -> String.valueOf(result.issues()));
    }

    /**
     * The training shape constrains disclosure to its declared values;
     * a value outside the sh:in list is a violation on the expanded graph.
     */
    @Test
    void trainingRecordDisclosureOutsideShaclInFails() {
        ValidationResult result = validator.validate(record("invalid-training-disclosure.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().contains("disclosure") && i.message().contains("not in expected values")));
    }

    /**
     * The reason this stage exists: an aliased full-IRI property duplicating
     * the license claim passes the JSON Schema stage (additional properties
     * are unconstrained) but violates SHACL cardinality and node-kind
     * constraints on the expanded graph. Verified against the Python oracle
     * (pyshacl) with the same document.
     */
    @Test
    void aliasLicenseClaimFails() {
        ValidationResult result = validator.validate(record("schema-invisible-alias.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.path().contains("http://purl.org/dc/terms/license")
                        && i.severity() == ValidationIssue.Severity.VIOLATION));
    }

    @Test
    void licenseCategoryOutsideShaclInFails() {
        ValidationResult result = validator.validate(record("invalid-license-category.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .anyMatch(
                        i -> i.path().contains("licenseCategory") && i.message().contains("not in expected values")));
    }

    /**
     * Offline policy: the validator never fetches remote contexts. A document
     * referencing one is refused before RDF expansion (also closes the SSRF
     * window of ingest-controlled IRIs).
     */
    @Test
    void unknownRemoteContextIsRefused() {
        ValidationResult result = validator.validate(record("unknown-remote-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("https://schema.org")));
    }

    /**
     * Retrieval trigger: @import inside an inline context would make the
     * RDF parser fetch a remote document. Must be refused before expansion,
     * never fetched (the fixture names an unroutable address; a fetch
     * attempt would slow the test down or fail with a network error).
     */
    @Test
    void importInsideInlineContextIsRefused() {
        ValidationResult result = validator.validate(record("import-in-inline-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("@import")));
    }

    /** Retrieval trigger: term-scoped remote context reference, array form (C1 regression). */
    @Test
    void termScopedContextArrayReferenceIsRefused() {
        ValidationResult result = validator.validate(record("scoped-context-array-ref.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("term-scoped")));
    }

    /** Retrieval trigger: term-scoped remote context reference. */
    @Test
    void termScopedContextReferenceIsRefused() {
        ValidationResult result = validator.validate(record("scoped-context-ref.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("term-scoped")));
    }

    @Test
    void nullContextValueIsRefused() {
        ValidationResult result = validator.validate(record("null-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("must be a string or array")));
    }

    /**
     * An empty inline context would leave all terms unexpanded, so no
     * sl:ProvenanceRecord focus node would exist and the shapes would
     * conform vacuously. Inline context objects are refused outright.
     */
    @Test
    void emptyInlineContextIsRefused() {
        ValidationResult result = validator.validate(record("empty-inline-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("inline @context objects")));
    }

    /**
     * Same for the array form: an inline element overriding the "type"
     * term mapping would de-type the node even though the bundled context
     * is referenced as well.
     */
    @Test
    void contextArrayTypeOverrideIsRefused() {
        ValidationResult result = validator.validate(record("context-array-override.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("inline @context objects")));
    }

    /**
     * Trivial-conformance guard, still needed with the canonical context: a
     * record whose type does not expand to sl:ProvenanceRecord has no focus
     * node, so the shapes would conform vacuously. The validator must refuse.
     */
    @Test
    void recordOfAnotherTypeYieldsNoFocusNodeAndIsRefused() {
        ValidationResult result = validator.validate(record("wrong-type.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.message().contains("trivially conform")));
    }

    /**
     * Without a context the sourcelume terms could not be expanded; an
     * empty/untyped graph would trivially conform, which would be worse
     * than rejecting the document.
     */
    @Test
    void documentWithoutContextIsRefused() {
        ValidationResult result = validator.validate(record("no-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().equals("/@context")));
    }

    @Test
    void malformedJsonFailsWithoutException() {
        ValidationResult result = validator.validate(record("malformed.json"));
        assertFalse(result.conforms());
        assertTrue(result.issues().get(0).message().contains("Not valid JSON"));
    }

    private static String record(String name) {
        try (InputStream in = ShaclRecordValidatorTest.class.getResourceAsStream("/records/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + name, e);
        }
    }
}

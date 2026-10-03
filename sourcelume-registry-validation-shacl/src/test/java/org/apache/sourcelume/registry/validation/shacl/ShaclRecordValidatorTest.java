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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
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

    @Test
    void validRecordConforms() {
        ValidationResult result = validator.validate(record("valid-minimal.jsonld"));
        assertTrue(result.conforms(), () -> String.valueOf(result.issues()));
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

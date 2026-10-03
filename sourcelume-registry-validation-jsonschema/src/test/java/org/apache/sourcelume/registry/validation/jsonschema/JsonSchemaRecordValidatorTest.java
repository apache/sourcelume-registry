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
package org.apache.sourcelume.registry.validation.jsonschema;

import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JsonSchemaRecordValidatorTest {

    private static JsonSchemaRecordValidator validator;

    @BeforeAll
    static void setUp() {
        validator = new JsonSchemaRecordValidator();
    }

    @Test
    void idAndOrderAreStable() {
        assertEquals("json-schema", validator.id());
        assertEquals(100, validator.order());
    }

    @Test
    void validRecordConforms() {
        ValidationResult result = validator.validate(record("valid-minimal.jsonld"));
        assertTrue(result.conforms());
    }

    @Test
    void missingRequiredPropertyFails() {
        ValidationResult result = validator.validate(record("invalid-missing-license.jsonld"));
        assertFalse(result.conforms());
        // required-property errors are reported at the root object; the stage
        // normalizes networknt's empty root pointer to "/"
        assertTrue(result.issues().stream()
                .anyMatch(i -> i.message().contains("license")));
    }

    /**
     * Parity with the Python reference validator (jsonschema[format]):
     * a non-RFC3339 timestamp must be a violation, not an annotation.
     */
    @Test
    void badDateTimeFailsViaFormatAssertion() {
        ValidationResult result = validator.validate(record("invalid-bad-datetime.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().startsWith("/created")));
    }

    /**
     * Parity with the Python reference validator (rfc3987): the "uri" format
     * must reject record ids that are not valid URIs.
     */
    @Test
    void badUriFailsViaFormatAssertion() {
        ValidationResult result = validator.validate(record("invalid-bad-uri.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().anyMatch(i -> i.path().startsWith("/id")));
    }

    /**
     * Documents the gap this stage cannot see: an aliased full-IRI property
     * duplicating the license claim is invisible to the JSON Schema because
     * the spec allows additional properties. The SHACL stage catches it.
     */
    @Test
    void aliasLicenseIsInvisibleToSchemaStage() {
        ValidationResult result = validator.validate(record("schema-invisible-alias.jsonld"));
        assertTrue(result.conforms());
    }

    @Test
    void malformedJsonFailsWithoutException() {
        ValidationResult result = validator.validate(record("malformed.json"));
        assertFalse(result.conforms());
        assertEquals(1, result.issues().size());
        assertTrue(result.issues().get(0).message().contains("Not valid JSON"));
    }

    private static String record(String name) {
        try (InputStream in = JsonSchemaRecordValidatorTest.class
                .getResourceAsStream("/records/" + name)) {
            if (in == null) {
                throw new IllegalArgumentException("Fixture not found: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read fixture: " + name, e);
        }
    }
}

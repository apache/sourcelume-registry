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

import org.apache.sourcelume.registry.core.validation.RecordValidator;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test of the plugin mechanism with both shipped plugins on the
 * test classpath: ServiceLoader discovery, chain ordering (json-schema before
 * shacl), fail-fast behavior, and parity with the Python reference validator
 * on documents the oracle was run against.
 */
class ValidatorChainDiscoveryTest {

    private static ValidatorChain chain;

    @BeforeAll
    static void discoverChain() {
        chain = ValidatorChain.discover();
    }

    @Test
    void bothPluginsAreDiscoveredInExecutionOrder() {
        List<String> ids = chain.validators().stream().map(RecordValidator::id).toList();
        assertEquals(List.of("json-schema", "shacl"), ids);
    }

    @Test
    void validRecordConformsThroughWholeChain() {
        ValidationResult result = chain.validate(record("valid-minimal.jsonld"));
        assertTrue(result.conforms(), () -> String.valueOf(result.issues()));
    }

    /**
     * Parity with tools/validate.py: a schema-invalid document fails at the
     * first stage and never reaches SHACL expansion.
     */
    @Test
    void schemaInvalidDocumentStopsChainAtFirstStage() {
        ValidationResult result = chain.validate(record("invalid-missing-license.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream().allMatch(i -> "json-schema".equals(i.validatorId())));
    }

    /**
     * The headline case for the two-stage chain: the aliased license claim is
     * invisible to JSON Schema (conforms) and caught by SHACL - same verdict
     * the Python oracle (jsonschema[format] + pyshacl) produces.
     */
    @Test
    void aliasLicensePassesSchemaButFailsShacl() {
        ValidationResult result = chain.validate(record("schema-invisible-alias.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .anyMatch(i -> "shacl".equals(i.validatorId())
                        && i.path().contains("http://purl.org/dc/terms/license")));
    }

    @Test
    void invalidLicenseCategoryFailsSchemaStageFirst() {
        ValidationResult result = chain.validate(record("invalid-license-category.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .allMatch(i -> "json-schema".equals(i.validatorId())));
    }

    /**
     * A document the SHACL plugin refuses (remote context) is still evaluated
     * by the schema stage first; the refusal ends the chain.
     */
    @Test
    void remoteContextRefusalSurfacesThroughChain() {
        ValidationResult result = chain.validate(record("unknown-remote-context.jsonld"));
        assertFalse(result.conforms());
        assertTrue(result.issues().stream()
                .anyMatch(i -> "shacl".equals(i.validatorId())
                        && i.message().contains("Unknown context reference")));
    }

    private static String record(String name) {
        try (InputStream in = ValidatorChainDiscoveryTest.class
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

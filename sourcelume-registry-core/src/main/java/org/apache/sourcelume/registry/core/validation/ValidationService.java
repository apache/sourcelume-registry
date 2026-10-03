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
package org.apache.sourcelume.registry.core.validation;

import org.apache.sourcelume.registry.common.exception.SourcelumeValidationException;

/**
 * Public-facing entry point for validation of raw JSON‑LD provenance records.
 * The service uses the {@link ValidatorChain} SPI to discover pluggable
 * validation plugins (e.g., JSON‑Schema and SHACL) and runs them in the
 * configured order, throwing a {@link SourcelumeValidationException} on
 * non‑conformance. It is intentionally lightweight: the {@code validate}
 * method is static and free of external dependencies beyond the core
 * {@link RecordValidator} service files.
 *
 * <p>The {@link #validate(String)} method is the primary wire for any
 * ingest pipeline that must guarantee validity before persistence.
 * The validation plugins themselves must be present in the classpath
 * for the service to function; if none are discovered, a clear
 * exception is thrown.
 *
 * <p>The design isolates validation from any specific framework or
 * persistence backend. It is wired in the core as a pure JDK utility
 * and can be called from any environment (including the Quarkus
 * runtime's REST layer).
 */
public final class ValidationService {

    private ValidationService() {}

    /**
     * Validates a JSON‑LD provenance record using the pluggable validator
     * chain discovered via the {@link RecordValidator} service loader.
     *
     * @param jsonLd the raw JSON‑LD string (must be syntactically valid JSON‑LD)
     * @throws SourcelumeValidationException if the document is invalid
     *         (non‑conformance) or if no validator plugins could be discovered
     */
    public static void validate(final String jsonLd) {
        validate(ValidatorChain.discover(), jsonLd);
    }

    /**
     * Variant with an explicitly supplied chain (testable without plugins
     * on the classpath; the future ingest service will pass its wired chain).
     *
     * @param chain the validator chain to run
     * @param jsonLd the raw JSON-LD string
     * @throws SourcelumeValidationException on non-conformance
     */
    public static void validate(final ValidatorChain chain, final String jsonLd) {
        final ValidationResult result = chain.validate(jsonLd);
        if (!result.conforms()) {
            final var violations = result.issues().stream()
                    .filter(i -> ValidationIssue.Severity.VIOLATION.equals(i.severity()))
                    .map(i -> String.format("%s at %s: %s", i.validatorId(), i.path(), i.message()))
                    .toList();
            throw new SourcelumeValidationException("Validation failed", violations);
        }
    }
}
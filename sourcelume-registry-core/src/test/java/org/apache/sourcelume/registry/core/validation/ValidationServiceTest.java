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
package org.apache.sourcelume.registry.core.validation;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.apache.sourcelume.registry.common.exception.SourcelumeValidationException;
import org.junit.jupiter.api.Test;

class ValidationServiceTest {

    /** Minimal stub: conforms to anything that is not the word "invalid". */
    private static final RecordValidator STUB = new RecordValidator() {
        @Override
        public String id() {
            return "stub";
        }

        @Override
        public int order() {
            return 0;
        }

        @Override
        public ValidationResult validate(String jsonLd) {
            if (jsonLd != null && jsonLd.contains("invalid")) {
                return ValidationResult.failed(List.of(ValidationIssue.violation("stub", "/", "stub says invalid")));
            }
            return new ValidationResult(true, List.of());
        }
    };

    private static ValidatorChain chain() {
        return ValidatorChain.of(List.of(STUB));
    }

    @Test
    void conformingDocumentPassesQuietly() {
        assertDoesNotThrow(() -> ValidationService.validate(chain(), "{}"));
    }

    @Test
    void nonConformanceThrowsWithFormattedViolations() {
        SourcelumeValidationException e =
                assertThrows(SourcelumeValidationException.class, () -> ValidationService.validate(chain(), "invalid"));
        assertEquals(1, e.getValidationErrors().size());
        assertTrue(e.getValidationErrors().get(0).contains("stub at /"));
        assertTrue(e.getValidationErrors().get(0).contains("stub says invalid"));
    }

    @Test
    void warningsAloneDoNotFailValidation() {
        RecordValidator warner = new RecordValidator() {
            @Override
            public String id() {
                return "warner";
            }

            @Override
            public int order() {
                return 0;
            }

            @Override
            public ValidationResult validate(String jsonLd) {
                return new ValidationResult(
                        true, List.of(ValidationIssue.warning("warner", "/license", "check the scope")));
            }
        };
        assertDoesNotThrow(() -> ValidationService.validate(ValidatorChain.of(List.of(warner)), "{}"));
    }
}

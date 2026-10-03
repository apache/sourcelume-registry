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

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ValidatorChainTest {

    @Test
    void validatorsRunInAscendingOrder() {
        Recorder recorder = new Recorder();
        ValidatorChain chain = ValidatorChain.of(List.of(
                recorder.validator("late", 900, true),
                recorder.validator("early", 100, true)));
        chain.validate("{}");
        assertEquals(List.of("early", "late"), recorder.calls);
    }

    @Test
    void chainStopsAtFirstNonConformingValidator() {
        Recorder recorder = new Recorder();
        ValidatorChain chain = ValidatorChain.of(List.of(
                recorder.validator("failing", 100, false),
                recorder.validator("never-called", 200, true)));
        ValidationResult result = chain.validate("{}");
        assertFalse(result.conforms());
        assertEquals(List.of("failing"), recorder.calls);
    }

    @Test
    void failingValidatorsIssuesAreIncluded() {
        Recorder recorder = new Recorder();
        ValidatorChain chain = ValidatorChain.of(List.of(
                recorder.validator("warns", 100, true, ValidationIssue.warning("warns", "/x", "warned")),
                recorder.validator("failing", 200, false)));
        ValidationResult result = chain.validate("{}");
        assertFalse(result.conforms());
        assertEquals(2, result.issues().size());
        assertEquals(ValidationIssue.Severity.VIOLATION, result.issues().get(1).severity());
    }

    @Test
    void conformsWhenAllConform() {
        ValidatorChain chain = ValidatorChain.of(List.of(
                new MockValidator("a", 100, true),
                new MockValidator("b", 200, true)));
        ValidationResult result = chain.validate("{}");
        assertTrue(result.conforms());
        assertTrue(result.issues().isEmpty());
    }

    @Test
    void emptyChainIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ValidatorChain.of(List.of()));
        assertTrue(e.getMessage().contains("at least one"));
    }

    @Test
    void duplicateIdsAreRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ValidatorChain.of(List.of(
                        new MockValidator("shacl", 100, true),
                        new MockValidator("shacl", 200, true))));
        assertTrue(e.getMessage().contains("shacl"));
    }

    @Test
    void nullDocumentIsRejected() {
        ValidatorChain chain = ValidatorChain.of(List.of(new MockValidator("a", 100, true)));
        assertThrows(NullPointerException.class, () -> chain.validate(null));
    }

    @Test
    void resultIssuesAreImmutable() {
        ValidationResult result = ValidationResult.failed(List.of(
                ValidationIssue.violation("x", "/", "boom")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.issues().add(ValidationIssue.violation("y", "/", "boom")));
    }

    @Test
    void failedWithEmptyIssuesIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ValidationResult.failed(List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ValidationResult.failed(null));
    }

    @Test
    void issuelessNonConformanceYieldsSynthesizedIssue() {
        RecordValidator silent = new RecordValidator() {
            @Override
            public String id() {
                return "silent";
            }

            @Override
            public int order() {
                return 100;
            }

            @Override
            public ValidationResult validate(String jsonLd) {
                return new ValidationResult(false, List.of());
            }
        };
        ValidationResult result = ValidatorChain.of(List.of(silent)).validate("{}");
        assertFalse(result.conforms());
        assertEquals(1, result.issues().size());
        assertEquals("silent", result.issues().get(0).validatorId());
        assertTrue(result.issues().get(0).message().contains("without issues"));
    }

    private static final class Recorder {
        final List<String> calls = new ArrayList<>();

        RecordValidator validator(String id, int order, boolean conforms) {
            return validator(id, order, conforms, null);
        }

        RecordValidator validator(String id, int order, boolean conforms, ValidationIssue extraIssue) {
            return new RecordValidator() {
                @Override
                public String id() {
                    return id;
                }

                @Override
                public int order() {
                    return order;
                }

                @Override
                public ValidationResult validate(String jsonLd) {
                    calls.add(id);
                    if (conforms) {
                        return extraIssue == null
                                ? ValidationResult.OK
                                : new ValidationResult(true, List.of(extraIssue));
                    }
                    return ValidationResult.failed(List.of(
                            ValidationIssue.violation(id, "/", "mock failure")));
                }
            };
        }
    }

    private record MockValidator(String id, int order, boolean conforms) implements RecordValidator {
        @Override
        public ValidationResult validate(String jsonLd) {
            return conforms ? ValidationResult.OK
                    : ValidationResult.failed(List.of(ValidationIssue.violation(id(), "/", "mock failure")));
        }
    }
}

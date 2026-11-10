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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.apache.sourcelume.registry.spi.ValidationIssue;
import org.junit.jupiter.api.Test;

class ValidationIssueTest {

    @Test
    void testHelperMethodsAndSeverities() {
        ValidationIssue violation = ValidationIssue.violation("json-schema", "/license", "license required");
        assertEquals("json-schema", violation.validatorId());
        assertEquals(ValidationIssue.Severity.VIOLATION, violation.severity());
        assertEquals("/license", violation.path());
        assertEquals("license required", violation.message());

        ValidationIssue warning = ValidationIssue.warning("shacl", "/custodyChain", "custody warning");
        assertEquals("shacl", warning.validatorId());
        assertEquals(ValidationIssue.Severity.WARNING, warning.severity());
        assertEquals("/custodyChain", warning.path());
        assertEquals("custody warning", warning.message());

        ValidationIssue info = ValidationIssue.info("custom", "/", "custom info");
        assertEquals("custom", info.validatorId());
        assertEquals(ValidationIssue.Severity.INFO, info.severity());
        assertEquals("/", info.path());
        assertEquals("custom info", info.message());
    }

    @Test
    void testSeverityEnum() {
        assertArrayEquals(
                new ValidationIssue.Severity[] {
                    ValidationIssue.Severity.VIOLATION, ValidationIssue.Severity.WARNING, ValidationIssue.Severity.INFO
                },
                ValidationIssue.Severity.values());
        assertEquals(ValidationIssue.Severity.VIOLATION, ValidationIssue.Severity.valueOf("VIOLATION"));
    }

    @Test
    void testEqualsAndHashCode() {
        ValidationIssue issue1 = ValidationIssue.violation("v1", "/p", "msg");
        ValidationIssue issue2 = ValidationIssue.violation("v1", "/p", "msg");
        ValidationIssue issue3 = ValidationIssue.violation("v2", "/p", "msg");

        assertEquals(issue1, issue2);
        assertNotEquals(issue1, issue3);
        assertEquals(issue1.hashCode(), issue2.hashCode());
    }
}

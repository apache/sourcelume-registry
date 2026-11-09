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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;
import org.apache.sourcelume.registry.spi.ValidationIssue;
import org.junit.jupiter.api.Test;

class ValidationResultTest {

    @Test
    void testOkConstantAndIsOk() {
        ValidationResult ok = ValidationResult.OK;
        assertTrue(ok.conforms());
        assertTrue(ok.issues().isEmpty());
        assertTrue(ok.isOk());
    }

    @Test
    void testFailedFactoryMethod() {
        ValidationIssue issue = ValidationIssue.violation("val1", "/path", "error");
        ValidationResult failed = ValidationResult.failed(List.of(issue));

        assertFalse(failed.conforms());
        assertFalse(failed.isOk());
        assertEquals(1, failed.issues().size());
        assertEquals(issue, failed.issues().get(0));
    }

    @Test
    void testFailedRejectsNullOrEmptyList() {
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.failed(null));
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.failed(Collections.emptyList()));
    }

    @Test
    void testImmutabilityOfIssuesList() {
        ValidationIssue issue = ValidationIssue.violation("val1", "/path", "error");
        ValidationResult result = new ValidationResult(false, List.of(issue));

        assertThrows(UnsupportedOperationException.class, () -> result.issues().add(issue));
    }
}

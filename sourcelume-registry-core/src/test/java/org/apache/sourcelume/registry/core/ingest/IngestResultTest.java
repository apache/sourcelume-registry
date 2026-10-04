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
package org.apache.sourcelume.registry.core.ingest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.core.validation.ValidationIssue;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.junit.jupiter.api.Test;

/**
 * Record semantics of {@link IngestResult}: an ingest outcome is data —
 * an INCOMPLETE record is a normal curation state, not an exception.
 */
class IngestResultTest {

    @Test
    void carriesQualifiedStatusAndValidation() {
        ValidationResult validation = ValidationResult.failed(
                List.of(ValidationIssue.violation("json-schema", "/license", "missing license")));

        IngestResult result = new IngestResult("https://example.org/records/one", RecordStatus.INCOMPLETE, validation);

        assertEquals("https://example.org/records/one", result.qualifiedName());
        assertEquals(RecordStatus.INCOMPLETE, result.status());
        assertEquals(validation, result.validation());
    }

    @Test
    void isValueEqual() {
        IngestResult a = new IngestResult("q", RecordStatus.VALIDATED, ValidationResult.OK);
        IngestResult b = new IngestResult("q", RecordStatus.VALIDATED, ValidationResult.OK);

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void rejectsFailedValidationWithoutIssues() {
        assertThrows(IllegalArgumentException.class, () -> ValidationResult.failed(List.of()));
    }
}

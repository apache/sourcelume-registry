/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
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
package org.apache.sourcelume.registry.common.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProvenanceRecordDtoTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldSerializeAndDeserializeValidRecord() throws Exception {
        ProvenanceRecordDto record = new ProvenanceRecordDto();
        record.setId("https://sourcelume.apache.org/records/rec-001");
        record.setType("ProvenanceRecord");
        record.setIdentifier("https://doi.org/10.1234/dataset-001");
        record.setName("Sample Dataset");
        record.setLicense("https://spdx.org/licenses/Apache-2.0.html");
        record.setCreated("2026-09-13T10:00:00Z");
        record.setAdded("2026-09-13T10:00:00Z");
        record.setContentCreated("2026-09-01T00:00:00Z");
        record.setOrigin("Generated from synthetic benchmark suite");
        record.setCreator(List.of(new CreatorDto("schema:Organization", "Apache Sourcelume", "originator")));
        record.setCustodyChain(List.of(new CustodyEventDto("https://sourcelume.apache.org/agents/agent-1", "ingested", "2026-09-13T10:00:00Z")));

        String json = mapper.writeValueAsString(record);
        assertNotNull(json);

        ProvenanceRecordDto deserialized = mapper.readValue(json, ProvenanceRecordDto.class);
        assertEquals(record.getId(), deserialized.getId());
        assertEquals(record.getName(), deserialized.getName());
        assertEquals(1, deserialized.getCreator().size());
        assertEquals(1, deserialized.getCustodyChain().size());

        Set<ConstraintViolation<ProvenanceRecordDto>> violations = validator.validate(deserialized);
        assertTrue(violations.isEmpty(), "Should have no validation violations: " + violations);
    }

    @Test
    void shouldDetectValidationViolationsWhenRequiredFieldsMissing() {
        ProvenanceRecordDto record = new ProvenanceRecordDto();
        Set<ConstraintViolation<ProvenanceRecordDto>> violations = validator.validate(record);
        assertFalse(violations.isEmpty());
        assertTrue(violations.size() >= 5);
    }
}

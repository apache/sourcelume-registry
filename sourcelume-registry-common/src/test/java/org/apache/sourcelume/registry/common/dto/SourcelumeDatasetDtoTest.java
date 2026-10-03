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
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for the ingest lifecycle fields on
 * {@link SourcelumeDatasetDto}. The fields are written by the ingest
 * pipeline (API and worker), not by client input, so they carry no bean
 * validation constraints of their own.
 */
class SourcelumeDatasetDtoTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void serializesTheLifecycleFields() throws IOException {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName("https://example.org/records/one");
        dto.setName("one");
        dto.setRecordStatus(RecordStatus.PENDING);
        dto.setRawJsonLd("{\"id\": \"https://example.org/records/one\"}");
        dto.setValidationIssues("[{\"message\": \"missing license\"}]");

        String json = objectMapper.writeValueAsString(dto);

        assertTrue(json.contains("\"recordStatus\":\"PENDING\""));
        assertTrue(json.contains("\"rawJsonLd\":"));
        assertTrue(json.contains("\"validationIssues\":"));
    }

    @Test
    void deserializesTheLifecycleFields() throws IOException {
        String json = "{\"qualifiedName\": \"https://example.org/records/one\","
                + " \"name\": \"one\", \"recordStatus\": \"INCOMPLETE\","
                + " \"rawJsonLd\": \"{}\", \"validationIssues\": \"[]\"}";

        SourcelumeDatasetDto dto = objectMapper.readValue(json, SourcelumeDatasetDto.class);

        assertEquals(RecordStatus.INCOMPLETE, dto.getRecordStatus());
        assertEquals("{}", dto.getRawJsonLd());
        assertEquals("[]", dto.getValidationIssues());
    }

    @Test
    void omitsNullLifecycleFields() throws IOException {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName("https://example.org/records/two");
        dto.setName("two");

        String json = objectMapper.writeValueAsString(dto);

        assertFalse(json.contains("recordStatus"));
        assertFalse(json.contains("rawJsonLd"));
        assertFalse(json.contains("validationIssues"));
    }

    @Test
    void unknownRecordStatusValueIsRejected() {
        String json = "{\"qualifiedName\": \"q\", \"name\": \"n\", \"recordStatus\": \"SOMEDAY\"}";
        assertThrows(IOException.class, () -> objectMapper.readValue(json, SourcelumeDatasetDto.class));
    }
}

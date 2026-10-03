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

package org.apache.sourcelume.registry.common.mapper;

import org.apache.sourcelume.registry.common.dto.ProvenanceRecordDto;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.common.exception.SourcelumeValidationException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for the JSON-LD to DTO mapping. The mapper is intentionally a
 * plain Jackson binding: validation runs before mapping, and the
 * validators enforce the canonical record shape, so no JSON-LD expansion
 * is required here.
 */
class ProvenanceRecordMapperTest {

    @Test
    void mapsAValidRecord() throws IOException {
        String raw = readFixture("/records/valid-minimal.jsonld");

        ProvenanceRecordDto record = ProvenanceRecordMapper.map(raw);

        assertEquals("https://sourcelume.apache.org/records/minimal-example", record.getId());
        assertEquals("ProvenanceRecord", record.getType());
        assertEquals("https://example.org/datasets/minimal-example", record.getIdentifier());
        assertEquals("Minimal example record", record.getName());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", record.getLicense());
        assertEquals(1, record.getCreator().size());
        assertEquals("Example Organization", record.getCreator().get(0).getName());
        assertEquals(1, record.getCustodyChain().size());
        assertEquals("https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld", record.getContext());
    }

    @Test
    void rejectsMalformedJson() {
        SourcelumeValidationException exception =
                assertThrows(SourcelumeValidationException.class, () -> ProvenanceRecordMapper.map("not json"));

        assertNotNull(exception.getMessage());
    }

    @Test
    void extractsTheRecordIdLeniently() {
        String raw = "{\"@context\": \"x\", \"id\": \"https://example.org/records/one\"}";
        assertEquals("https://example.org/records/one", ProvenanceRecordMapper.extractRecordId(raw));
    }

    @Test
    void extractRecordIdRejectsMissingId() {
        assertThrows(IllegalArgumentException.class,
                () -> ProvenanceRecordMapper.extractRecordId("{\"name\": \"no id here\"}"));
    }

    @Test
    void extractRecordIdRejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> ProvenanceRecordMapper.extractRecordId("{\"id\": \"   \"}"));
    }

    @Test
    void extractRecordIdRejectsMalformedJson() {
        assertThrows(IllegalArgumentException.class, () -> ProvenanceRecordMapper.extractRecordId("{nope"));
    }

    @Test
    void mapsARecordToTheDatasetEntity() throws IOException {
        ProvenanceRecordDto record = ProvenanceRecordMapper.map(readFixture("/records/valid-minimal.jsonld"));

        SourcelumeDatasetDto entity = ProvenanceRecordMapper.toDatasetEntity(record);

        assertEquals(record.getId(), entity.getQualifiedName());
        assertEquals(record.getName(), entity.getName());
        assertEquals(record.getLicense(), entity.getLicenseId());
        assertEquals(record.getIdentifier(), entity.getSourceUri());
    }

    private String readFixture(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

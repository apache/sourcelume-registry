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
package org.apache.sourcelume.registry.common.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.apache.sourcelume.registry.common.dto.SourcelumeModelDto;
import org.apache.sourcelume.registry.common.dto.TrainingProvenanceRecordDto;
import org.apache.sourcelume.registry.common.exception.SourcelumeValidationException;
import org.junit.jupiter.api.Test;

/**
 * Tests for the training provenance record mapping — same discipline as
 * {@link ProvenanceRecordMapperTest}: plain Jackson binding, validation
 * before mapping, record IRI becomes the qualifiedName.
 */
class TrainingProvenanceRecordMapperTest {

    @Test
    void mapsAValidRecord() throws IOException {
        String raw = readFixture("/records/valid-training-minimal.jsonld");

        TrainingProvenanceRecordDto record = TrainingProvenanceRecordMapper.map(raw);

        assertEquals("https://sourcelume.apache.org/records/training-minimal", record.getId());
        assertEquals("TrainingProvenanceRecord", record.getType());
        assertEquals("https://example.org/models/minimal-model", record.getIdentifier());
        assertEquals("Minimal model", record.getName());
        assertEquals("v1", record.getVersion());
        assertEquals("complete", record.getDisclosure());
        assertEquals(1, record.getCreator().size());
        assertEquals(1, record.getTrainingData().size());
        assertEquals(
                "https://sourcelume.apache.org/records/minimal-example",
                record.getTrainingData().get(0).getDataset());
    }

    @Test
    void mapsCreatorIdAndDistribution() throws IOException {
        String raw = readFixture("/records/valid-training-distribution.jsonld");

        TrainingProvenanceRecordDto record = TrainingProvenanceRecordMapper.map(raw);

        assertEquals("did:web:example.org", record.getCreator().get(0).getId());
        assertEquals(1, record.getDistribution().size());
        assertEquals(
                "https://example.org/models/distribution-model/weights.safetensors",
                record.getDistribution().get(0).getContentUrl());
        assertEquals(
                "9b71d224bd62f3785d96d46ad3ea3d73319bfbc2890caadae2dff72519673ca7",
                record.getDistribution().get(0).getSha256());
    }

    @Test
    void mapsARecordToTheModelEntity() throws IOException {
        TrainingProvenanceRecordDto record =
                TrainingProvenanceRecordMapper.map(readFixture("/records/valid-training-minimal.jsonld"));

        SourcelumeModelDto entity = TrainingProvenanceRecordMapper.toModelEntity(record);

        assertEquals(SourcelumeModelDto.TYPE_NAME, entity.getTypeName());
        assertEquals(record.getId(), entity.getQualifiedName());
        assertEquals(record.getName(), entity.getName());
        assertEquals(record.getIdentifier(), entity.getModelUri());
        assertEquals(record.getVersion(), entity.getModelVersion());
        assertEquals(record.getDisclosure(), entity.getDisclosure());
    }

    @Test
    void rejectsMalformedJson() {
        assertThrows(SourcelumeValidationException.class, () -> TrainingProvenanceRecordMapper.map("not json"));
    }

    @Test
    void mapRejectsNullOrBlank() {
        assertThrows(IllegalArgumentException.class, () -> TrainingProvenanceRecordMapper.map(null));
        assertThrows(IllegalArgumentException.class, () -> TrainingProvenanceRecordMapper.map("   "));
    }

    @Test
    void toModelEntityRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> TrainingProvenanceRecordMapper.toModelEntity(null));
    }

    private String readFixture(String resource) throws IOException {
        try (InputStream in = getClass().getResourceAsStream(resource)) {
            assertNotNull(in, "fixture not found: " + resource);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}

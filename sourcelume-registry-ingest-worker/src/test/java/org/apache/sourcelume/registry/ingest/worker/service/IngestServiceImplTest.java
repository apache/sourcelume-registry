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
package org.apache.sourcelume.registry.ingest.worker.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Tests the ingest pipeline with the real validator chain (both plugins
 * from the classpath, as discovered in production) and an in-memory
 * fake of the {@link AtlasAdapter}. Valid record → ACTIVE upsert with
 * the mapped provenance attributes; invalid record → INCOMPLETE upsert
 * with the issues stored as JSON.
 */
class IngestServiceImplTest {

    private static String validRecord;
    private static String invalidRecord;

    private final RecordingAtlasAdapter adapter = new RecordingAtlasAdapter();
    private final IngestServiceImpl service =
            new IngestServiceImpl(ValidatorChain.discover(), adapter, new ObjectMapper());

    @BeforeAll
    static void loadFixtures() {
        validRecord = fixture("valid-minimal.jsonld");
        invalidRecord = fixture("invalid-missing-license.jsonld");
    }

    @Test
    void conformingRecordIsPromotedToActiveWithMappedAttributes() {
        SourcelumeDatasetDto pending = pending("https://sourcelume.apache.org/records/minimal-example", validRecord);
        adapter.given(pending);

        IngestResult result = service.process(pending);

        assertEquals(RecordStatus.ACTIVE, result.status());
        assertEquals("https://sourcelume.apache.org/records/minimal-example", result.qualifiedName());
        assertTrue(result.validation().conforms());

        SourcelumeDatasetDto upserted = adapter.upserted;
        assertEquals(RecordStatus.ACTIVE, upserted.getRecordStatus());
        assertEquals("Minimal example record", upserted.getName());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", upserted.getLicenseId());
        assertEquals("https://example.org/datasets/minimal-example", upserted.getSourceUri());
        assertEquals(validRecord, upserted.getRawJsonLd(), "raw document must stay byte-identical");
        assertEquals(
                SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES,
                upserted.getValidationIssues(),
                "an ACTIVE promotion must explicitly clear a previous INCOMPLETE verdict");
    }

    @Test
    void nonConformingRecordBecomesIncompleteWithStoredIssues() throws Exception {
        SourcelumeDatasetDto pending =
                pending("https://sourcelume.apache.org/records/invalid-missing-license", invalidRecord);
        adapter.given(pending);

        IngestResult result = service.process(pending);

        assertEquals(RecordStatus.INCOMPLETE, result.status());
        assertTrue(!result.validation().conforms());

        SourcelumeDatasetDto upserted = adapter.upserted;
        assertEquals(RecordStatus.INCOMPLETE, upserted.getRecordStatus());
        assertEquals("https://sourcelume.apache.org/records/invalid-missing-license", upserted.getQualifiedName());
        assertEquals(invalidRecord, upserted.getRawJsonLd());
        assertNull(upserted.getName(), "nothing is mapped for an INCOMPLETE record");
        assertNull(upserted.getLicenseId());

        JsonNode issues = new ObjectMapper().readTree(upserted.getValidationIssues());
        assertTrue(issues.isArray(), "stored issues are a JSON array");
        assertTrue(issues.size() >= 1, "the missing license must be reported");
        assertTrue(issues.get(0).has("validatorId"), "issue JSON carries the validator id");
        assertTrue(issues.get(0).has("message"), "issue JSON carries the message");
    }

    @Test
    void guidTravelsWithThePromotion() {
        SourcelumeDatasetDto pending = pending("https://sourcelume.apache.org/records/minimal-example", validRecord);
        pending.setGuid("existing-guid");
        adapter.given(pending);

        service.process(pending);

        assertEquals("existing-guid", adapter.upserted.getGuid());
    }

    @Test
    void aRecordCorrectedAfterThePollIsSkippedNotOverwritten() {
        // The poll saw the old document; the store now holds the corrected
        // resubmission. The worker must not stamp a verdict from the stale
        // snapshot over the fresh one.
        SourcelumeDatasetDto polled = pending("https://sourcelume.apache.org/records/minimal-example", "stale");
        SourcelumeDatasetDto corrected = pending("https://sourcelume.apache.org/records/minimal-example", validRecord);
        adapter.given(corrected);

        IngestResult result = service.process(polled);

        assertEquals(RecordStatus.PENDING, result.status(), "the record keeps its PENDING state");
        assertNull(result.validation(), "no verdict is derived from a stale snapshot");
        assertNull(adapter.upserted, "nothing is written for a skipped record");
        assertEquals(
                validRecord,
                adapter.store
                        .get("https://sourcelume.apache.org/records/minimal-example")
                        .getRawJsonLd(),
                "the corrected submission stays untouched");
    }

    @Test
    void aRecordThatLeftPendingIsSkipped() {
        SourcelumeDatasetDto polled = pending("https://sourcelume.apache.org/records/minimal-example", validRecord);
        SourcelumeDatasetDto active = pending("https://sourcelume.apache.org/records/minimal-example", validRecord);
        active.setRecordStatus(RecordStatus.ACTIVE);
        adapter.given(active);

        IngestResult result = service.process(polled);

        assertEquals(RecordStatus.PENDING, result.status());
        assertNull(result.validation());
        assertNull(adapter.upserted);
    }

    private static SourcelumeDatasetDto pending(String qualifiedName, String rawJsonLd) {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName(qualifiedName);
        dto.setRecordStatus(RecordStatus.PENDING);
        dto.setRawJsonLd(rawJsonLd);
        return dto;
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/records/" + name), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    /**
     * In-memory stand-in for the Atlas backend: a qualified-name-keyed
     * store (so the service's re-read guard sees real current state) plus
     * a capture of the last upsert.
     */
    static class RecordingAtlasAdapter implements AtlasAdapter {

        final java.util.Map<String, SourcelumeDatasetDto> store = new java.util.HashMap<>();
        SourcelumeDatasetDto upserted;

        void given(SourcelumeDatasetDto dto) {
            store.put(dto.getQualifiedName(), dto);
        }

        @Override
        public boolean isServerReady() {
            return true;
        }

        @Override
        public TypeDefinitionModel loadTypeDefs(String resourcePath) {
            return null;
        }

        @Override
        public TypeDefinitionModel registerOrUpdateTypeDefs(TypeDefinitionModel typeDefs) {
            return null;
        }

        @Override
        public String createOrUpdateDatasetEntity(SourcelumeDatasetDto dataset) {
            this.upserted = dataset;
            store.put(dataset.getQualifiedName(), dataset);
            return dataset.getGuid() != null ? dataset.getGuid() : "guid-1";
        }

        @Override
        public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
            return store.get(qualifiedName);
        }

        @Override
        public List<SourcelumeDatasetDto> findDatasetsByStatus(RecordStatus status, int limit) {
            return store.values().stream()
                    .filter(dto -> status == dto.getRecordStatus())
                    .limit(limit)
                    .toList();
        }
    }
}

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
package org.apache.sourcelume.registry.runtime.quarkus.ingest.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
 * fake of the {@link AtlasAdapter}. Valid record -> validated upsert with
 * the mapped provenance attributes; invalid record -> INCOMPLETE upsert
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
    void conformingRecordAnswersValidatedWithMappedAttributes() {
        SourcelumeDatasetDto submitted =
                submitted("https://sourcelume.apache.org/records/minimal-example", validRecord);

        IngestResult result = service.process(submitted);

        assertEquals(RecordStatus.VALIDATED, result.status());
        assertEquals("https://sourcelume.apache.org/records/minimal-example", result.qualifiedName());
        assertTrue(result.validation().conforms());

        SourcelumeDatasetDto upserted = adapter.upserted;
        assertEquals(RecordStatus.VALIDATED, upserted.getRecordStatus());
        assertEquals("Minimal example record", upserted.getName());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", upserted.getLicenseId());
        assertEquals("https://example.org/datasets/minimal-example", upserted.getSourceUri());
        assertEquals(validRecord, upserted.getRawJsonLd(), "raw document must stay byte-identical");
        assertEquals(
                SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES,
                upserted.getValidationIssues(),
                "a validated promotion must explicitly clear a previous INCOMPLETE verdict");
        assertVerdictContext(upserted, validRecord);
    }

    /** sha256 travels with the submission; the chain and the timestamp are stamped by the pipeline. */
    private static void assertVerdictContext(SourcelumeDatasetDto upserted, String rawDocument) {
        assertEquals(
                Digests.sha256Hex(rawDocument.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                upserted.getSha256(),
                "the digest of the received bytes travels with the verdict");
        assertTrue(
                upserted.getValidatedBy().contains("json"),
                "the chain ids name the validators that judged the record: " + upserted.getValidatedBy());
        assertNotNull(upserted.getValidatedAt(), "the verdict carries its timestamp");
    }

    @Test
    void nonConformingRecordBecomesIncompleteWithStoredIssues() throws Exception {
        SourcelumeDatasetDto submitted =
                submitted("https://sourcelume.apache.org/records/invalid-missing-license", invalidRecord);

        IngestResult result = service.process(submitted);

        assertEquals(RecordStatus.INCOMPLETE, result.status());
        assertTrue(!result.validation().conforms());

        SourcelumeDatasetDto upserted = adapter.upserted;
        assertEquals(RecordStatus.INCOMPLETE, upserted.getRecordStatus());
        assertEquals("https://sourcelume.apache.org/records/invalid-missing-license", upserted.getQualifiedName());
        assertEquals(invalidRecord, upserted.getRawJsonLd());
        assertNull(upserted.getName(), "nothing is mapped for an INCOMPLETE record");
        assertNull(upserted.getLicenseId());

        assertVerdictContext(upserted, invalidRecord);

        JsonNode issues = new ObjectMapper().readTree(upserted.getValidationIssues());
        assertTrue(issues.isArray(), "stored issues are a JSON array");
        assertTrue(issues.size() >= 1, "the missing license must be reported");
        assertTrue(issues.get(0).has("validatorId"), "issue JSON carries the validator id");
        assertTrue(issues.get(0).has("message"), "issue JSON carries the message");
    }

    @Test
    void stampsTheVerdictTimestampFromTheInjectedClock() {
        java.time.Clock fixed =
                java.time.Clock.fixed(java.time.Instant.parse("2026-10-04T12:00:00Z"), java.time.ZoneOffset.UTC);
        IngestServiceImpl clocked =
                new IngestServiceImpl(ValidatorChain.discover(), adapter, new ObjectMapper(), fixed);
        SourcelumeDatasetDto submitted =
                submitted("https://sourcelume.apache.org/records/minimal-example", validRecord);

        IngestResult result = clocked.process(submitted);

        assertEquals(
                "2026-10-04T12:00:00Z",
                result.validatedAt(),
                "the timestamp comes from the injected clock, not the wall clock");
    }

    @Test
    void guidTravelsWithTheVerdict() {
        SourcelumeDatasetDto submitted =
                submitted("https://sourcelume.apache.org/records/minimal-example", validRecord);
        submitted.setGuid("existing-guid");

        service.process(submitted);

        assertEquals("existing-guid", adapter.upserted.getGuid());
    }

    private static SourcelumeDatasetDto submitted(String qualifiedName, String rawJsonLd) {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName(qualifiedName);
        dto.setRawJsonLd(rawJsonLd);
        // the digest the REST layer computes over the received bytes
        dto.setSha256(Digests.sha256Hex(rawJsonLd.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        // no status: the pipeline assigns the verdict — submissions carry none.
        return dto;
    }

    private static String fixture(String name) {
        try {
            return Files.readString(Path.of("src/test/resources/records/" + name), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read fixture " + name, e);
        }
    }

    /** In-memory stand-in for the Atlas backend: keeps the last upsert. */
    static class RecordingAtlasAdapter implements AtlasAdapter {

        SourcelumeDatasetDto upserted;

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
            return dataset.getGuid() != null ? dataset.getGuid() : "guid-1";
        }

        @Override
        public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
            return null;
        }

        @Override
        public List<SourcelumeDatasetDto> findDatasetsByStatus(RecordStatus status, int limit) {
            return List.of();
        }
    }
}

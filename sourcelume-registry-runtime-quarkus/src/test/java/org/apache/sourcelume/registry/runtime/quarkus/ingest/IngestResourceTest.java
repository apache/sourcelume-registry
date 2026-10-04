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

package org.apache.sourcelume.registry.runtime.quarkus.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.runtime.quarkus.ingest.service.IngestServiceImpl;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Semantics of the ingest endpoints, with the real ingest pipeline
 * (the real plugin chain from the classpath) and an in-memory Atlas
 * fake: POST validates inline and answers with the record's verdict —
 * 201 for a new record, 200 for a corrected resubmission of an
 * INCOMPLETE record, 409 for anything already VALIDATED.
 */
class IngestResourceTest {

    private static final String RECORD_ID = "https://sourcelume.apache.org/records/ingest-test";
    private static final String VALID_RECORD = """
            {
              "@context": "https://sourcelume.apache.org/context/0.0.1/sourcelume.jsonld",
              "id": "https://sourcelume.apache.org/records/ingest-test",
              "type": "ProvenanceRecord",
              "identifier": "https://example.org/datasets/ingest-test",
              "name": "Ingest endpoint test record",
              "license": "https://www.apache.org/licenses/LICENSE-2.0",
              "creator": [ { "type": "schema:Organization", "name": "Example Organization" } ],
              "created": "2026-09-08T00:00:00Z",
              "added": "2026-09-08T00:00:00Z",
              "contentCreated": "2026-09-08T00:00:00Z",
              "origin": "Test record for the ingest endpoint.",
              "custodyChain": [ { "agent": "https://example.org/", "action": "created",
                                  "startTime": "2026-09-08T00:00:00Z" } ]
            }""";

    private static final String INVALID_RECORD = VALID_RECORD.replace(
            "\"license\": \"https://www.apache.org/licenses/LICENSE-2.0\",", "");

    private final FakeAtlasAdapter fake = new FakeAtlasAdapter();
    private final IngestResource resource = new IngestResource(fake,
            new IngestServiceImpl(ValidatorChain.discover(), fake, new ObjectMapper()), new ObjectMapper());

    @BeforeEach
    void resetStore() {
        fake.store.clear();
    }

    @Test
    void validRecordAnswersWithItsVerdict() {
        var response = resource.submit(VALID_RECORD);

        assertEquals(201, response.getStatus());
        assertEquals(URI.create("/records/https%3A%2F%2Fsourcelume.apache.org%2Frecords%2Fingest-test"),
                response.getLocation());

        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertNull(body.validationIssues(), "a validated record reports no issues");

        SourcelumeDatasetDto stored = fake.store.get(RECORD_ID);
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals("Ingest endpoint test record", stored.getName(),
                "the mapped name replaces the placeholder");
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", stored.getLicenseId());
        assertEquals(VALID_RECORD, stored.getRawJsonLd(), "the stored document must be byte-identical");
    }

    @Test
    void invalidRecordBecomesIncompleteAndReportsItsIssues() {
        var response = resource.submit(INVALID_RECORD);

        assertEquals(201, response.getStatus());
        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertTrue(body.validationIssues().toLowerCase().contains("license"),
                "the issues come with the response — no polling needed");

        SourcelumeDatasetDto stored = fake.store.get(RECORD_ID);
        assertEquals(RecordStatus.INCOMPLETE, stored.getRecordStatus());
        assertEquals("ingest-test", stored.getName(),
                "Atlas requires a name — the placeholder stays on an INCOMPLETE record");
        assertTrue(stored.getValidationIssues() != null
                        && !SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES.equals(stored.getValidationIssues()),
                "the pipeline stores the real failure report, not the sentinel");
    }

    @Test
    void correctedResubmissionOfAnIncompleteRecordAnswersWithItsVerdict() {
        resource.submit(INVALID_RECORD);
        fake.store.get(RECORD_ID).setValidationIssues("[{\"message\": \"missing license\"}]");

        var response = resource.submit(VALID_RECORD);

        assertEquals(200, response.getStatus());
        IngestResource.IngestResponse body = (IngestResource.IngestResponse) response.getEntity();
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertNull(body.validationIssues(), "the old failure report does not surface");

        SourcelumeDatasetDto stored = fake.store.get(RECORD_ID);
        assertEquals(RecordStatus.VALIDATED, stored.getRecordStatus());
        assertEquals(SourcelumeDatasetDto.CLEARED_VALIDATION_ISSUES, stored.getValidationIssues(),
                "the resubmission explicitly clears the old issues — see CLEARED_VALIDATION_ISSUES");
    }

    @Test
    void validatedRecordRejectsDuplicateSubmit() {
        resource.submit(VALID_RECORD);

        var response = resource.submit(VALID_RECORD);

        assertEquals(409, response.getStatus());
        IngestResource.ConflictResponse body = (IngestResource.ConflictResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
    }

    @Test
    void recordWithoutAnIdIsRejected() {
        var response = resource.submit("{\"name\": \"no id here\"}");

        assertEquals(400, response.getStatus());
    }

    @Test
    void unparseableBodyIsRejected() {
        var response = resource.submit("this is not JSON");

        assertEquals(400, response.getStatus());
    }

    @Test
    void statusOfAnUnknownRecordIs404() {
        var response = resource.status("https://sourcelume.apache.org/records/unknown");

        assertEquals(404, response.getStatus());
    }

    @Test
    void statusReturnsRecordStateAndIssues() {
        resource.submit(INVALID_RECORD);
        fake.store.get(RECORD_ID).setValidationIssues("[{\"message\": \"missing license\"}]");

        var response = resource.status(RECORD_ID);

        assertEquals(200, response.getStatus());
        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertEquals("[{\"message\": \"missing license\"}]", body.validationIssues());
    }

    @Test
    void statusOfAValidatedRecordCarriesTheMappedAttributes() {
        resource.submit(VALID_RECORD);

        var response = resource.status(RECORD_ID);

        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RecordStatus.VALIDATED, body.recordStatus());
        assertEquals("Ingest endpoint test record", body.name());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", body.licenseId());
        assertEquals("https://example.org/datasets/ingest-test", body.sourceUri());
        assertNull(body.validationIssues(), "the cleared sentinel never surfaces");
    }

    /** In-memory stand-in for the Atlas backend: a map keyed by qualifiedName. */
    static class FakeAtlasAdapter implements AtlasAdapter {

        final java.util.Map<String, SourcelumeDatasetDto> store = new java.util.HashMap<>();

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

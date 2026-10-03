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

import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test for the ingest endpoints against an in-memory fake of the
 * {@link AtlasAdapter} — no HTTP layer, no Atlas, no CDI: the resource is
 * plain logic around the adapter, so the JAX-RS response objects are
 * asserted directly. The fake keeps the stored rawJsonLd byte-identical,
 * which the API contract requires (later signature checks must see exactly
 * what was submitted).
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
              "created": "2026-10-03T00:00:00Z",
              "added": "2026-10-03T00:00:00Z",
              "contentCreated": "2026-10-03T00:00:00Z",
              "origin": "A minimal conformant ProvenanceRecord.",
              "custodyChain": [ { "agent": "https://example.org/", "action": "created",
                                  "startTime": "2026-10-03T00:00:00Z" } ]
            }
            """;

    private final FakeAtlasAdapter fake = new FakeAtlasAdapter();
    private final IngestResource resource = new IngestResource(fake);

    @BeforeEach
    void resetFake() {
        fake.store.clear();
    }

    @Test
    void submitStoresAPendingRecordWithTheOriginalBytes() {
        var response = resource.submit(VALID_RECORD);

        assertEquals(202, response.getStatus());
        assertEquals(URI.create("/records/https%3A%2F%2Fsourcelume.apache.org%2Frecords%2Fingest-test"),
                response.getLocation());

        IngestResource.AcceptedResponse body = (IngestResource.AcceptedResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.PENDING, body.recordStatus());

        SourcelumeDatasetDto stored = fake.store.get(RECORD_ID);
        assertEquals(RecordStatus.PENDING, stored.getRecordStatus());
        assertEquals(VALID_RECORD, stored.getRawJsonLd(), "the stored record must be byte-identical");
        assertNull(stored.getValidationIssues());
    }

    @Test
    void submittingTheSameIdAgainIsRejectedWhilePendingOrActive() {
        resource.submit(VALID_RECORD);

        var response = resource.submit(VALID_RECORD);

        assertEquals(409, response.getStatus());
        IngestResource.ConflictResponse body = (IngestResource.ConflictResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.PENDING, body.recordStatus());
    }

    @Test
    void activeRecordsAreAlsoRejected() {
        fake.store.put(RECORD_ID, dataset(RECORD_ID, RecordStatus.ACTIVE));

        var response = resource.submit(VALID_RECORD);

        assertEquals(409, response.getStatus());
        assertEquals(RecordStatus.ACTIVE,
                ((IngestResource.ConflictResponse) response.getEntity()).recordStatus());
    }

    @Test
    void incompleteRecordAcceptsACorrectedResubmission() {
        SourcelumeDatasetDto incomplete = dataset(RECORD_ID, RecordStatus.INCOMPLETE);
        incomplete.setValidationIssues("[{\"message\": \"missing license\"}]");
        fake.store.put(RECORD_ID, incomplete);

        var response = resource.submit(VALID_RECORD);

        assertEquals(202, response.getStatus());

        SourcelumeDatasetDto stored = fake.store.get(RECORD_ID);
        assertEquals(RecordStatus.PENDING, stored.getRecordStatus());
        assertEquals(VALID_RECORD, stored.getRawJsonLd());
        assertNull(stored.getValidationIssues(), "issues are cleared on resubmission");
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
        SourcelumeDatasetDto incomplete = dataset(RECORD_ID, RecordStatus.INCOMPLETE);
        incomplete.setValidationIssues("[{\"message\": \"missing license\"}]");
        fake.store.put(RECORD_ID, incomplete);

        var response = resource.status(RECORD_ID);

        assertEquals(200, response.getStatus());
        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RECORD_ID, body.qualifiedName());
        assertEquals(RecordStatus.INCOMPLETE, body.recordStatus());
        assertEquals("[{\"message\": \"missing license\"}]", body.validationIssues());
    }

    @Test
    void statusOfAnActiveRecordCarriesTheMappedAttributes() {
        SourcelumeDatasetDto active = dataset(RECORD_ID, RecordStatus.ACTIVE);
        active.setName("Ingest endpoint test record");
        active.setLicenseId("https://www.apache.org/licenses/LICENSE-2.0");
        active.setSourceUri("https://example.org/datasets/ingest-test");
        fake.store.put(RECORD_ID, active);

        var response = resource.status(RECORD_ID);

        assertEquals(200, response.getStatus());
        IngestResource.RecordResponse body = (IngestResource.RecordResponse) response.getEntity();
        assertEquals(RecordStatus.ACTIVE, body.recordStatus());
        assertEquals("https://example.org/datasets/ingest-test", body.sourceUri());
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0", body.licenseId());
    }

    private static SourcelumeDatasetDto dataset(String qualifiedName, RecordStatus status) {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName(qualifiedName);
        dto.setRecordStatus(status);
        return dto;
    }

    /** In-memory stand-in for the Atlas backend: a map keyed by qualifiedName. */
    static class FakeAtlasAdapter implements AtlasAdapter {

        final Map<String, SourcelumeDatasetDto> store = new HashMap<>();

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
            SourcelumeDatasetDto existing = store.get(dataset.getQualifiedName());
            if (existing != null && existing.getGuid() != null) {
                dataset.setGuid(existing.getGuid());
            } else {
                dataset.setGuid("guid-" + (store.size() + 1));
            }
            store.put(dataset.getQualifiedName(), dataset);
            return dataset.getGuid();
        }

        @Override
        public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
            return store.get(qualifiedName);
        }

        @Override
        public List<SourcelumeDatasetDto> findDatasetsByStatus(RecordStatus status, int limit) {
            return store.values().stream()
                    .filter(d -> status == d.getRecordStatus())
                    .limit(limit)
                    .collect(Collectors.toList());
        }
    }
}

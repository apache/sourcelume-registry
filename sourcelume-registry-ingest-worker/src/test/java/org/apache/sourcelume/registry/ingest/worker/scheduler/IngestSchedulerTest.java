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

package org.apache.sourcelume.registry.ingest.worker.scheduler;

import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.ValidationResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests the ingest loop against fakes: every PENDING record the poll
 * finds is handed to the ingest service; an empty poll is a no-op; a
 * failing record does not take the batch (or the scheduler) down — it
 * simply stays PENDING and is retried on the next tick.
 */
class IngestSchedulerTest {

    @Test
    void processesEveryPendingRecordThePollFinds() {
        FakeAtlasAdapter atlas = new FakeAtlasAdapter(List.of(
                pending("https://example.org/records/one"), pending("https://example.org/records/two")));
        RecordingIngestService ingest = new RecordingIngestService();
        IngestScheduler scheduler = new IngestScheduler(atlas, ingest, 20);

        scheduler.poll();

        assertEquals(List.of("https://example.org/records/one", "https://example.org/records/two"),
                ingest.processed);
        assertEquals(RecordStatus.PENDING, atlas.polledWithStatus);
        assertEquals(20, atlas.polledWithLimit);
    }

    @Test
    void emptyPollIsANoop() {
        RecordingIngestService ingest = new RecordingIngestService();
        IngestScheduler scheduler = new IngestScheduler(new FakeAtlasAdapter(List.of()), ingest, 20);

        scheduler.poll();

        assertEquals(List.of(), ingest.processed);
    }

    @Test
    void failingRecordDoesNotBlockTheRestOfTheBatch() {
        FakeAtlasAdapter atlas = new FakeAtlasAdapter(List.of(
                pending("https://example.org/records/failing"), pending("https://example.org/records/healthy")));
        RecordingIngestService ingest = new RecordingIngestService();
        ingest.failFor.add("https://example.org/records/failing");
        IngestScheduler scheduler = new IngestScheduler(atlas, ingest, 20);

        scheduler.poll();

        assertEquals(List.of("https://example.org/records/failing", "https://example.org/records/healthy"),
                ingest.processed, "the failing record does not stop the batch");
    }

    private static SourcelumeDatasetDto pending(String qualifiedName) {
        SourcelumeDatasetDto dto = new SourcelumeDatasetDto();
        dto.setQualifiedName(qualifiedName);
        dto.setRecordStatus(RecordStatus.PENDING);
        return dto;
    }

    /** Fake store: hands out a fixed PENDING list. */
    static class FakeAtlasAdapter implements AtlasAdapter {

        private final List<SourcelumeDatasetDto> pending;
        RecordStatus polledWithStatus;
        int polledWithLimit;

        FakeAtlasAdapter(List<SourcelumeDatasetDto> pending) {
            this.pending = pending;
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
            return "guid-1";
        }

        @Override
        public SourcelumeDatasetDto getDatasetByQualifiedName(String qualifiedName) {
            return null;
        }

        @Override
        public List<SourcelumeDatasetDto> findDatasetsByStatus(RecordStatus status, int limit) {
            this.polledWithStatus = status;
            this.polledWithLimit = limit;
            return pending.stream().limit(limit).toList();
        }
    }

    /** Records every processed record, optionally failing for chosen ones. */
    static class RecordingIngestService implements IngestService {

        final List<String> processed = new ArrayList<>();
        final List<String> failFor = new ArrayList<>();

        @Override
        public IngestResult process(SourcelumeDatasetDto pendingDataset) {
            processed.add(pendingDataset.getQualifiedName());
            if (failFor.contains(pendingDataset.getQualifiedName())) {
                throw new IllegalStateException("backend unavailable — simulated");
            }
            return new IngestResult(pendingDataset.getQualifiedName(), RecordStatus.ACTIVE,
                    ValidationResult.OK);
        }
    }
}

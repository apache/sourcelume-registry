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

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.common.dto.RecordStatus;
import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.apache.sourcelume.registry.core.ingest.IngestResult;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * The ingest loop: polls the store for PENDING records and hands each to
 * the {@link IngestService} pipeline. Atlas is the single state source —
 * there is no queue; a record picked up but left PENDING (because the
 * pipeline or the backend failed) is simply found again on the next tick.
 *
 * <p>Each record is processed independently: one failing record never
 * blocks the rest of the batch, and a failing round never loses work —
 * the PENDING records stay PENDING.
 */
@ApplicationScoped
public class IngestScheduler {

    private static final Logger log = LoggerFactory.getLogger(IngestScheduler.class);

    private final AtlasAdapter atlasAdapter;
    private final IngestService ingestService;
    private final int batchSize;

    @Inject
    IngestScheduler(AtlasAdapter atlasAdapter, IngestService ingestService,
            @ConfigProperty(name = "sourcelume.ingest.batch-size", defaultValue = "20") int batchSize) {
        this.atlasAdapter = atlasAdapter;
        this.ingestService = ingestService;
        this.batchSize = batchSize;
    }

    /**
     * The scheduled tick. Skips a tick while the previous one is still
     * running — a slow backend must not pile up concurrent polls.
     */
    @Scheduled(every = "{sourcelume.ingest.poll-interval}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void pollTick() {
        poll();
    }

    void poll() {
        List<SourcelumeDatasetDto> pending = atlasAdapter.findDatasetsByStatus(RecordStatus.PENDING, batchSize);
        if (pending.isEmpty()) {
            return;
        }
        log.debug("Ingest tick: processing up to {} PENDING record(s)", pending.size());
        for (SourcelumeDatasetDto dataset : pending) {
            processOne(dataset);
        }
    }

    private void processOne(SourcelumeDatasetDto dataset) {
        try {
            IngestResult result = ingestService.process(dataset);
            if (result.validation() == null) {
                log.info("Skipped {}: changed since the poll — stays {} and is picked up again",
                        result.qualifiedName(), result.status());
            } else {
                log.info("Ingested {}: {} ({} validation issue(s))", result.qualifiedName(), result.status(),
                        result.validation().issues().size());
            }
        } catch (Exception e) {
            // No status reset, no retry bookkeeping: the record stays
            // PENDING and is picked up again on the next tick.
            log.warn("Could not ingest record {} — it stays PENDING and is retried: {}",
                    dataset.getQualifiedName(), e.getMessage(), e);
        }
    }
}

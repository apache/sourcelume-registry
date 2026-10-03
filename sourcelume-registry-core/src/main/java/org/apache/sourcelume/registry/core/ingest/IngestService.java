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

package org.apache.sourcelume.registry.core.ingest;

import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;

/**
 * SPI for the ingest pipeline step that turns a stored PENDING record
 * into a verdict: validate the record's raw JSON-LD, map a conforming
 * record onto its entity attributes, and promote the entity to
 * {@link org.apache.sourcelume.registry.common.dto.RecordStatus#ACTIVE}
 * or {@link org.apache.sourcelume.registry.common.dto.RecordStatus#INCOMPLETE}.
 *
 * <p>The registry's REST API only ever writes PENDING entities; running
 * this step is the ingest worker's job. Keeping the step behind an SPI
 * lets other frontends (a CLI, for example) run the same pipeline
 * without going through the worker process.
 */
public interface IngestService {

    /**
     * Processes one stored PENDING dataset: validates its rawJsonLd,
     * and promotes it to ACTIVE (mapped attributes) or INCOMPLETE
     * (validation issues on the entity). The stored raw document is
     * never modified.
     *
     * @param pendingDataset the stored dataset, never null
     * @return the outcome of the run, never null
     */
    IngestResult process(SourcelumeDatasetDto pendingDataset);
}

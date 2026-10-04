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
package org.apache.sourcelume.registry.core.ingest;

import org.apache.sourcelume.registry.common.dto.SourcelumeDatasetDto;

/**
 * SPI for the ingest pipeline step that turns a submitted record into
 * a verdict: validate the record's raw JSON-LD, map a conforming record
 * onto its entity attributes, and store the entity with its verdict —
 * {@link org.apache.sourcelume.registry.common.dto.RecordStatus#VALIDATED}
 * or {@link org.apache.sourcelume.registry.common.dto.RecordStatus#INCOMPLETE}.
 *
 * <p>The REST API calls this step synchronously on submission, so no
 * record reaches the store without a verdict. Keeping the step behind
 * an SPI lets other frontends (a CLI, or a queue consumer the day a
 * slow validation step makes a real queue worth its price) run the
 * same pipeline.
 */
public interface IngestService {

    /**
     * Processes one submitted dataset: validates its rawJsonLd and stores
     * the entity with its verdict — VALIDATED (mapped attributes) or
     * INCOMPLETE (validation issues on the entity). The raw document is
     * stored byte-identical.
     *
     * @param submitted the submitted dataset, never null
     * @return the outcome of the run, never null
     */
    IngestResult process(SourcelumeDatasetDto submitted);
}

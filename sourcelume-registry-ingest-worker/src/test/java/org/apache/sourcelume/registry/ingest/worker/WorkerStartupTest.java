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

package org.apache.sourcelume.registry.ingest.worker;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.core.ingest.IngestService;
import org.apache.sourcelume.registry.core.validation.ValidatorChain;
import org.apache.sourcelume.registry.ingest.worker.scheduler.IngestScheduler;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Boots the worker application with the full production wiring minus a
 * Atlas backend (bootstrap off, unreachable URL). The app starting at all
 * proves the fail-on-start wiring: ValidatorChainProducer touches the
 * chain during startup, so a worker without validator plugins would
 * have aborted boot before any test method runs (see PluginlessStartupTest
 * for the no-plugins case at the chain level).
 */
@QuarkusTest
class WorkerStartupTest {

    @Inject
    ValidatorChain validatorChain;

    @Inject
    IngestService ingestService;

    @Inject
    IngestScheduler ingestScheduler;

    @Test
    void chainIsWiredWithBothPluginsInOrder() {
        assertEquals(2, validatorChain.validators().size());
        assertEquals("json-schema", validatorChain.validators().get(0).id());
        assertEquals("shacl", validatorChain.validators().get(1).id());
    }

    @Test
    void pipelineAndSchedulerAreCdiBeans() {
        assertNotNull(ingestService);
        assertNotNull(ingestScheduler);
    }
}

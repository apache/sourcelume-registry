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

package org.apache.sourcelume.registry.ingest.worker.bootstrap;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Registers the Sourcelume typedefs with Atlas on worker startup, with
 * retries, so a worker starting alongside its Atlas backend waits for it
 * instead of failing fast. Bootstrap can be disabled via
 * {@code sourcelume.atlas.bootstrap-on-startup=false}.
 *
 * <p>Exhausted retries do not abort the worker (same semantics as the
 * Quarkus runtime): readiness stays DOWN and the poll loop keeps running
 * — a worker is useless without Atlas, but crashing on a slow backend
 * would turn deploy ordering into a hard failure.
 */
@ApplicationScoped
public class AtlasBootstrapObserver {

    private static final Logger log = LoggerFactory.getLogger(AtlasBootstrapObserver.class);

    private final AtlasAdapter atlasAdapter;
    private final SourcelumeAtlasProperties properties;

    @Inject
    public AtlasBootstrapObserver(AtlasAdapter atlasAdapter, SourcelumeAtlasProperties properties) {
        this.atlasAdapter = atlasAdapter;
        this.properties = properties;
    }

    void onStart(@Observes StartupEvent event) {
        if (!properties.bootstrapOnStartup()) {
            log.info("Atlas typedef bootstrap on startup is disabled via configuration.");
            return;
        }

        log.info("Starting Sourcelume ingest worker bootstrap sequence...");
        int attempts = 0;
        while (attempts < properties.maxRetries()) {
            attempts++;
            log.info("Attempting Atlas typedef bootstrap (attempt {}/{})", attempts, properties.maxRetries());
            try {
                atlasAdapter.registerTypeDefsFromResource(properties.typedefsResource());
                log.info("Sourcelume ingest worker bootstrap completed successfully.");
                return;
            } catch (Exception e) {
                log.warn("Atlas typedef bootstrap failed: {}", e.getMessage());
                if (attempts < properties.maxRetries()) {
                    try {
                        Thread.sleep(properties.retryDelayMs());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        log.error("Bootstrap retry interrupted", ie);
                        break;
                    }
                }
            }
        }
        log.warn("Sourcelume ingest worker bootstrap could not complete after {} attempt(s). "
                + "Worker will keep running; the readiness check stays DOWN.", attempts);
    }
}

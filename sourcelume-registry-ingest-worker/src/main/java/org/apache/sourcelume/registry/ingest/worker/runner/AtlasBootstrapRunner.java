/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
    */
package org.apache.sourcelume.registry.ingest.worker.runner;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.ingest.worker.service.AtlasBootstrapService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Startup observer that initiates the Sourcelume spec verification and typedef registration.
 *
 * <p>Quarkus/CDI equivalent of the Spring Boot {@code ApplicationRunner} used in the
 * Spring spike. Observes {@link StartupEvent} and runs the bootstrap sequence with
 * retries, exactly as the Spring version did.
 */
@ApplicationScoped
public class AtlasBootstrapRunner {

    private static final Logger log = LoggerFactory.getLogger(AtlasBootstrapRunner.class);

    private final AtlasBootstrapService bootstrapService;
    private final SourcelumeAtlasProperties properties;

    @Inject
    public AtlasBootstrapRunner(AtlasBootstrapService bootstrapService, SourcelumeAtlasProperties properties) {
        this.bootstrapService = bootstrapService;
        this.properties = properties;
    }

    void onStart(@Observes StartupEvent event) {
        if (!properties.bootstrapOnStartup()) {
            log.info("Atlas typedef bootstrap on startup is disabled via configuration.");
            return;
        }

        log.info("Starting Sourcelume Registry bootstrap sequence...");
        int attempts = 0;
        int maxAttempts = properties.maxRetries();
        long delay = properties.retryDelayMs();

        while (attempts < maxAttempts) {
            attempts++;
            log.info("Attempting Atlas bootstrap (attempt {}/{})", attempts, maxAttempts);
            if (bootstrapService.bootstrap()) {
                log.info("Sourcelume Registry bootstrap completed successfully.");
                return;
            }
            if (attempts < maxAttempts) {
                log.warn("Atlas bootstrap failed, retrying in {} ms...", delay);
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("Bootstrap retry interrupted", e);
                    break;
                }
            }
        }
        log.warn("Sourcelume Registry bootstrap could not complete all steps after {} attempt(s). Service will remain active.", attempts);
    }
}

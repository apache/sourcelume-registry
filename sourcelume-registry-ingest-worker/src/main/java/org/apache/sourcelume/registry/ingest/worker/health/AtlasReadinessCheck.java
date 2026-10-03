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
package org.apache.sourcelume.registry.ingest.worker.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

/**
 * SmallRye Health readiness check for Apache Atlas connectivity, using the
 * backend-neutral {@link AtlasAdapter} SPI. The worker is only ready to
 * promote records once its provenance-graph backend is reachable.
 *
 * <p>Ported from the Quarkus runtime module, which owns the same check for
 * the REST runtime.
 */
@Readiness
@ApplicationScoped
public class AtlasReadinessCheck implements HealthCheck {

    private final AtlasAdapter atlasAdapter;
    private final SourcelumeAtlasProperties properties;

    @Inject
    public AtlasReadinessCheck(AtlasAdapter atlasAdapter, SourcelumeAtlasProperties properties) {
        this.atlasAdapter = atlasAdapter;
        this.properties = properties;
    }

    @Override
    public HealthCheckResponse call() {
        try {
            boolean ready = atlasAdapter.isServerReady();
            if (ready) {
                return HealthCheckResponse.named("atlas")
                        .up()
                        .withData("atlasUrl", properties.url())
                        .withData("status", "CONNECTED")
                        .build();
            }
            return HealthCheckResponse.named("atlas")
                    .down()
                    .withData("atlasUrl", properties.url())
                    .withData("status", "SERVER_NOT_READY")
                    .build();
        } catch (Exception e) {
            return HealthCheckResponse.named("atlas")
                    .down()
                    .withData("atlasUrl", properties.url())
                    .withData("error", e.getMessage())
                    .build();
        }
    }
}

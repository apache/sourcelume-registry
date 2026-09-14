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
package org.apache.sourcelume.registry.ingest.worker.actuator;

import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Actuator HealthIndicator for Apache Atlas connectivity using AtlasAdapter.
 * Contributes to readiness probe on management port 9001.
 */
@Component
public class AtlasHealthIndicator implements HealthIndicator {

    private final AtlasAdapter atlasAdapter;
    private final SourcelumeAtlasProperties properties;

    public AtlasHealthIndicator(AtlasAdapter atlasAdapter, SourcelumeAtlasProperties properties) {
        this.atlasAdapter = atlasAdapter;
        this.properties = properties;
    }

    @Override
    public Health health() {
        try {
            boolean ready = atlasAdapter.isServerReady();
            if (ready) {
                return Health.up()
                        .withDetail("atlasUrl", properties.getUrl())
                        .withDetail("status", "CONNECTED")
                        .build();
            } else {
                return Health.down()
                        .withDetail("atlasUrl", properties.getUrl())
                        .withDetail("status", "SERVER_NOT_READY")
                        .build();
            }
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("atlasUrl", properties.getUrl())
                    .withDetail("error", e.getMessage())
                    .build();
        }
    }
}

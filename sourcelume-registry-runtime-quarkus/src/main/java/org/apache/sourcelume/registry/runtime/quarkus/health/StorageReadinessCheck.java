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
package org.apache.sourcelume.registry.runtime.quarkus.health;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.runtime.quarkus.bootstrap.StorageBootstrap;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.health.*;

/** Readiness follows initialization and the selected backend, including ambiguous-write quarantine. */
@Readiness
@ApplicationScoped
public class StorageReadinessCheck implements HealthCheck {
    private final RegistryPlugin plugin;
    private final StorageBootstrap bootstrap;
    private final String provider;

    @Inject
    public StorageReadinessCheck(RegistryPlugin plugin, StorageBootstrap bootstrap, Config config) {
        this.plugin = plugin;
        this.bootstrap = bootstrap;
        provider = config.getValue("sourcelume.storage.provider", String.class);
    }

    @Override
    public HealthCheckResponse call() {
        try {
            return HealthCheckResponse.named(provider)
                    .status(bootstrap.initialized() && plugin.isReady())
                    .build();
        } catch (RuntimeException e) {
            return HealthCheckResponse.named(provider).down().build();
        }
    }
}

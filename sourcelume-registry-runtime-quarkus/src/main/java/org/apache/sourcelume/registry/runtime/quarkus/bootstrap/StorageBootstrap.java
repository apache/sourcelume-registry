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
package org.apache.sourcelume.registry.runtime.quarkus.bootstrap;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.eclipse.microprofile.config.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Verifies shared resources, then delegates backend initialization to the selected plugin. */
@ApplicationScoped
public class StorageBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(StorageBootstrap.class);
    private final RegistryPlugin plugin;
    private final Config config;
    private volatile boolean initialized;

    @Inject
    public StorageBootstrap(RegistryPlugin plugin, Config config) {
        this.plugin = plugin;
        this.config = config;
    }

    void start(@Observes StartupEvent event) {
        String provider = config.getValue("sourcelume.storage.provider", String.class);
        String prefix = "sourcelume." + provider + ".";
        String contextResource = config.getOptionalValue("sourcelume.spec-context-resource", String.class)
                .orElseGet(() -> config.getOptionalValue(prefix + "spec-context-resource", String.class)
                        .orElse("context/0.0.1/sourcelume.jsonld"));
        SpecResourceLoader.loadResourceBytes(contextResource);
        boolean bootstrap = config.getOptionalValue("sourcelume.storage.bootstrap-on-startup", Boolean.class)
                .orElseGet(() -> config.getOptionalValue(prefix + "bootstrap-on-startup", Boolean.class)
                        .orElse(true));
        if (!bootstrap) {
            initialized = true;
            return;
        }
        int attempts = config.getOptionalValue("sourcelume.storage.max-retries", Integer.class)
                .orElseGet(() -> config.getOptionalValue(prefix + "max-retries", Integer.class)
                        .orElse(5));
        long delay = config.getOptionalValue("sourcelume.storage.retry-delay-ms", Long.class)
                .orElseGet(() -> config.getOptionalValue(prefix + "retry-delay-ms", Long.class)
                        .orElse(2000L));
        if (attempts < 1 || delay < 0) throw new IllegalArgumentException("Invalid storage bootstrap retry settings");
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                plugin.initialize();
                initialized = true;
                return;
            } catch (RuntimeException e) {
                LOG.warn("Storage initialization failed (attempt {}/{})", attempt, attempts, e);
            }
            if (attempt == attempts) break;
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public boolean initialized() {
        return initialized;
    }
}

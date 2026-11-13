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
package org.apache.sourcelume.registry.runtime.quarkus.storage;

import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.util.HashMap;
import java.util.Map;
import org.apache.sourcelume.registry.core.storage.PluginLoader;
import org.apache.sourcelume.registry.spi.*;
import org.eclipse.microprofile.config.Config;

/** Runtime composition root. Configuration and lifecycle stay outside Core and the SPI. */
@ApplicationScoped
public class StorageProducer {
    private final Config config;

    @Inject
    public StorageProducer(Config config) {
        this.config = config;
    }

    @Produces
    @Singleton
    @DefaultBean
    public RegistryPlugin plugin() {
        String id = config.getValue("sourcelume.storage.provider", String.class);
        boolean requireAtomic = config.getOptionalValue("sourcelume.storage.require-atomic-writes", Boolean.class)
                .orElse(false);
        RegistryPlugin plugin = PluginLoader.open(
                id, PluginContext.builder().properties(properties(id)).build());
        if (requireAtomic && plugin.recordStore().writeGuarantee() != RecordStore.WriteGuarantee.ATOMIC_CONDITIONAL) {
            plugin.close();
            throw new IllegalStateException("Selected storage plugin does not provide atomic conditional writes");
        }
        return plugin;
    }

    public Map<String, String> properties(String id) {
        String prefix = "sourcelume." + id + ".";
        Map<String, String> values = new HashMap<>();
        for (String name : config.getPropertyNames()) {
            if (!name.startsWith(prefix)) continue;
            config.getOptionalValue(name, String.class)
                    .ifPresent(value -> values.put(name.substring(prefix.length()), value));
        }
        return values;
    }

    @Produces
    @ApplicationScoped
    public RecordStore recordStore(RegistryPlugin plugin) {
        return plugin.recordStore();
    }

    @Produces
    @ApplicationScoped
    public RecordSearch recordSearch(RegistryPlugin plugin) {
        return plugin.recordSearch();
    }

    void close(@Disposes RegistryPlugin plugin) {
        plugin.close();
    }
}

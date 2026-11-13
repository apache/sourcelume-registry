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

import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.runtime.StartupEvent;
import io.smallrye.config.SmallRyeConfigBuilder;
import org.apache.sourcelume.registry.spi.*;
import org.eclipse.microprofile.config.Config;
import org.junit.jupiter.api.Test;

class StorageBootstrapTest {
    static class Plugin implements RegistryPlugin {
        int calls;
        int failures;
        boolean interrupt;

        public RecordStore recordStore() {
            return null;
        }

        public RecordSearch recordSearch() {
            return null;
        }

        public void initialize() {
            calls++;
            if (interrupt) Thread.currentThread().interrupt();
            if (calls <= failures) throw new IllegalStateException("not ready");
        }

        public boolean isReady() {
            return true;
        }

        public void close() {}
    }

    private static Config config(String... entries) {
        SmallRyeConfigBuilder builder =
                new SmallRyeConfigBuilder().withDefaultValue("sourcelume.storage.provider", "memory");
        for (int i = 0; i < entries.length; i += 2) builder.withDefaultValue(entries[i], entries[i + 1]);
        return builder.build();
    }

    @Test
    void disabledBootstrapDoesNotInitializeBackend() {
        Plugin plugin = new Plugin();
        StorageBootstrap bootstrap =
                new StorageBootstrap(plugin, config("sourcelume.storage.bootstrap-on-startup", "false"));
        bootstrap.start(new StartupEvent());
        assertEquals(0, plugin.calls);
        assertTrue(bootstrap.initialized());
    }

    @Test
    void retriesAndMarksReadyOnlyAfterSuccess() {
        Plugin plugin = new Plugin();
        plugin.failures = 1;
        StorageBootstrap bootstrap = new StorageBootstrap(
                plugin, config("sourcelume.storage.max-retries", "3", "sourcelume.storage.retry-delay-ms", "0"));
        bootstrap.start(new StartupEvent());
        assertEquals(2, plugin.calls);
        assertTrue(bootstrap.initialized());
    }

    @Test
    void exhaustedInitializationRemainsUnready() {
        Plugin plugin = new Plugin();
        plugin.failures = 10;
        StorageBootstrap bootstrap = new StorageBootstrap(
                plugin, config("sourcelume.storage.max-retries", "3", "sourcelume.storage.retry-delay-ms", "0"));
        bootstrap.start(new StartupEvent());
        assertEquals(3, plugin.calls);
        assertFalse(bootstrap.initialized());
    }

    @Test
    void interruptStopsRetries() {
        Plugin plugin = new Plugin();
        plugin.failures = 10;
        plugin.interrupt = true;
        StorageBootstrap bootstrap = new StorageBootstrap(plugin, config("sourcelume.storage.retry-delay-ms", "1"));
        try {
            bootstrap.start(new StartupEvent());
            assertEquals(1, plugin.calls);
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(bootstrap.initialized());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void selectedProviderUsesItsOwnSettings() {
        Plugin plugin = new Plugin();
        StorageBootstrap bootstrap = new StorageBootstrap(
                plugin,
                config("sourcelume.storage.provider", "memory", "sourcelume.atlas.bootstrap-on-startup", "false"));
        bootstrap.start(new StartupEvent());
        assertEquals(1, plugin.calls);
    }
}

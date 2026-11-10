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
package org.apache.sourcelume.registry.core.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.tck.MemoryPlugin;
import org.junit.jupiter.api.Test;

class PluginLoaderTest {
    static class Provider implements RegistryPluginProvider {
        final String name;
        int opened;

        Provider(String name) {
            this.name = name;
        }

        public String id() {
            return name;
        }

        public RegistryPlugin open(PluginContext context) {
            opened++;
            return new MemoryPlugin();
        }
    }

    @Test
    void opensOnlySelectedProvider() {
        Provider selected = new Provider("memory");
        Provider other = new Provider("other");
        try (var plugin = PluginLoader.open("memory", PluginContext.builder().build(), List.of(other, selected))) {
            assertTrue(plugin.isReady());
            assertEquals(1, selected.opened);
            assertEquals(0, other.opened);
        }
    }

    @Test
    void rejectsMissingAndDuplicateProviderBeforeOpeningAnything() {
        Provider one = new Provider("memory");
        Provider two = new Provider("memory");
        assertThrows(
                IllegalArgumentException.class,
                () -> PluginLoader.open("missing", PluginContext.builder().build(), List.of(one)));
        assertThrows(
                IllegalStateException.class,
                () -> PluginLoader.open("memory", PluginContext.builder().build(), List.of(one, two)));
        assertEquals(0, one.opened);
        assertEquals(0, two.opened);
    }
}

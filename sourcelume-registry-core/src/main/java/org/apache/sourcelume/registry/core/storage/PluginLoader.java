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

import java.util.Objects;
import java.util.ServiceLoader;
import org.apache.sourcelume.registry.spi.*;

/** Selects exactly one classpath provider and gives ownership of it to the runtime. */
public final class PluginLoader {
    private PluginLoader() {}

    public static RegistryPlugin open(String id, PluginContext context) {
        return open(id, context, ServiceLoader.load(RegistryPluginProvider.class));
    }

    public static RegistryPlugin open(String id, PluginContext context, Iterable<RegistryPluginProvider> providers) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(context, "context");
        RegistryPluginProvider selected = null;
        for (RegistryPluginProvider provider : providers) {
            if (!id.equals(provider.id())) continue;
            if (selected != null) throw new IllegalStateException("Multiple storage providers named " + id);
            selected = provider;
        }
        if (selected == null) throw new IllegalArgumentException("No storage provider named " + id);
        return Objects.requireNonNull(selected.open(context), "Provider returned no plugin");
    }
}

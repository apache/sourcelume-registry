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
package org.apache.sourcelume.registry.runtime.atlas;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.AtlasRegistryPlugin;
import org.apache.sourcelume.registry.spi.RegistryPlugin;

/** Existing Atlas GUID/typedef routes. They are not obligations of other storage plugins. */
@ApplicationScoped
public class AtlasCompatibilityProducer {
    @Inject
    RegistryPlugin plugin;

    @Inject
    Instance<AtlasAdapter> overrides;

    public AtlasAdapter adapter() {
        if (overrides.isResolvable()) return overrides.get();
        if (plugin instanceof AtlasRegistryPlugin atlas) return atlas.legacyAdapter();
        throw new NotFoundException("Atlas catalog views are unavailable for the selected storage provider");
    }
}

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
package org.example.sourcelume;

import org.apache.sourcelume.registry.spi.PluginContext;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.apache.sourcelume.registry.spi.RegistryPluginProvider;
import org.apache.sourcelume.registry.spi.StoreLimits;

/** Public, no-argument ServiceLoader entry point; construction has no backend side effects. */
public final class ExamplePluginProvider implements RegistryPluginProvider {
    @Override
    public String id() {
        return "example";
    }

    @Override
    public RegistryPlugin open(PluginContext context) {
        int maxDocumentBytes = Integer.parseInt(context.properties().getOrDefault("max-document-bytes", "4194304"));
        return new InMemoryPlugin(
                StoreLimits.builder().maxDocumentBytes(maxDocumentBytes).build());
    }
}

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
package org.apache.sourcelume.registry.atlas.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.spi.PluginContext;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.apache.sourcelume.registry.spi.RegistryPluginProvider;

/** ServiceLoader provider. Only the selected provider creates a client. */
public final class AtlasPluginProvider implements RegistryPluginProvider {
    @Override
    public String id() {
        return "atlas";
    }

    @Override
    public RegistryPlugin open(PluginContext context) {
        SourcelumeAtlasProperties properties = configuration(context.properties());
        ObjectMapper json = new ObjectMapper();
        return new AtlasRegistryPlugin(new RestAtlasAdapter(properties, json), json, properties.typedefsResource());
    }

    public static SourcelumeAtlasProperties configuration(Map<String, String> values) {
        Map<String, String> config = Map.copyOf(values);
        return new SourcelumeAtlasProperties() {
            public String url() {
                return config.getOrDefault("url", "http://localhost:21000");
            }

            public String user() {
                return config.getOrDefault("user", "admin");
            }

            public String password() {
                return config.getOrDefault("password", "atlasR0cks!");
            }

            public Optional<String> passwordFile() {
                return Optional.ofNullable(config.get("password-file")).filter(v -> !v.isBlank());
            }

            public String specContextResource() {
                return config.getOrDefault("spec-context-resource", "context/0.0.1/sourcelume.jsonld");
            }

            public String typedefsResource() {
                return config.getOrDefault("typedefs-resource", "models/sourcelume/sourcelume_model.json");
            }

            public boolean bootstrapOnStartup() {
                return Boolean.parseBoolean(config.getOrDefault("bootstrap-on-startup", "true"));
            }

            public int maxRetries() {
                return Integer.parseInt(config.getOrDefault("max-retries", "5"));
            }

            public long retryDelayMs() {
                return Long.parseLong(config.getOrDefault("retry-delay-ms", "2000"));
            }
        };
    }
}

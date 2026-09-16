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
package org.apache.sourcelume.registry.atlas.adapter.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Configuration for Atlas connectivity and Sourcelume schema bootstrap, bound from
 * MicroProfile Config properties under the {@code sourcelume.atlas} prefix.
 *
 * <p>Quarkus/SmallRye Config equivalent of the Spring Boot
 * {@code @ConfigurationProperties(prefix = "sourcelume.atlas")} used in the Spring
 * spike. The password-file resolution mirrors that behavior.
 */
@ConfigMapping(prefix = "sourcelume.atlas")
public interface SourcelumeAtlasProperties {

    @WithDefault("http://localhost:21000")
    String url();

    @WithDefault("admin")
    String user();

    @WithDefault("atlasR0cks!")
    String password();

    Optional<String> passwordFile();

    @WithDefault("context/0.0.1/sourcelume.jsonld")
    String specContextResource();

    @WithDefault("models/sourcelume/sourcelume_model.json")
    String typedefsResource();

    @WithDefault("true")
    boolean bootstrapOnStartup();

    @WithDefault("5")
    int maxRetries();

    @WithDefault("2000")
    long retryDelayMs();

    /**
     * Resolves the Atlas password, preferring {@code passwordFile} if configured and accessible.
     */
    default String resolvedPassword() {
        if (passwordFile().isPresent()) {
            Path path = Path.of(passwordFile().get());
            if (Files.exists(path)) {
                try {
                    return Files.readString(path).trim();
                } catch (Exception e) {
                    throw new IllegalStateException("Failed to read password from file: " + passwordFile().get(), e);
                }
            }
        }
        return password();
    }
}

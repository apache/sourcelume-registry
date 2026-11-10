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
package org.apache.sourcelume.registry.atlas.adapter.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Atlas connection and schema configuration, populated by AtlasPluginProvider.
 * The Quarkus runtime passes the selected provider's properties as plain data.
 */
public interface SourcelumeAtlasProperties {

    String url();

    String user();

    String password();

    Optional<String> passwordFile();

    String specContextResource();

    String typedefsResource();

    boolean bootstrapOnStartup();

    int maxRetries();

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
                    throw new IllegalStateException(
                            "Failed to read password from file: "
                                    + passwordFile().get(),
                            e);
                }
            }
        }
        return password();
    }
}

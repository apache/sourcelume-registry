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

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Configuration properties for Atlas connectivity and Sourcelume schema bootstrap.
 */
@ConfigurationProperties(prefix = "sourcelume.atlas")
public class SourcelumeAtlasProperties {

    private String url = "http://localhost:21000";
    private String user = "admin";
    private String password = "atlasR0cks!";
    private String passwordFile;
    private String specContextResource = "context/0.0.1/sourcelume.jsonld";
    private String typedefsResource = "models/sourcelume/sourcelume_model.json";
    private boolean bootstrapOnStartup = true;
    private int maxRetries = 5;
    private long retryDelayMs = 2000L;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUser() {
        return user;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getPasswordFile() {
        return passwordFile;
    }

    public void setPasswordFile(String passwordFile) {
        this.passwordFile = passwordFile;
    }

    public String getSpecContextResource() {
        return specContextResource;
    }

    public void setSpecContextResource(String specContextResource) {
        this.specContextResource = specContextResource;
    }

    public String getTypedefsResource() {
        return typedefsResource;
    }

    public void setTypedefsResource(String typedefsResource) {
        this.typedefsResource = typedefsResource;
    }

    public boolean isBootstrapOnStartup() {
        return bootstrapOnStartup;
    }

    public void setBootstrapOnStartup(boolean bootstrapOnStartup) {
        this.bootstrapOnStartup = bootstrapOnStartup;
    }

    public int getMaxRetries() {
        return maxRetries;
    }

    public void setMaxRetries(int maxRetries) {
        this.maxRetries = maxRetries;
    }

    public long getRetryDelayMs() {
        return retryDelayMs;
    }

    public void setRetryDelayMs(long retryDelayMs) {
        this.retryDelayMs = retryDelayMs;
    }

    /**
     * Resolves the Atlas password, preferring {@code passwordFile} if configured and accessible.
     */
    public String getResolvedPassword() {
        if (passwordFile != null && !passwordFile.isBlank()) {
            Path path = Path.of(passwordFile);
            if (Files.exists(path)) {
                try {
                    return Files.readString(path).trim();
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to read password from file: " + passwordFile, e);
                }
            }
        }
        return password;
    }
}

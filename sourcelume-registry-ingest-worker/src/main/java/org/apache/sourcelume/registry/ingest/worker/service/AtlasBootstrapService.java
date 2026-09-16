/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
    The ASF licenses this file to You under the Apache License, Version 2.0
    (the "License"); you may not use this file except in compliance with
    the License.  You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied
    See the License for the specific language governing permissions and
    limitations under the License.
    */
package org.apache.sourcelume.registry.ingest.worker.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service responsible for validating spec resources and registering Sourcelume typedefs with Atlas
 * via the AtlasAdapter.
 *
 * <p>Quarkus/CDI equivalent of the Spring {@code @Service} used in the Spring spike.
 * Uses the backend-neutral {@link AtlasAdapter} interface and {@link AtlasAdapter.TypeDefinitionModel}
 * instead of Atlas-specific {@code AtlasTypesDef} — the Atlas wire shape lives in the adapter
 * implementation only.
 */
@ApplicationScoped
public class AtlasBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AtlasBootstrapService.class);

    private final SourcelumeAtlasProperties properties;
    private final AtlasAdapter atlasAdapter;

    @Inject
    public AtlasBootstrapService(SourcelumeAtlasProperties properties, AtlasAdapter atlasAdapter) {
        this.properties = properties;
        this.atlasAdapter = atlasAdapter;
    }

    /**
     * Reads the spec context from classpath to verify spec jar dependency.
     */
    public byte[] readSpecContext() {
        String resourcePath = properties.specContextResource();
        byte[] bytes = SpecResourceLoader.loadResourceBytes(resourcePath);
        log.info("Successfully read {} bytes from spec context resource: {}", bytes.length, resourcePath);
        return bytes;
    }

    /**
     * Loads the Sourcelume typedef definitions from the bundled JSON resource.
     */
    public AtlasAdapter.TypeDefinitionModel loadTypeDefs() {
        String resourcePath = properties.typedefsResource();
        AtlasAdapter.TypeDefinitionModel typeDefs = atlasAdapter.loadTypeDefs(resourcePath);
        log.info("Loaded {} entity def(s) from {}", typeDefs.getEntityDefCount(), resourcePath);
        return typeDefs;
    }

    /**
     * Performs end-to-end bootstrap: verifies spec jar, loads typedefs, and registers them in Atlas.
     */
    public boolean bootstrap() {
        try {
            readSpecContext();
            AtlasAdapter.TypeDefinitionModel typeDefs = loadTypeDefs();
            return registerTypeDefs(typeDefs);
        } catch (Exception e) {
            log.error("Failed to bootstrap Sourcelume typedefs into Atlas: {}", e.getMessage(), e);
            return false;
        }
    }

    /**
     * Registers typedefs with Atlas via AtlasAdapter.
     */
    public boolean registerTypeDefs(AtlasAdapter.TypeDefinitionModel typeDefs) {
        try {
            log.info("Attempting to register Sourcelume typedefs with Atlas at {}", properties.url());
            AtlasAdapter.TypeDefinitionModel createdOrUpdated = atlasAdapter.registerOrUpdateTypeDefs(typeDefs);
            log.info("Successfully registered/updated Sourcelume typedefs with Atlas. Entity types: {}", createdOrUpdated.getEntityDefCount());
            return true;
        } catch (Exception e) {
            log.error("Failed to register/update typedefs in Atlas: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Checks if Atlas is reachable and responsive.
     */
    public boolean isAtlasReachable() {
        return atlasAdapter.isServerReady();
    }
}

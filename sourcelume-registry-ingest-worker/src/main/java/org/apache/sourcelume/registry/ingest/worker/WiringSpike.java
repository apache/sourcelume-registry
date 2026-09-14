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
package org.apache.sourcelume.registry.ingest.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.atlas.AtlasClientV2;
import org.apache.atlas.model.typedef.AtlasTypesDef;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.DefaultAtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;

/**
 * Steel thread for the Sourcelume Registry.
 *
 * <p>Proves four pieces of wiring connect:
 * <ol>
 *   <li>Resolves spec resource via SpecResourceLoader in sourcelume-registry-common.</li>
 *   <li>Loads typedefs from sourcelume-registry-typedefs via AtlasAdapter.</li>
 *   <li>Constructs Atlas adapter connection.</li>
 *   <li>Registers typedefs against Atlas instance.</li>
 * </ol>
 */
public final class WiringSpike {

    private static final String SPEC_CONTEXT_RESOURCE = SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE;
    private static final String TYPEDEFS_RESOURCE = "models/sourcelume/sourcelume_model.json";

    public static void main(String[] args) {
        System.out.println("Sourcelume Registry steel thread starting...");

        readSpecContextFromJar();

        SourcelumeAtlasProperties properties = new SourcelumeAtlasProperties();
        String atlasUrl = firstNonBlank(
                args.length > 0 ? args[0] : null,
                System.getenv("SOURCELUME_ATLAS_URL"),
                "http://localhost:21000");
        String username = firstNonBlank(System.getenv("SOURCELUME_ATLAS_USER"), "admin");
        String password = firstNonBlank(System.getenv("SOURCELUME_ATLAS_PASSWORD"), "atlasR0cks!");

        properties.setUrl(atlasUrl);
        properties.setUser(username);
        properties.setPassword(password);

        AtlasClientV2 atlasClient = new AtlasClientV2(new String[]{atlasUrl}, new String[]{username, password});
        AtlasAdapter atlasAdapter = new DefaultAtlasAdapter(atlasClient, properties, new ObjectMapper());

        AtlasTypesDef typesDef = atlasAdapter.loadTypeDefs(TYPEDEFS_RESOURCE);
        System.out.println("Loaded " + (typesDef.getEntityDefs() != null ? typesDef.getEntityDefs().size() : 0)
                + " entity def(s) from " + TYPEDEFS_RESOURCE);

        AtlasTypesDef created = atlasAdapter.registerOrUpdateTypeDefs(typesDef);
        System.out.println("Atlas accepted the typedefs. Entity types: "
                + (created.getEntityDefs() != null ? created.getEntityDefs().size() : 0));

        System.out.println("Steel thread complete: sourcelume-spec jar resource read, "
                + "Sourcelume typedefs loaded, and Atlas accepted them.");
    }

    private static void readSpecContextFromJar() {
        byte[] bytes = SpecResourceLoader.loadResourceBytes(SPEC_CONTEXT_RESOURCE);
        System.out.println("Read " + bytes.length
                + " bytes from sourcelume-spec jar resource: " + SPEC_CONTEXT_RESOURCE);
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private WiringSpike() {
        // Entry point only.
    }
}

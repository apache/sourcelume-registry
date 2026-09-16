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
package org.apache.sourcelume.registry.ingest.worker;

import io.quarkus.runtime.Quarkus;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.apache.sourcelume.registry.common.spec.SpecResourceLoader;

/**
 * Steel thread for the Sourcelume Registry (Quarkus spike).
 *
 * <p>Runnable proof that the four pieces of wiring connect, mirroring Jamie's
 * WiringSpike from the Spring spike branch but using the Quarkus + thin REST
 * client stack:
 *
 * <ol>
 *   <li>This module can resolve a resource bundled inside the locally-built
 *       {@code sourcelume-spec} jar (proves the spec-jar dependency is real).</li>
 *   <li>This module can load the Sourcelume typedefs bundled in
 *       {@code sourcelume-registry-typedefs} (proves the typedefs module is usable).</li>
 *   <li>The thin REST {@link AtlasAdapter} can reach a running Atlas instance.</li>
 *   <li>Atlas will accept the Sourcelume typedefs (proves the typedef JSON is well-formed
 *       and the REST wire format is correct).</li>
 * </ol>
 *
 * <p>Run directly via {@code mvn -pl sourcelume-registry-ingest-worker exec:java}
 * against a running Atlas, or just start the Quarkus app and let
 * {@code AtlasBootstrapRunner} do the same work on startup.
 */
public final class WiringSpike {

    public static void main(String[] args) {
        // When run via exec:java outside the Quarkus container, this performs the same
        // four proof steps the Spring WiringSpike did. Inside Quarkus, the
        // AtlasBootstrapRunner does this on startup — this main is the manual fallback.
        String atlasUrl = args.length > 0 ? args[0] : System.getenv().getOrDefault("SOURCELUME_ATLAS_URL", "http://localhost:21000");
        String user = System.getenv().getOrDefault("SOURCELUME_ATLAS_USER", "admin");
        String password = System.getenv().getOrDefault("SOURCELUME_ATLAS_PASSWORD", "atlasR0cks!");

        try {
            // Step 1: spec jar resource resolvable
            byte[] ctx = SpecResourceLoader.loadResourceBytes(SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE);
            System.out.println("1. spec context read: " + ctx.length + " bytes");

            // Step 2: typedefs resource resolvable
            String typedefs = SpecResourceLoader.loadResourceString("models/sourcelume/sourcelume_model.json");
            System.out.println("2. typedefs loaded: " + (typedefs.contains("sourcelume_dataset") ? "OK" : "MISSING sourcelume_dataset"));

            // Steps 3 & 4 require the AtlasAdapter, which in Quarkus is CDI-managed.
            // For the standalone manual run, we construct the thin client directly.
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            org.apache.sourcelume.registry.atlas.adapter.RestAtlasAdapter adapter =
                    new org.apache.sourcelume.registry.atlas.adapter.RestAtlasAdapter(
                            new StandaloneProperties(atlasUrl, user, password), om);

            System.out.println("3. atlas reachable: " + adapter.isServerReady());
            AtlasAdapter.TypeDefinitionModel td = adapter.registerTypeDefsFromResource("models/sourcelume/sourcelume_model.json");
            System.out.println("4. atlas accepted typedefs, entity types: " + td.getEntityDefCount());
            System.out.println("Steel thread complete: sourcelume-spec jar resource read, Sourcelume typedefs loaded, and Atlas accepted them.");
        } catch (Exception e) {
            System.err.println("Steel thread FAILED: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /** Minimal properties impl for the standalone run path (CDI not available). */
    record StandaloneProperties(String url, String user, String password) implements SourcelumeAtlasProperties {
        @Override public String url() { return url; }
        @Override public String user() { return user; }
        @Override public String password() { return password; }
        @Override public java.util.Optional<String> passwordFile() { return java.util.Optional.empty(); }
        @Override public String specContextResource() { return "context/0.0.1/sourcelume.jsonld"; }
        @Override public String typedefsResource() { return "models/sourcelume/sourcelume_model.json"; }
        @Override public boolean bootstrapOnStartup() { return false; }
        @Override public int maxRetries() { return 1; }
        @Override public long retryDelayMs() { return 0; }
    }
}

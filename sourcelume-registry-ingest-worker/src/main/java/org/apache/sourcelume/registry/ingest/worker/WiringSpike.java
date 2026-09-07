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
import org.apache.atlas.AtlasServiceException;
import org.apache.atlas.model.typedef.AtlasTypesDef;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Steel thread for the Sourcelume Registry.
 *
 * <p>This class does not implement any real ingest, validation, or signature-verification
 * logic. Its only job is to prove four pieces of wiring described in the Registry's
 * architecture actually connect:
 *
 * <ol>
 *   <li>This module can resolve a resource bundled inside the locally-built
 *       {@code sourcelume-spec} jar (proves the spec-jar dependency is real).</li>
 *   <li>This module can load the Sourcelume Atlas typedefs bundled in
 *       {@code sourcelume-registry-typedefs} (proves the typedefs module is usable).</li>
 *   <li>This module can construct an Atlas client and reach a running Atlas instance.</li>
 *   <li>Atlas will accept the Sourcelume typedefs (proves the typedef JSON is well-formed
 *       and compatible with the target Atlas version).</li>
 * </ol>
 *
 * <p>Nothing here should be mistaken for production ingest logic - see
 * {@code docs/architecture.md} in the sourcelume-registry repo for the full design
 * (queue consumption, signature verification, schema validation, etc.).
 */
public final class WiringSpike {

    // TODO: this path is a placeholder assumption about what sourcelume-spec's jar bundles
    // on its classpath. Confirm against the real resource layout published by the
    // sourcelume-spec repo (see its context/0.0.1/sourcelume.jsonld) and update accordingly.
    private static final String SPEC_CONTEXT_RESOURCE = "context/0.0.1/sourcelume.jsonld";

    // All Sourcelume entity types live in one file, loaded via a single
    // createAtlasTypeDefs call - see sourcelume-registry-typedefs/pom.xml for why
    // Atlas's own thousand-bucket directory numbering doesn't apply here.
    private static final String TYPEDEFS_RESOURCE = "models/sourcelume/sourcelume_model.json";

    public static void main(String[] args) throws IOException, AtlasServiceException {
        System.out.println("Sourcelume Registry steel thread starting...");

        readSpecContextFromJar();

        AtlasTypesDef typesDef = loadTypedefsFromResource();

        AtlasClientV2 atlasClient = buildAtlasClient(args);

        registerTypedefs(atlasClient, typesDef);

        System.out.println("Steel thread complete: sourcelume-spec jar resource read, "
                + "Sourcelume typedefs loaded, and Atlas accepted them.");
    }

    /** Step 1: prove the sourcelume-spec jar is really on the classpath and readable. */
    private static void readSpecContextFromJar() throws IOException {
        ClassLoader classLoader = WiringSpike.class.getClassLoader();
        URL resource = classLoader.getResource(SPEC_CONTEXT_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException(
                    "Could not find '" + SPEC_CONTEXT_RESOURCE + "' on the classpath. "
                            + "Check that sourcelume-spec has been 'mvn install'-ed locally, "
                            + "and that its jar actually bundles a resource at this path - "
                            + "update SPEC_CONTEXT_RESOURCE if the real layout differs.");
        }
        try (InputStream in = resource.openStream()) {
            byte[] bytes = in.readAllBytes();
            System.out.println("Read " + bytes.length
                    + " bytes from sourcelume-spec jar resource: " + SPEC_CONTEXT_RESOURCE);
        }
    }

    /** Step 2: load the Sourcelume Atlas typedefs bundled in sourcelume-registry-typedefs. */
    private static AtlasTypesDef loadTypedefsFromResource() throws IOException {
        ClassLoader classLoader = WiringSpike.class.getClassLoader();
        URL resource = classLoader.getResource(TYPEDEFS_RESOURCE);
        if (resource == null) {
            throw new IllegalStateException("Could not find typedefs resource: " + TYPEDEFS_RESOURCE);
        }
        ObjectMapper mapper = new ObjectMapper();
        try (InputStream in = resource.openStream()) {
            AtlasTypesDef typesDef = mapper.readValue(in, AtlasTypesDef.class);
            System.out.println("Loaded " + typesDef.getEntityDefs().size()
                    + " entity def(s) from " + TYPEDEFS_RESOURCE);
            return typesDef;
        }
    }

    /**
     * Step 3: build a minimal Atlas client. Connection details come from args or env vars
     * so this can point at a local dev Atlas instance without hardcoding credentials.
     *
     * <p>TODO: verify the AtlasClientV2 constructor signature against the actual Atlas
     * version this project targets (atlas.version in the parent pom) - this was written
     * from the well-known Atlas client pattern but has not been compiled against a real
     * Atlas dependency in this environment.
     */
    private static AtlasClientV2 buildAtlasClient(String[] args) {
        String atlasUrl = firstNonBlank(
                args.length > 0 ? args[0] : null,
                System.getenv("SOURCELUME_ATLAS_URL"),
                "http://localhost:21000");
        String username = firstNonBlank(System.getenv("SOURCELUME_ATLAS_USER"), "admin");
        String password = firstNonBlank(System.getenv("SOURCELUME_ATLAS_PASSWORD"), "atlasR0cks!");

        System.out.println("Connecting to Atlas at " + atlasUrl + " as user '" + username + "'");
        return new AtlasClientV2(new String[] { atlasUrl }, new String[] { username, password });
    }

    /**
     * Step 4: register the typedefs with Atlas. If this call succeeds, the steel thread has
     * verified spec-jar -> typedefs -> Atlas wiring end to end.
     */
    private static void registerTypedefs(AtlasClientV2 atlasClient, AtlasTypesDef typesDef)
            throws AtlasServiceException {
        AtlasTypesDef created = atlasClient.createAtlasTypeDefs(typesDef);
        System.out.println("Atlas accepted the typedefs. Entity types created: "
                + created.getEntityDefs().size());
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

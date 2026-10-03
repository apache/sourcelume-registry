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
package org.apache.sourcelume.registry.runtime.quarkus.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import org.apache.sourcelume.registry.core.AtlasAdapter;
import org.junit.jupiter.api.Test;

/**
 * Tests the first two bootstrap steps inside the CDI container, without a
 * running Atlas:
 *
 * <ol>
 *   <li>{@code readSpecContext()} resolves a resource bundled inside the
 *       locally-built {@code sourcelume-spec} jar — proves the spec-jar
 *       dependency is real on the runtime classpath.</li>
 *   <li>{@code loadTypeDefs()} loads the Sourcelume typedefs from
 *       {@code sourcelume-registry-typedefs} through the adapter — proves the
 *       typedefs module and the AtlasAdapter wiring are usable.</li>
 * </ol>
 *
 * <p>The third step (registering with Atlas) is covered by the startup
 * bootstrap against a live backend, see dev-support/README.md.
 */
@QuarkusTest
class AtlasBootstrapServiceTest {

    @Inject
    AtlasBootstrapService bootstrapService;

    @Test
    void shouldReadSpecContextFromBundledSpecJar() {
        byte[] context = bootstrapService.readSpecContext();
        assertNotNull(context);
        assertTrue(context.length > 0, "spec context should not be empty");
        String jsonLd = new String(context, StandardCharsets.UTF_8);
        assertTrue(jsonLd.contains("@context"), "spec context should be a JSON-LD @context document");
    }

    @Test
    void shouldLoadSourcelumeTypeDefsThroughAdapter() {
        AtlasAdapter.TypeDefinitionModel typeDefs = bootstrapService.loadTypeDefs();
        assertNotNull(typeDefs);
        assertEquals(1, typeDefs.getEntityDefCount(), "expected exactly one entity def (sourcelume_dataset)");
        assertTrue(new String(typeDefs.getRawJson(), StandardCharsets.UTF_8).contains("sourcelume_dataset"));
    }
}

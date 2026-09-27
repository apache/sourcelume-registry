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
package org.apache.sourcelume.registry.common.spec;

import org.apache.sourcelume.registry.common.exception.SourcelumeException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpecResourceLoaderTest {

    @Test
    void shouldReadBundledSpecContextResource() {
        assertTrue(SpecResourceLoader.resourceExists(SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE));
        byte[] bytes = SpecResourceLoader.loadResourceBytes(SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE);
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);

        String jsonld = SpecResourceLoader.loadResourceString(SpecResourceLoader.DEFAULT_CONTEXT_RESOURCE);
        assertTrue(jsonld.contains("@context"));
    }

    @Test
    void shouldReadBundledSchemaResource() {
        assertTrue(SpecResourceLoader.resourceExists(SpecResourceLoader.DEFAULT_SCHEMA_RESOURCE));
        String schema = SpecResourceLoader.loadResourceString(SpecResourceLoader.DEFAULT_SCHEMA_RESOURCE);
        assertTrue(schema.contains("ProvenanceRecord"));
    }

    @Test
    void shouldThrowSourcelumeExceptionOnMissingResource() {
        assertFalse(SpecResourceLoader.resourceExists("non-existent-resource.json"));
        assertThrows(SourcelumeException.class, () ->
                SpecResourceLoader.loadResourceBytes("non-existent-resource.json"));
    }
}

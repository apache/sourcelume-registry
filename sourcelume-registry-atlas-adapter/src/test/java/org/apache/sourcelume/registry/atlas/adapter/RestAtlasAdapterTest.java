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
package org.apache.sourcelume.registry.atlas.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RestAtlasAdapterTest {

    private RestAtlasAdapter adapter;

    @BeforeEach
    void setUp() {
        SourcelumeAtlasProperties properties = new SourcelumeAtlasProperties();
        adapter = new RestAtlasAdapter(properties, new ObjectMapper());
    }

    @Test
    void shouldLoadBundledTypeDefs() {
        TypeDefinitionModel td = adapter.loadTypeDefs("models/sourcelume/sourcelume_model.json");
        assertNotNull(td);
        assertEquals("models/sourcelume/sourcelume_model.json", td.getSourceResource());
        assertTrue(td.getRawJson().length > 0);
        assertEquals(1, td.getEntityDefCount(), "expected exactly one entity def (sourcelume_dataset)");
        String json = new String(td.getRawJson());
        assertTrue(json.contains("sourcelume_dataset"), "typedef JSON should contain sourcelume_dataset");
    }

    @Test
    void isServerReady_returnsFalseWhenServerUnreachable() {
        assertFalse(adapter.isServerReady());
    }
}

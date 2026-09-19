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
package org.apache.sourcelume.registry.ingest.worker.service;

import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter;
import org.apache.sourcelume.registry.atlas.adapter.AtlasAdapter.TypeDefinitionModel;
import org.apache.sourcelume.registry.atlas.adapter.config.SourcelumeAtlasProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AtlasBootstrapServiceTest {

    @Mock
    private AtlasAdapter atlasAdapter;

    private SourcelumeAtlasProperties properties;
    private AtlasBootstrapService service;

    @BeforeEach
    void setUp() {
        properties = new SourcelumeAtlasProperties();
        service = new AtlasBootstrapService(properties, atlasAdapter);
    }

    @Test
    void readSpecContext_succeedsWhenResourcePresent() {
        byte[] bytes = service.readSpecContext();
        assertNotNull(bytes);
        assertTrue(bytes.length > 0);
    }

    @Test
    void loadTypeDefs_delegatesToAtlasAdapter() {
        TypeDefinitionModel expected = new TypeDefinitionModel(properties.getTypedefsResource(), new byte[0], 1);
        when(atlasAdapter.loadTypeDefs(properties.getTypedefsResource())).thenReturn(expected);

        TypeDefinitionModel result = service.loadTypeDefs();
        assertNotNull(result);
        assertEquals(1, result.getEntityDefCount());
        verify(atlasAdapter).loadTypeDefs(properties.getTypedefsResource());
    }

    @Test
    void bootstrap_registersTypeDefsInAtlas() {
        TypeDefinitionModel typesDef = new TypeDefinitionModel(properties.getTypedefsResource(), new byte[0], 1);
        when(atlasAdapter.loadTypeDefs(any())).thenReturn(typesDef);
        when(atlasAdapter.registerOrUpdateTypeDefs(any())).thenReturn(typesDef);

        boolean result = service.bootstrap();
        assertTrue(result);
        verify(atlasAdapter).registerOrUpdateTypeDefs(any());
    }
}

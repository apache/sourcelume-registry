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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SourcelumeAtlasPropertiesTest {

    @Test
    void shouldReturnDefaultValues() {
        SourcelumeAtlasProperties properties = new SourcelumeAtlasProperties();
        assertEquals("http://localhost:21000", properties.getUrl());
        assertEquals("admin", properties.getUser());
        assertEquals("atlasR0cks!", properties.getPassword());
        assertEquals("atlasR0cks!", properties.getResolvedPassword());
        assertEquals("models/sourcelume/sourcelume_model.json", properties.getTypedefsResource());
    }

    @Test
    void shouldResolvePasswordFromFile(@TempDir Path tempDir) throws IOException {
        Path secretFile = tempDir.resolve("atlas-secret.txt");
        Files.writeString(secretFile, "secretPassword123\n");

        SourcelumeAtlasProperties properties = new SourcelumeAtlasProperties();
        properties.setPassword("fallbackPassword");
        properties.setPasswordFile(secretFile.toString());

        assertEquals("secretPassword123", properties.getResolvedPassword());
    }
}

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
package org.apache.sourcelume.registry.runtime.quarkus.test;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.apache.sourcelume.registry.atlas.adapter.AtlasRegistryPlugin;
import org.apache.sourcelume.registry.spi.*;

@Alternative
@Singleton
public class TestStoragePlugin implements RegistryPlugin {
    private final AtlasRegistryPlugin delegate;

    @Inject
    public TestStoragePlugin(TestAtlasAdapter adapter, ObjectMapper json) {
        delegate = new AtlasRegistryPlugin(adapter, json, "models/sourcelume/sourcelume_model.json");
    }

    public RecordStore recordStore() {
        return delegate.recordStore();
    }

    public RecordSearch recordSearch() {
        return delegate.recordSearch();
    }

    public void initialize() {
        delegate.initialize();
    }

    public boolean isReady() {
        return delegate.isReady();
    }

    public void close() {
        delegate.close();
    }
}

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
package org.apache.sourcelume.registry.postgres;

import org.apache.sourcelume.registry.spi.PluginContext;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.apache.sourcelume.registry.spi.RegistryPluginProvider;

/**
 * ServiceLoader entry point for the PostgreSQL storage plugin. Construction must not connect to a
 * backend; the connection settings are read from the plugin context during {@link #open}.
 */
public final class PostgresPluginProvider implements RegistryPluginProvider {

    /** Provider id matched against {@code sourcelume.storage.provider}. */
    public static final String ID = "postgres";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RegistryPlugin open(PluginContext context) {
        return new PostgresPlugin(PostgresConfig.fromProperties(context.properties()));
    }
}

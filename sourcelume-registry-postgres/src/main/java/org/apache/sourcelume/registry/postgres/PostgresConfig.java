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

import java.util.Map;
import java.util.Objects;

/**
 * Connection settings for the PostgreSQL plugin. Values arrive through the plugin context with the
 * {@code sourcelume.postgres.} prefix already stripped, so the keys are {@code jdbc-url},
 * {@code username} and {@code password}.
 */
final class PostgresConfig {

    private final String jdbcUrl;
    private final String username;
    private final String password;

    PostgresConfig(String jdbcUrl, String username, String password) {
        this.jdbcUrl = Objects.requireNonNull(jdbcUrl, "jdbcUrl");
        if (jdbcUrl.isBlank()) throw new IllegalArgumentException("Missing sourcelume.postgres.jdbc-url");
        this.username = username;
        this.password = password;
    }

    static PostgresConfig fromProperties(Map<String, String> properties) {
        Objects.requireNonNull(properties, "properties");
        return new PostgresConfig(properties.get("jdbc-url"), properties.get("username"), properties.get("password"));
    }

    String jdbcUrl() {
        return jdbcUrl;
    }

    String username() {
        return username;
    }

    String password() {
        return password;
    }
}

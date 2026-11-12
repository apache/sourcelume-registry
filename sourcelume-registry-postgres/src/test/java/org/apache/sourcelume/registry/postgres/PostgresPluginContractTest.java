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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import org.apache.sourcelume.registry.spi.PluginContext;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.apache.sourcelume.registry.spi.tck.RecordStoreContract;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Runs the storage SPI contract test suite against the PostgreSQL plugin. By default an isolated
 * PostgreSQL container is started; when the environment variable
 * {@code SOURCELUME_PG_TCK_JDBC_URL} is set, that instance is used instead (with
 * {@code SOURCELUME_PG_TCK_USERNAME} and {@code SOURCELUME_PG_TCK_PASSWORD}), which allows the
 * suite to run in environments without a Docker daemon.
 */
class PostgresPluginContractTest extends RecordStoreContract {

    private static final String ENV_JDBC_URL = "SOURCELUME_PG_TCK_JDBC_URL";
    private static final String ENV_USERNAME = "SOURCELUME_PG_TCK_USERNAME";
    private static final String ENV_PASSWORD = "SOURCELUME_PG_TCK_PASSWORD";

    private static PostgreSQLContainer<?> container;
    private static String jdbcUrl;
    private static String username;
    private static String password;

    @BeforeAll
    static void startDatabase() {
        String envUrl = System.getenv(ENV_JDBC_URL);
        if (envUrl != null && !envUrl.isBlank()) {
            jdbcUrl = envUrl;
            username = envOrDefault(ENV_USERNAME, "postgres");
            password = envOrDefault(ENV_PASSWORD, "");
        } else {
            container = new PostgreSQLContainer<>("postgres:17-alpine");
            container.start();
            jdbcUrl = container.getJdbcUrl();
            username = container.getUsername();
            password = container.getPassword();
        }
    }

    @AfterAll
    static void stopDatabase() {
        if (container != null) {
            container.stop();
        }
    }

    @Override
    protected RegistryPlugin createPlugin() {
        resetSchema();
        RegistryPlugin plugin = new PostgresPluginProvider()
                .open(PluginContext.builder()
                        .properties(Map.of("jdbc-url", jdbcUrl, "username", username, "password", password))
                        .build());
        plugin.initialize();
        return plugin;
    }

    /** Each test needs an empty, isolated store: the schema is dropped and recreated. */
    private static void resetSchema() {
        try (Connection connection = DriverManager.getConnection(jdbcUrl, username, password);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA public CASCADE");
            statement.execute("CREATE SCHEMA public");
        } catch (SQLException e) {
            throw new IllegalStateException("Could not reset the test schema", e);
        }
    }

    private static String envOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }
}

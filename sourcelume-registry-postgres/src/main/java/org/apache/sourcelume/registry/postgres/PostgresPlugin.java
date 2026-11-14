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

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.apache.sourcelume.registry.spi.RecordSearch;
import org.apache.sourcelume.registry.spi.RecordStore;
import org.apache.sourcelume.registry.spi.RegistryPlugin;
import org.apache.sourcelume.registry.spi.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PostgreSQL storage plugin. Schema ownership is exclusive to this plugin: {@link #initialize()}
 * applies the versioned migrations found on the classpath exactly once per database.
 */
final class PostgresPlugin implements RegistryPlugin {

    private static final Logger LOG = LoggerFactory.getLogger(PostgresPlugin.class);

    private static final String MIGRATIONS_PACKAGE =
            PostgresPlugin.class.getPackageName().replace('.', '/') + "/migrations";

    private final PostgresConfig config;
    private volatile boolean ready;
    private volatile boolean closed;

    PostgresPlugin(PostgresConfig config) {
        this.config = config;
    }

    @Override
    public RecordStore recordStore() {
        return new PostgresRecordStore(this);
    }

    @Override
    public RecordSearch recordSearch() {
        return new PostgresRecordSearch(this);
    }

    @Override
    public synchronized void initialize() {
        checkClosed();
        if (ready) {
            return;
        }
        try (Connection connection = openConnection()) {
            applyMigrations(connection);
        } catch (SQLException e) {
            throw new StorageException(StorageException.Code.UNAVAILABLE, "PostgreSQL schema initialization failed", e);
        }
        ready = true;
    }

    @Override
    public boolean isReady() {
        if (!ready || closed) {
            return false;
        }
        try (Connection connection = openConnection()) {
            return connection.isValid(2);
        } catch (SQLException | StorageException e) {
            return false;
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
    }

    void checkOpen() {
        checkClosed();
        if (!ready) {
            throw new StorageException(StorageException.Code.UNAVAILABLE, "PostgreSQL plugin is not initialized");
        }
    }

    Connection openConnection() {
        checkClosed();
        try {
            return DriverManager.getConnection(config.jdbcUrl(), config.username(), config.password());
        } catch (SQLException e) {
            throw new StorageException(StorageException.Code.UNAVAILABLE, "PostgreSQL connection failed", e);
        }
    }

    /**
     * Opens a read-only transaction whose statements all share one snapshot, so a
     * record's row and its facets, attachments and validation issues are read
     * atomically even while another writer corrects the record.
     */
    Connection openSnapshotConnection() throws SQLException {
        Connection connection = openConnection();
        connection.setAutoCommit(false);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        return connection;
    }

    private void checkClosed() {
        if (closed) {
            throw new StorageException(StorageException.Code.UNAVAILABLE, "PostgreSQL plugin is closed");
        }
    }

    private static void applyMigrations(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "version text PRIMARY KEY, applied_at timestamptz NOT NULL DEFAULT now())");
        }
        for (String version : migrationIndex()) {
            if (isApplied(connection, version)) {
                continue;
            }
            LOG.info("Applying PostgreSQL schema migration {}", version);
            String sql = readResource(MIGRATIONS_PACKAGE + "/" + version);
            // One transaction per migration: the DDL and its schema_history entry
            // commit together, so a disconnect can never leave an applied migration
            // unrecorded (which would fail the next startup with "relation already
            // exists"). PostgreSQL DDL is transactional.
            connection.setAutoCommit(false);
            try {
                try (Statement statement = connection.createStatement()) {
                    statement.execute(sql);
                }
                try (PreparedStatement applied =
                        connection.prepareStatement("INSERT INTO schema_history (version) VALUES (?)")) {
                    applied.setString(1, version);
                    applied.executeUpdate();
                }
                connection.commit();
            } finally {
                connection.setAutoCommit(true);
            }
        }
    }

    private static boolean isApplied(Connection connection, String version) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT 1 FROM schema_history WHERE version = ?")) {
            statement.setString(1, version);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next();
            }
        }
    }

    private static List<String> migrationIndex() {
        String index = readResource(MIGRATIONS_PACKAGE + "/index.txt");
        List<String> versions = new ArrayList<>();
        for (String line : index.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                versions.add(trimmed);
            }
        }
        return versions;
    }

    private static String readResource(String name) {
        try (InputStream input = PostgresPlugin.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) {
                throw new StorageException(StorageException.Code.INVALID_DATA, "Missing resource: " + name);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new StorageException(StorageException.Code.INVALID_DATA, "Cannot read resource: " + name, e);
        }
    }
}

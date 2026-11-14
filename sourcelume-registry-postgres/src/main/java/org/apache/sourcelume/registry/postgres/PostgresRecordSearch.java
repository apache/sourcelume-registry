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
import java.sql.SQLException;
import java.util.List;
import org.apache.sourcelume.registry.spi.RecordSearch;
import org.apache.sourcelume.registry.spi.SearchQuery;
import org.apache.sourcelume.registry.spi.StorageException;
import org.apache.sourcelume.registry.spi.StoredRecord;

/**
 * Index queries over the same PostgreSQL database as {@link PostgresRecordStore}. Search is strongly
 * consistent: the facet tables are written in the same transaction as the record row.
 */
final class PostgresRecordSearch implements RecordSearch {

    private final PostgresPlugin plugin;

    PostgresRecordSearch(PostgresPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public List<StoredRecord> search(SearchQuery query) {
        plugin.checkOpen();
        try (Connection connection = plugin.openSnapshotConnection()) {
            List<StoredRecord> records = RecordMapper.search(connection, query);
            connection.commit();
            return records;
        } catch (SQLException e) {
            throw new StorageException(StorageException.Code.BACKEND_ERROR, "Search failed", e);
        }
    }
}

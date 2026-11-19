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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.apache.sourcelume.registry.spi.RawDocument;
import org.apache.sourcelume.registry.spi.RecordAttachment;
import org.apache.sourcelume.registry.spi.RecordAttributes;
import org.apache.sourcelume.registry.spi.RecordEnvelope;
import org.apache.sourcelume.registry.spi.RecordFacet;
import org.apache.sourcelume.registry.spi.RecordFacets;
import org.apache.sourcelume.registry.spi.RecordStatus;
import org.apache.sourcelume.registry.spi.SearchQuery;
import org.apache.sourcelume.registry.spi.StoredRecord;
import org.apache.sourcelume.registry.spi.ValidationIssue;
import org.apache.sourcelume.registry.spi.ValidationMetadata;

/** Maps between the relational schema and the SPI record types. */
final class RecordMapper {

    private static final String RECORD_COLUMNS =
            "id, type, status, revision, document_sha256, document_bytes, validated_by, validated_at,"
                    + " attr_name, attr_description, attr_license_id, attr_source_uri,"
                    + " attr_model_uri, attr_model_version, attr_disclosure";

    private RecordMapper() {}

    /** Loads a single record and locks its row until the current transaction ends. */
    static StoredRecord lockRecord(Connection connection, String id) throws SQLException {
        return loadSingle(connection, "SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ? FOR UPDATE", id);
    }

    static StoredRecord loadOne(Connection connection, String id) throws SQLException {
        return loadSingle(connection, "SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ?", id);
    }

    static Map<String, StoredRecord> loadAll(Connection connection, Collection<String> ids) throws SQLException {
        List<Row> rows;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ANY(?) ORDER BY id")) {
            statement.setArray(1, connection.createArrayOf("text", ids.toArray(new String[0])));
            rows = readRows(statement);
        }
        Map<String, StoredRecord> found = new LinkedHashMap<>();
        for (StoredRecord record : hydrate(connection, rows)) {
            found.put(record.record().id(), record);
        }
        return Map.copyOf(found);
    }

    /**
     * Searches the records table, then hydrates every hit in one batch. The number of statements does
     * not grow with the number of hits.
     */
    static List<StoredRecord> search(Connection connection, SearchQuery query) throws SQLException {
        List<RecordFacet> facetKeys = new ArrayList<>();
        List<List<String>> facetValues = new ArrayList<>();
        for (Map.Entry<RecordFacet, Set<String>> entry : query.facets().values().entrySet()) {
            facetKeys.add(entry.getKey());
            facetValues.add(new ArrayList<>(entry.getValue()));
        }
        StringBuilder sql =
                new StringBuilder("SELECT " + RECORD_COLUMNS + " FROM records WHERE type = ? AND status = ?");
        for (int i = 0; i < facetKeys.size(); i++) {
            sql.append(" AND EXISTS (SELECT 1 FROM record_facets f WHERE f.record_id = records.id")
                    .append(" AND f.facet = ?");
            for (int j = 0; j < facetValues.get(i).size(); j++) {
                sql.append(j == 0 ? " AND f.value IN (?" : ", ?");
            }
            sql.append("))");
        }
        sql.append(" ORDER BY id LIMIT ?");
        try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
            int index = 1;
            statement.setString(index++, query.type());
            statement.setString(index++, query.status().name());
            for (int i = 0; i < facetKeys.size(); i++) {
                statement.setString(index++, facetKeys.get(i).name());
                for (String value : facetValues.get(i)) {
                    statement.setString(index++, value);
                }
            }
            statement.setInt(index, query.limit());
            return hydrate(connection, readRows(statement));
        }
    }

    /** Inserts the record row; returns 0 when a record with the same id already exists. */
    static int insertRecord(Connection connection, RecordEnvelope envelope, String revision) throws SQLException {
        String sql = "INSERT INTO records ("
                + RECORD_COLUMNS
                + ", updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, now()) ON CONFLICT (id) DO NOTHING";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bindEnvelope(statement, envelope, revision);
            return statement.executeUpdate();
        }
    }

    /** Replaces the record row; the caller holds the row lock from {@link #lockRecord}. */
    static int updateRecord(Connection connection, RecordEnvelope envelope, String revision) throws SQLException {
        String sql = "UPDATE records SET type = ?, status = ?, revision = ?, document_sha256 = ?,"
                + " document_bytes = ?, validated_by = ?, validated_at = ?, attr_name = ?,"
                + " attr_description = ?, attr_license_id = ?, attr_source_uri = ?, attr_model_uri = ?,"
                + " attr_model_version = ?, attr_disclosure = ?, updated_at = now() WHERE id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int bound = bindEnvelopeBody(statement, envelope, revision, 1);
            statement.setString(bound + 1, envelope.id());
            return statement.executeUpdate();
        }
    }

    /** Replaces facets, attachments and validation issues of the given record. */
    static void replaceChildren(Connection connection, RecordEnvelope envelope) throws SQLException {
        String id = envelope.id();
        try (PreparedStatement deleteFacets =
                connection.prepareStatement("DELETE FROM record_facets WHERE record_id = ?")) {
            deleteFacets.setString(1, id);
            deleteFacets.executeUpdate();
        }
        try (PreparedStatement deleteAttachments =
                connection.prepareStatement("DELETE FROM record_attachments WHERE record_id = ?")) {
            deleteAttachments.setString(1, id);
            deleteAttachments.executeUpdate();
        }
        try (PreparedStatement deleteIssues =
                connection.prepareStatement("DELETE FROM validation_issues WHERE record_id = ?")) {
            deleteIssues.setString(1, id);
            deleteIssues.executeUpdate();
        }
        try (PreparedStatement insertFacet =
                connection.prepareStatement("INSERT INTO record_facets (record_id, facet, value) VALUES (?,?,?)")) {
            for (Map.Entry<RecordFacet, Set<String>> entry :
                    envelope.facets().values().entrySet()) {
                for (String value : entry.getValue()) {
                    insertFacet.setString(1, id);
                    insertFacet.setString(2, entry.getKey().name());
                    insertFacet.setString(3, value);
                    insertFacet.addBatch();
                }
            }
            insertFacet.executeBatch();
        }
        try (PreparedStatement insertAttachment = connection.prepareStatement("INSERT INTO record_attachments"
                + " (record_id, name, ordinal, media_type, document_sha256, bytes) VALUES (?,?,?,?,?,?)")) {
            List<RecordAttachment> attachments = envelope.attachments();
            for (int i = 0; i < attachments.size(); i++) {
                RecordAttachment attachment = attachments.get(i);
                insertAttachment.setString(1, id);
                insertAttachment.setString(2, attachment.name());
                insertAttachment.setInt(3, i);
                insertAttachment.setString(4, attachment.mediaType());
                insertAttachment.setString(5, attachment.documentSha256());
                insertAttachment.setBytes(6, attachment.bytes());
                insertAttachment.addBatch();
            }
            insertAttachment.executeBatch();
        }
        try (PreparedStatement insertIssue = connection.prepareStatement("INSERT INTO validation_issues"
                + " (record_id, ordinal, validator_id, severity, path, message) VALUES (?,?,?,?,?,?)")) {
            List<ValidationIssue> issues = envelope.validation().issues();
            for (int i = 0; i < issues.size(); i++) {
                ValidationIssue issue = issues.get(i);
                insertIssue.setString(1, id);
                insertIssue.setInt(2, i);
                insertIssue.setString(3, issue.validatorId());
                insertIssue.setString(4, issue.severity().name());
                insertIssue.setString(5, issue.path());
                insertIssue.setString(6, issue.message());
                insertIssue.addBatch();
            }
            insertIssue.executeBatch();
        }
    }

    private static void bindEnvelope(PreparedStatement statement, RecordEnvelope envelope, String revision)
            throws SQLException {
        statement.setString(1, envelope.id());
        bindEnvelopeBody(statement, envelope, revision, 2);
    }

    private static int bindEnvelopeBody(
            PreparedStatement statement, RecordEnvelope envelope, String revision, int startIndex) throws SQLException {
        int index = startIndex;
        statement.setString(index++, envelope.type());
        statement.setString(index++, envelope.validation().status().name());
        statement.setString(index++, revision);
        statement.setString(index++, envelope.document().sha256());
        statement.setBytes(index++, envelope.document().bytes());
        statement.setString(index++, envelope.validation().validatedBy());
        statement.setString(index++, envelope.validation().validatedAt());
        statement.setString(index++, envelope.attributes().name());
        statement.setString(index++, envelope.attributes().description());
        statement.setString(index++, envelope.attributes().licenseId());
        statement.setString(index++, envelope.attributes().sourceUri());
        statement.setString(index++, envelope.attributes().modelUri());
        statement.setString(index++, envelope.attributes().modelVersion());
        statement.setString(index++, envelope.attributes().disclosure());
        return index - 1;
    }

    /** One records-table row. Side tables are attached in a batch by {@link #hydrate}. */
    private record Row(
            String id,
            String type,
            String status,
            String revision,
            byte[] documentBytes,
            String validatedBy,
            String validatedAt,
            String name,
            String description,
            String licenseId,
            String sourceUri,
            String modelUri,
            String modelVersion,
            String disclosure) {}

    private static StoredRecord loadSingle(Connection connection, String sql, String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            List<StoredRecord> found = hydrate(connection, readRows(statement));
            return found.isEmpty() ? null : found.get(0);
        }
    }

    private static List<Row> readRows(PreparedStatement statement) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (ResultSet resultSet = statement.executeQuery()) {
            while (resultSet.next()) {
                rows.add(new Row(
                        resultSet.getString("id"),
                        resultSet.getString("type"),
                        resultSet.getString("status"),
                        resultSet.getString("revision"),
                        resultSet.getBytes("document_bytes"),
                        resultSet.getString("validated_by"),
                        resultSet.getString("validated_at"),
                        resultSet.getString("attr_name"),
                        resultSet.getString("attr_description"),
                        resultSet.getString("attr_license_id"),
                        resultSet.getString("attr_source_uri"),
                        resultSet.getString("attr_model_uri"),
                        resultSet.getString("attr_model_version"),
                        resultSet.getString("attr_disclosure")));
            }
        }
        return rows;
    }

    /**
     * Turns rows into records. Validation issues, facets and attachments are each read with one query
     * for the whole batch, so the statement count is constant in the number of rows.
     */
    private static List<StoredRecord> hydrate(Connection connection, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return List.of();
        }
        String[] ids = rows.stream().map(Row::id).toArray(String[]::new);
        Map<String, List<ValidationIssue>> issues = loadIssues(connection, ids);
        Map<String, RecordFacets.Builder> facets = loadFacets(connection, ids);
        Map<String, List<RecordAttachment>> attachments = loadAttachments(connection, ids);
        List<StoredRecord> records = new ArrayList<>(rows.size());
        for (Row row : rows) {
            RecordFacets.Builder recordFacets = facets.get(row.id());
            RecordEnvelope envelope = RecordEnvelope.builder()
                    .id(row.id())
                    .type(row.type())
                    .document(RawDocument.builder().bytes(row.documentBytes()).build())
                    .validation(ValidationMetadata.builder()
                            .status(RecordStatus.valueOf(row.status()))
                            .issues(issues.getOrDefault(row.id(), List.of()))
                            .validatedBy(row.validatedBy())
                            .validatedAt(row.validatedAt())
                            .build())
                    .attributes(RecordAttributes.builder()
                            .name(row.name())
                            .description(row.description())
                            .licenseId(row.licenseId())
                            .sourceUri(row.sourceUri())
                            .modelUri(row.modelUri())
                            .modelVersion(row.modelVersion())
                            .disclosure(row.disclosure())
                            .build())
                    .facets(recordFacets == null ? RecordFacets.builder().build() : recordFacets.build())
                    .attachments(attachments.getOrDefault(row.id(), List.of()))
                    .build();
            records.add(StoredRecord.builder()
                    .record(envelope)
                    .revision(row.revision())
                    .build());
        }
        return records;
    }

    private static Map<String, List<ValidationIssue>> loadIssues(Connection connection, String[] ids)
            throws SQLException {
        Map<String, List<ValidationIssue>> byRecord = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_id, validator_id, severity, path, message FROM validation_issues"
                        + " WHERE record_id = ANY(?) ORDER BY record_id, ordinal")) {
            statement.setArray(1, connection.createArrayOf("text", ids));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byRecord.computeIfAbsent(resultSet.getString("record_id"), key -> new ArrayList<>())
                            .add(new ValidationIssue(
                                    resultSet.getString("validator_id"),
                                    ValidationIssue.Severity.valueOf(resultSet.getString("severity")),
                                    resultSet.getString("path"),
                                    resultSet.getString("message")));
                }
            }
        }
        return byRecord;
    }

    private static Map<String, RecordFacets.Builder> loadFacets(Connection connection, String[] ids)
            throws SQLException {
        Map<String, RecordFacets.Builder> byRecord = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_id, facet, value FROM record_facets WHERE record_id = ANY(?)")) {
            statement.setArray(1, connection.createArrayOf("text", ids));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byRecord.computeIfAbsent(resultSet.getString("record_id"), key -> RecordFacets.builder())
                            .add(RecordFacet.valueOf(resultSet.getString("facet")), resultSet.getString("value"));
                }
            }
        }
        return byRecord;
    }

    private static Map<String, List<RecordAttachment>> loadAttachments(Connection connection, String[] ids)
            throws SQLException {
        Map<String, List<RecordAttachment>> byRecord = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT record_id, name, media_type, document_sha256, bytes FROM record_attachments"
                        + " WHERE record_id = ANY(?) ORDER BY record_id, ordinal")) {
            statement.setArray(1, connection.createArrayOf("text", ids));
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    byRecord.computeIfAbsent(resultSet.getString("record_id"), key -> new ArrayList<>())
                            .add(RecordAttachment.builder()
                                    .name(resultSet.getString("name"))
                                    .mediaType(resultSet.getString("media_type"))
                                    .documentSha256(resultSet.getString("document_sha256"))
                                    .bytes(resultSet.getBytes("bytes"))
                                    .build());
                }
            }
        }
        return byRecord;
    }
}

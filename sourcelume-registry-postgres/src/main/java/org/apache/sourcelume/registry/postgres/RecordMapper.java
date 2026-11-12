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

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
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
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ? FOR UPDATE")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(connection, resultSet) : null;
            }
        }
    }

    static StoredRecord loadOne(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() ? mapRow(connection, resultSet) : null;
            }
        }
    }

    static Map<String, StoredRecord> loadAll(Connection connection, Collection<String> ids) throws SQLException {
        Map<String, StoredRecord> found = new LinkedHashMap<>();
        Array idArray = connection.createArrayOf("text", ids.toArray(new String[0]));
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT " + RECORD_COLUMNS + " FROM records WHERE id = ANY(?) ORDER BY id")) {
            statement.setArray(1, idArray);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    StoredRecord record = mapRow(connection, resultSet);
                    found.put(record.record().id(), record);
                }
            }
        }
        return Map.copyOf(found);
    }

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
        List<StoredRecord> results = new ArrayList<>();
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
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    results.add(mapRow(connection, resultSet));
                }
            }
        }
        return results;
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

    private static StoredRecord mapRow(Connection connection, ResultSet resultSet) throws SQLException {
        String id = resultSet.getString("id");
        RecordEnvelope envelope = RecordEnvelope.builder()
                .id(id)
                .type(resultSet.getString("type"))
                .document(RawDocument.builder()
                        .bytes(resultSet.getBytes("document_bytes"))
                        .build())
                .validation(ValidationMetadata.builder()
                        .status(RecordStatus.valueOf(resultSet.getString("status")))
                        .issues(loadIssues(connection, id))
                        .validatedBy(resultSet.getString("validated_by"))
                        .validatedAt(resultSet.getString("validated_at"))
                        .build())
                .attributes(RecordAttributes.builder()
                        .name(resultSet.getString("attr_name"))
                        .description(resultSet.getString("attr_description"))
                        .licenseId(resultSet.getString("attr_license_id"))
                        .sourceUri(resultSet.getString("attr_source_uri"))
                        .modelUri(resultSet.getString("attr_model_uri"))
                        .modelVersion(resultSet.getString("attr_model_version"))
                        .disclosure(resultSet.getString("attr_disclosure"))
                        .build())
                .facets(loadFacets(connection, id))
                .attachments(loadAttachments(connection, id))
                .build();
        return StoredRecord.builder()
                .record(envelope)
                .revision(resultSet.getString("revision"))
                .build();
    }

    private static List<ValidationIssue> loadIssues(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT validator_id, severity, path, message FROM validation_issues"
                        + " WHERE record_id = ? ORDER BY ordinal")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<ValidationIssue> issues = new ArrayList<>();
                while (resultSet.next()) {
                    issues.add(new ValidationIssue(
                            resultSet.getString("validator_id"),
                            ValidationIssue.Severity.valueOf(resultSet.getString("severity")),
                            resultSet.getString("path"),
                            resultSet.getString("message")));
                }
                return issues;
            }
        }
    }

    private static RecordFacets loadFacets(Connection connection, String id) throws SQLException {
        RecordFacets.Builder facets = RecordFacets.builder();
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT facet, value FROM record_facets WHERE record_id = ?")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    facets.add(RecordFacet.valueOf(resultSet.getString("facet")), resultSet.getString("value"));
                }
            }
        }
        return facets.build();
    }

    private static List<RecordAttachment> loadAttachments(Connection connection, String id) throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT name, media_type, document_sha256, bytes FROM record_attachments"
                        + " WHERE record_id = ? ORDER BY ordinal")) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                List<RecordAttachment> attachments = new ArrayList<>();
                while (resultSet.next()) {
                    attachments.add(RecordAttachment.builder()
                            .name(resultSet.getString("name"))
                            .mediaType(resultSet.getString("media_type"))
                            .documentSha256(resultSet.getString("document_sha256"))
                            .bytes(resultSet.getBytes("bytes"))
                            .build());
                }
                return attachments;
            }
        }
    }
}

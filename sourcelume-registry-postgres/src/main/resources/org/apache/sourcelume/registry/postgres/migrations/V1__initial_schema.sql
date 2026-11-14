-- Licensed to the Apache Software Foundation (ASF) under one or more
-- contributor license agreements.  See the NOTICE file distributed with
-- this work for additional information regarding copyright ownership.
-- The ASF licenses this file to You under the Apache License, Version 2.0
-- (the "License"); you may not use this file except in compliance with
-- the License.  You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.

CREATE TABLE records (
    id text PRIMARY KEY,
    type text NOT NULL,
    status text NOT NULL,
    revision text NOT NULL,
    document_sha256 text NOT NULL,
    document_bytes bytea NOT NULL,
    validated_by text,
    validated_at text,
    attr_name text,
    attr_description text,
    attr_license_id text,
    attr_source_uri text,
    attr_model_uri text,
    attr_model_version text,
    attr_disclosure text,
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX records_type_status_idx ON records (type, status);

CREATE TABLE record_facets (
    record_id text NOT NULL REFERENCES records (id) ON DELETE CASCADE,
    facet text NOT NULL,
    value text NOT NULL,
    PRIMARY KEY (record_id, facet, value)
);

CREATE INDEX record_facets_lookup_idx ON record_facets (facet, value);

CREATE TABLE record_attachments (
    record_id text NOT NULL REFERENCES records (id) ON DELETE CASCADE,
    name text NOT NULL,
    ordinal int NOT NULL,
    media_type text NOT NULL,
    document_sha256 text NOT NULL,
    bytes bytea NOT NULL,
    PRIMARY KEY (record_id, name)
);

CREATE TABLE validation_issues (
    record_id text NOT NULL REFERENCES records (id) ON DELETE CASCADE,
    ordinal int NOT NULL,
    validator_id text NOT NULL,
    severity text NOT NULL,
    path text NOT NULL,
    message text NOT NULL,
    PRIMARY KEY (record_id, ordinal)
);

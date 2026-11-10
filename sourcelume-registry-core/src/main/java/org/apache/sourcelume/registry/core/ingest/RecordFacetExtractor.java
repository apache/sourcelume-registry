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
package org.apache.sourcelume.registry.core.ingest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.sourcelume.registry.common.dto.RecordType;
import org.apache.sourcelume.registry.spi.RecordFacet;
import org.apache.sourcelume.registry.spi.RecordFacets;

/** Extracts well-shaped claims even from INCOMPLETE records; a facet is not a validation verdict. */
final class RecordFacetExtractor {
    private static final ObjectMapper JSON = new ObjectMapper();

    private RecordFacetExtractor() {}

    static RecordFacets extract(String raw, RecordType type) {
        try {
            JsonNode record = JSON.readTree(raw);
            var facets = RecordFacets.builder();
            addArray(facets, RecordFacet.CREATOR_NAME, record.path("creator"), "name");
            if (type == RecordType.PROVENANCE_RECORD) {
                add(facets, RecordFacet.LICENSE, record.path("license"));
                addArray(facets, RecordFacet.CUSTODY_AGENT, record.path("custodyChain"), "agent");
            }
            if (type == RecordType.TRAINING_PROVENANCE_RECORD) {
                addArray(facets, RecordFacet.TRAINING_DATASET, record.path("trainingData"), "dataset");
            }
            return facets.build();
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Cannot extract record facets", e);
        }
    }

    private static void addArray(RecordFacets.Builder facets, RecordFacet facet, JsonNode array, String field) {
        if (!array.isArray()) return;
        for (JsonNode item : array) add(facets, facet, item.path(field));
    }

    private static void add(RecordFacets.Builder facets, RecordFacet facet, JsonNode value) {
        if (!value.isTextual()
                || value.textValue().isBlank()
                || !java.nio.charset.StandardCharsets.UTF_8.newEncoder().canEncode(value.textValue())) return;
        facets.add(facet, value.textValue());
    }
}

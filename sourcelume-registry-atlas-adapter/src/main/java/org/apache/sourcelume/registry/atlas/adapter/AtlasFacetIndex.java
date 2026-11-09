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
package org.apache.sourcelume.registry.atlas.adapter;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.stream.Collectors;
import org.apache.sourcelume.registry.spi.RecordFacet;
import org.apache.sourcelume.registry.spi.RecordFacets;

/** Fixed-width, case-sensitive tokens avoid Atlas text analysis changing exact facet matching. */
final class AtlasFacetIndex {
    private AtlasFacetIndex() {}

    static String token(RecordFacet facet, String value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest((facet.name() + "\n" + value).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String encode(RecordFacets facets) {
        return facets.values().entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(value -> token(entry.getKey(), value)))
                .collect(Collectors.joining(" "));
    }
}

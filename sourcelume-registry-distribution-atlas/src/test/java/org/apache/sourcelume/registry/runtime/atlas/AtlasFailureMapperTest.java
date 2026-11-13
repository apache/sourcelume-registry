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
package org.apache.sourcelume.registry.runtime.atlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.core.JsonParseException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.io.IOException;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.apache.sourcelume.registry.runtime.atlas.AtlasFailureMapper.ErrorResponse;
import org.junit.jupiter.api.Test;

/** The failures are built the way RestAtlasAdapter throws them. */
class AtlasFailureMapperTest {

    private final AtlasFailureMapper mapper = new AtlasFailureMapper();

    @Test
    void aBackendThatCannotBeReachedIs503() {
        assertEquals(503, status(new AtlasAdapterException("I/O error", 500, new ConnectException())));
        assertEquals(503, status(new AtlasAdapterException("I/O error", 500, new HttpTimeoutException("timed out"))));
        assertEquals(503, status(new AtlasAdapterException("Interrupted", 500, new InterruptedException())));
    }

    @Test
    void anErrorAnswerFromTheBackendIs502() {
        assertEquals(502, status(new AtlasAdapterException("Failed to fetch: {}", 401, null)));
        assertEquals(502, status(new AtlasAdapterException("Failed to fetch: {}", 500, null)));
        // An unreadable answer surfaces as an IOException too.
        assertEquals(502, status(new AtlasAdapterException("I/O error", 500, new JsonParseException(null, "bad"))));
        assertEquals(
                502, status(new AtlasAdapterException("Unknown record status", 500, new IllegalArgumentException())));
    }

    @Test
    void aFailureWithoutABackendCallIs500() {
        assertEquals(500, status(new AtlasAdapterException("Could not find typedefs resource")));
        assertEquals(500, status(new AtlasAdapterException("Failed to read typedefs resource", new IOException())));
    }

    @Test
    void theBodyIsJsonAndLeavesOutTheBackendsAnswer() {
        Response response = mapper.toResponse(new AtlasAdapterException("Failed to fetch: {\"secret\":1}", 401, null));
        assertEquals(MediaType.APPLICATION_JSON_TYPE, response.getMediaType());
        ErrorResponse body = (ErrorResponse) response.getEntity();
        assertFalse(body.message().contains("secret"), body.message());
    }

    private int status(AtlasAdapterException e) {
        return mapper.toResponse(e).getStatus();
    }
}

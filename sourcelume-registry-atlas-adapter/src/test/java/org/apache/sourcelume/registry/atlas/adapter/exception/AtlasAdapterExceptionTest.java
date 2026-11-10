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
package org.apache.sourcelume.registry.atlas.adapter.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class AtlasAdapterExceptionTest {

    @Test
    void testConstructorsAndGetters() {
        AtlasAdapterException e1 = new AtlasAdapterException("Failed to connect");
        assertEquals("Failed to connect", e1.getMessage());
        assertNull(e1.getCause());
        assertNull(e1.getHttpStatusCode());

        Throwable cause = new RuntimeException("connection timed out");
        AtlasAdapterException e2 = new AtlasAdapterException("Connection error", cause);
        assertEquals("Connection error", e2.getMessage());
        assertEquals(cause, e2.getCause());
        assertNull(e2.getHttpStatusCode());

        AtlasAdapterException e3 = new AtlasAdapterException("HTTP 404 from Atlas", 404, cause);
        assertEquals("HTTP 404 from Atlas", e3.getMessage());
        assertEquals(404, e3.getHttpStatusCode());
        assertEquals(cause, e3.getCause());
    }
}

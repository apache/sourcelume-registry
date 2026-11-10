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

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.sourcelume.registry.common.dto.RecordEntityDto;
import org.apache.sourcelume.registry.spi.*;
import org.apache.sourcelume.registry.spi.tck.RecordStoreContract;
import org.junit.jupiter.api.Test;

class AtlasPreSendFailureTest {
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<ObjectNode> stored = new AtomicReference<>();
    private final AtomicInteger mutations = new AtomicInteger();

    @Test
    void connectionRefusedBeforeSendingDoesNotQuarantineRecoveredBackend() throws Exception {
        HttpServer initial = server(0);
        int port = initial.getAddress().getPort();
        var config = AtlasPluginProvider.configuration(Map.of("url", "http://127.0.0.1:" + port));
        var adapter = new RestAtlasAdapter(config, json) {
            private boolean stopped;

            @Override
            public String createOrUpdateRecordEntity(RecordEntityDto entity) {
                if (!stopped) {
                    stopped = true;
                    initial.stop(0);
                }
                return super.createOrUpdateRecordEntity(entity);
            }
        };
        try (var plugin = new AtlasRegistryPlugin(adapter, json, "unused")) {
            StorageException failure = assertThrows(StorageException.class, () -> plugin.write(request("urn:refused")));
            assertEquals(StorageException.Code.UNAVAILABLE, failure.code());
            assertEquals(0, mutations.get());
            HttpServer recovered = server(port);
            try {
                assertTrue(plugin.isReady());
                assertEquals(
                        WriteResult.Disposition.CREATED,
                        plugin.write(request("urn:next")).disposition());
                assertEquals(1, mutations.get());
            } finally {
                recovered.stop(0);
            }
        } finally {
            initial.stop(0);
        }
    }

    private static WriteRequest request(String id) {
        return WriteRequest.builder()
                .condition(WriteCondition.absent())
                .candidate(RecordStoreContract.record(id, "ProvenanceRecord", "{}", RecordStatus.INCOMPLETE))
                .build();
    }

    private HttpServer server(int port) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            int status = 404;
            ObjectNode result = json.createObjectNode();
            if (path.endsWith("/admin/version")) status = 200;
            if (exchange.getRequestMethod().equals("POST")) {
                mutations.incrementAndGet();
                stored.set((ObjectNode) json.readTree(exchange.getRequestBody())
                        .path("entities")
                        .get(0));
                status = 200;
            }
            if (exchange.getRequestMethod().equals("GET")
                    && stored.get() != null
                    && path.endsWith("/" + stored.get().path("typeName").asText())) {
                result.set("entity", stored.get());
                status = 200;
            }
            byte[] body = result.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Connection", "close");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return server;
    }
}

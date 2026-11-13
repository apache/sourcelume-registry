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

import com.fasterxml.jackson.core.JsonProcessingException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.io.IOException;
import java.util.function.Function;
import org.apache.sourcelume.registry.atlas.adapter.exception.AtlasAdapterException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Answers a request whose backend call failed with a JSON error instead
 * of the framework's plain-text 500 page:
 * <ul>
 *   <li>{@code 503} when the backend could not be reached (refused,
 *       timed out) — what the readiness check reports too; worth a retry;</li>
 *   <li>{@code 502} when the backend answered with an error, or with an
 *       answer or stored data the adapter could not read;</li>
 *   <li>{@code 500} when no backend call failed, such as a typedefs
 *       resource missing from the classpath.</li>
 * </ul>
 * The body never carries the backend's own answer; that goes to the log.
 * A resource with its own error body shape passes it to
 * {@link #respond(AtlasAdapterException, Function)}.
 */
@Provider
public class AtlasFailureMapper implements ExceptionMapper<AtlasAdapterException> {

    public record ErrorResponse(String message) {}

    private static final Logger log = LoggerFactory.getLogger(AtlasFailureMapper.class);

    @Override
    public Response toResponse(AtlasAdapterException e) {
        return respond(e, ErrorResponse::new);
    }

    /** The answer for {@code e}, with the message wrapped in the caller's error body. */
    public static Response respond(AtlasAdapterException e, Function<String, ?> body) {
        int status = statusFor(e);
        String message;
        if (status == 503) {
            // Expected while the backend is down; one line per request is enough.
            log.warn("Backend unavailable: {} ({})", e.getMessage(), String.valueOf(e.getCause()));
            message = "The registry's backend is unavailable; try again later.";
        } else if (status == 502) {
            log.error("Backend call failed", e);
            message = "The registry's backend answered with an error or with data the registry could not read.";
        } else {
            log.error("Request failed", e);
            message = "The registry could not complete the request.";
        }
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(body.apply(message))
                .build();
    }

    static int statusFor(AtlasAdapterException e) {
        // The adapter sets a status only when a backend call failed: the
        // backend's own status, or 500 with the IOException or
        // InterruptedException when the call itself failed. A
        // JsonProcessingException is an IOException too, but means the
        // backend answered with something unreadable. An interrupted call
        // never got its answer — the registry is shutting down or gave up
        // on it — so it counts as unavailable too.
        if (e.getHttpStatusCode() == null) {
            return 500;
        }
        Throwable cause = e.getCause();
        boolean unreachable = cause instanceof InterruptedException
                || (cause instanceof IOException && !(cause instanceof JsonProcessingException));
        return unreachable ? 503 : 502;
    }
}

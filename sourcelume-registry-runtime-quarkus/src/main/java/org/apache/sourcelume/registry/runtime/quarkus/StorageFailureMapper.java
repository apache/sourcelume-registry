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
package org.apache.sourcelume.registry.runtime.quarkus;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.apache.sourcelume.registry.runtime.quarkus.ingest.IngestResource.ErrorResponse;
import org.apache.sourcelume.registry.spi.StorageException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Provider
public class StorageFailureMapper implements ExceptionMapper<StorageException> {
    private static final Logger LOG = LoggerFactory.getLogger(StorageFailureMapper.class);

    @Override
    public Response toResponse(StorageException failure) {
        LOG.warn("Storage operation failed: {}", failure.code(), failure);
        if (failure.code() == StorageException.Code.UNKNOWN_WRITE)
            return Response.status(503)
                    .type(MediaType.APPLICATION_JSON)
                    .header("X-Sourcelume-Error", "UNKNOWN_WRITE")
                    .entity(
                            new ErrorResponse(
                                    "The write outcome is unknown. Do not retry automatically; storage reconciliation is required."))
                    .build();
        int status = failure.code() == StorageException.Code.UNAVAILABLE ? 503 : 502;
        String message = status == 503
                ? "The registry's backend is unavailable; try again later."
                : "The registry's backend answered with an error or with data the registry could not read.";
        return Response.status(status)
                .type(MediaType.APPLICATION_JSON)
                .entity(new ErrorResponse(message))
                .build();
    }
}

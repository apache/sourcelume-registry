/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sourcelume.registry.common.spec;

import org.apache.sourcelume.registry.common.exception.SourcelumeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;

/**
 * Utility for reading specification resources bundled in sourcelume-spec or classpath.
 */
public final class SpecResourceLoader {

    private static final Logger log = LoggerFactory.getLogger(SpecResourceLoader.class);

    public static final String DEFAULT_CONTEXT_RESOURCE = "context/0.0.1/sourcelume.jsonld";
    public static final String DEFAULT_SCHEMA_RESOURCE = "schema/0.0.1/sourcelume.schema.json";
    public static final String DEFAULT_SHACL_RESOURCE = "schema/0.0.1/sourcelume.shacl.ttl";

    private SpecResourceLoader() {
    }

    /**
     * Reads a classpath resource as byte array.
     *
     * @param resourcePath classpath resource path
     * @return contents as bytes
     * @throws SourcelumeException if resource not found or unreadable
     */
    public static byte[] loadResourceBytes(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SpecResourceLoader.class.getClassLoader();
        }
        URL url = cl.getResource(resourcePath);
        if (url == null) {
            throw new SourcelumeException(
                    "Specification resource not found on classpath: " + resourcePath);
        }
        try (InputStream in = url.openStream()) {
            byte[] bytes = in.readAllBytes();
            log.debug("Loaded {} bytes from classpath resource: {}", bytes.length, resourcePath);
            return bytes;
        } catch (IOException e) {
            throw new SourcelumeException("Failed to read specification resource: " + resourcePath, e);
        }
    }

    /**
     * Reads a classpath resource as UTF-8 string.
     *
     * @param resourcePath classpath resource path
     * @return contents as string
     */
    public static String loadResourceString(String resourcePath) {
        return new String(loadResourceBytes(resourcePath), java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Checks if a resource exists on classpath.
     */
    public static boolean resourceExists(String resourcePath) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) {
            cl = SpecResourceLoader.class.getClassLoader();
        }
        return cl.getResource(resourcePath) != null;
    }
}

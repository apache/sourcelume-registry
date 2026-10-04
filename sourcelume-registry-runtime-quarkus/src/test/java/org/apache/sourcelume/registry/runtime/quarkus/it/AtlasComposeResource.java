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
package org.apache.sourcelume.registry.runtime.quarkus.it;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.File;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.testcontainers.containers.ComposeContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;

/**
 * Boots the vendored Apache Atlas 2.5.0 stack (Atlas, Solr, Kafka, ZooKeeper,
 * PostgreSQL — see dev-support/vendor/atlas-docker) via Testcontainers for
 * the integration tests, and points the registry runtime at it.
 *
 * <p>Prerequisites, deliberately NOT automated here (they are heavyweight,
 * one-time steps — see dev-support/README.md): the vendored Atlas tooling at
 * {@code dev-support/vendor/atlas-docker} and the locally built
 * {@code atlas:latest} image. When this resource runs, a missing
 * prerequisite fails loudly with an actionable message instead of being
 * skipped — the profile was requested explicitly.
 */
public class AtlasComposeResource implements QuarkusTestResourceLifecycleManager {

    /** Overrides the compose file location (e.g. for CI setups). */
    public static final String COMPOSE_FILE_PROPERTY = "sourcelume.it.atlas-compose";

    private static final String DEFAULT_COMPOSE_FILE = "../dev-support/vendor/atlas-docker/docker-compose.atlas.yml";

    private ComposeContainer compose;

    @Override
    public Map<String, String> start() {
        File composeFile = composeFile();
        if (!composeFile.isFile()) {
            throw new IllegalStateException(
                    """
                    The vendored Atlas compose file %s does not exist.
                    Run the one-time setup first: ./dev-support/setup.sh, then follow
                    dev-support/README.md (download-archives.sh, build, up), or set
                    the system property %s to an existing compose file."""
                            .formatted(composeFile, COMPOSE_FILE_PROPERTY));
        }

        compose = new ComposeContainer(composeFile)
                .withLocalCompose(true)
                // atlas:latest is built locally by the dev-support flow;
                // pulling would fail (the image is not on any registry).
                .withPull(false)
                .withEnv("ATLAS_BACKEND", "postgres")
                // A fresh Atlas boot initializes Solr collections and the
                // storage backend — this takes minutes, not seconds.
                .withExposedService(
                        "atlas",
                        21000,
                        new HttpWaitStrategy()
                                .forPath("/api/atlas/admin/version")
                                .forStatusCode(200)
                                .withBasicCredentials("admin", "atlasR0cks!")
                                .withStartupTimeout(Duration.ofMinutes(10)));
        compose.start();

        Map<String, String> config = new HashMap<>();
        config.put(
                "sourcelume.atlas.url",
                "http://" + compose.getServiceHost("atlas", 21000) + ":" + compose.getServicePort("atlas", 21000));
        config.put("sourcelume.atlas.user", "admin");
        config.put("sourcelume.atlas.password", "atlasR0cks!");
        // The tests exercise the full stack, including the startup bootstrap
        // of the type definitions against the real backend.
        config.put("sourcelume.atlas.bootstrap-on-startup", "true");
        return config;
    }

    @Override
    public void stop() {
        if (compose != null) {
            compose.stop();
        }
    }

    private static File composeFile() {
        String configured = System.getProperty(COMPOSE_FILE_PROPERTY);
        return new File(configured != null ? configured : DEFAULT_COMPOSE_FILE);
    }
}

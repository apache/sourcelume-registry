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
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 *
 * <p>The vendored compose files pin {@code container_name} on their
 * services — in the main file and in the files pulled in via
 * {@code extends} — which Testcontainers refuses to manage. Instead of
 * forking the files, this resource derives stripped copies at runtime
 * (same directory, so relative paths and the environment file keep
 * working) and removes them on shutdown.
 */
public class AtlasComposeResource implements QuarkusTestResourceLifecycleManager {

    /** Overrides the compose file location (e.g. for CI setups). */
    public static final String COMPOSE_FILE_PROPERTY = "sourcelume.it.atlas-compose";

    /** Compose {@code extends} entries look like {@code file: <name>}. */
    private static final Pattern EXTENDS_FILE_PATTERN = Pattern.compile("file:\\s*([\\w.\\-]+\\.ya?ml)");

    private static final String DEFAULT_COMPOSE_FILE = "../dev-support/vendor/atlas-docker/docker-compose.atlas.yml";

    private static final String DERIVED_SUFFIX = ".sourcelume-it.yml";

    private ComposeContainer compose;
    private final List<Path> derivedFiles = new ArrayList<>();

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

        compose = new ComposeContainer(deriveStrippedCopies(composeFile))
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
        for (Path derived : derivedFiles) {
            try {
                Files.deleteIfExists(derived);
            } catch (IOException e) {
                // best effort cleanup of derived, gitignored helper files
            }
        }
    }

    private static File composeFile() {
        String configured = System.getProperty(COMPOSE_FILE_PROPERTY);
        return new File(configured != null ? configured : DEFAULT_COMPOSE_FILE);
    }

    /**
     * Derives Testcontainers-manageable copies of the compose file and of
     * every file it pulls in via {@code extends}: without the
     * {@code container_name} lines and with the cross-references rewritten
     * to the derived names. The copies live next to the originals so that
     * relative volume paths and the {@code .env} file keep working.
     */
    private File deriveStrippedCopies(File composeFile) {
        try {
            Path dir = composeFile.toPath().getParent();

            // strip the extends-referenced files first, rewriting their
            // names in the main file afterwards
            String main = stripContainerNames(composeFile.toPath());
            Set<String> referenced = findExtendsFileReferences(main);
            for (String reference : referenced) {
                Path referencedFile = dir.resolve(reference);
                if (!referencedFile.toFile().isFile()) {
                    throw new IllegalStateException(
                            "Compose file references " + reference + ", but it does not exist next to " + composeFile);
                }
                Path derived = derivedName(referencedFile);
                String stripped = stripContainerNames(referencedFile);
                Files.writeString(derived, stripped + "\n", StandardCharsets.UTF_8);
                derivedFiles.add(derived);
                main = main.replace(reference, derived.getFileName().toString());
            }

            Path derivedMain = derivedName(composeFile.toPath());
            Files.writeString(derivedMain, main + "\n", StandardCharsets.UTF_8);
            derivedFiles.add(derivedMain);
            return derivedMain.toFile();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Failed to derive a Testcontainers-manageable copy of " + composeFile + ": " + e.getMessage(), e);
        }
    }

    private static String stripContainerNames(Path file) throws IOException {
        return Files.readString(file, StandardCharsets.UTF_8)
                .lines()
                .filter(line -> !line.trim().startsWith("container_name:"))
                .reduce((l1, l2) -> l1 + "\n" + l2)
                .orElse("");
    }

    /** Compose {@code extends} entries look like {@code file: <name>}. */
    private static Set<String> findExtendsFileReferences(String composeContent) {
        Set<String> references = new HashSet<>();
        Matcher matcher = EXTENDS_FILE_PATTERN.matcher(composeContent);
        while (matcher.find()) {
            references.add(matcher.group(1));
        }
        return references;
    }

    /** {@code docker-compose.atlas.yml} -> {@code docker-compose.atlas.sourcelume-it.yml}. */
    private static Path derivedName(Path file) {
        String name = file.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return file.getParent().resolve(name.substring(0, dot) + DERIVED_SUFFIX + name.substring(dot));
    }
}

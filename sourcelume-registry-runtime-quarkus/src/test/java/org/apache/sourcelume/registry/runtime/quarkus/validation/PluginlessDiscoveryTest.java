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
package org.apache.sourcelume.registry.runtime.quarkus.validation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * The no-plugins case: a registry whose classpath carries no validation
 * plugins must refuse to work — no record may be validated by an empty
 * chain. The {@code ValidatorChainProducer} turns this into a boot
 * failure; this test proves the underlying discovery semantics with the
 * <em>real</em> test classpath minus the plugin jars: ServiceLoader finds
 * nothing, and {@code ValidatorChain.discover()} throws instead of
 * returning an empty, silently-accepting chain.
 *
 * <p>The discovery runs in a fresh URLClassLoader parented only by the
 * platform loader (a child of the application loader would still see the
 * plugin service files through parent resource aggregation), using the
 * classpath surefire reports for this module. The loader is also installed
 * as the thread context loader for the call, because
 * {@code ServiceLoader.load(Class)} resolves providers through the TCCL —
 * mixing loaders would surface as "not a subtype" errors, which is
 * exactly the class-identity scenario this test must not trip over.
 */
class PluginlessDiscoveryTest {

    private static final String PLUGIN_MARKER = "sourcelume-registry-validation-";
    private static final String CHAIN_CLASS = "org.apache.sourcelume.registry.core.validation.ValidatorChain";

    @Test
    void discoverFailsLoudlyWithoutValidatorPlugins() throws Exception {
        URL[] fullClasspath = classpath(false);
        URL[] pluginless = classpath(true);
        assertEquals(2, countPluginJars(), "positive control: the test classpath carries both plugins");

        // Positive control: the full classpath discovers both plugins.
        Object chain = discoverIn(fullClasspath);
        assertEquals(2, validatorsIn(chain).size());

        // The plugin-less classpath aborts discovery with IllegalStateException.
        InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> discoverIn(pluginless));
        assertTrue(
                failure.getCause() instanceof IllegalStateException,
                "expected IllegalStateException, got " + failure.getCause());
    }

    private static Object discoverIn(URL[] classpath) throws Exception {
        ClassLoader original = Thread.currentThread().getContextClassLoader();
        try (URLClassLoader loader = new URLClassLoader(classpath, ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            Class<?> chainClass = loader.loadClass(CHAIN_CLASS);
            Method discover = chainClass.getMethod("discover");
            return discover.invoke(null);
        } finally {
            Thread.currentThread().setContextClassLoader(original);
        }
    }

    @SuppressWarnings("unchecked")
    private static java.util.List<Object> validatorsIn(Object chain) throws Exception {
        Method validators = chain.getClass().getMethod("validators");
        return (java.util.List<Object>) validators.invoke(chain);
    }

    private static URL[] classpath(boolean withoutPlugins) {
        return Arrays.stream(classpathEntries())
                .filter(entry -> !withoutPlugins || !entry.contains(PLUGIN_MARKER))
                .map(PluginlessDiscoveryTest::toUrl)
                .toArray(URL[]::new);
    }

    private static String[] classpathEntries() {
        String classpath = System.getProperty("surefire.test.class.path", "");
        return classpath.split(File.pathSeparator);
    }

    private static long countPluginJars() {
        return Arrays.stream(classpathEntries())
                .filter(e -> e.contains(PLUGIN_MARKER))
                .count();
    }

    private static URL toUrl(String entry) {
        try {
            return new File(entry).toURI().toURL();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("Bad classpath entry: " + entry, e);
        }
    }
}
